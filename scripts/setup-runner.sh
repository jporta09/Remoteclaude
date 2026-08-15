#!/usr/bin/env bash
# Instala un runner self-hosted de GitHub Actions en ESTA máquina, como servicio de usuario.
#
# Por qué: el CI hospedado por GitHub dejó de arrancar por facturación ("recent account
# payments have failed or your spending limit needs to be increased") y los jobs fallan en
# 3 segundos sin ejecutar nada. Un runner propio devuelve el gate, y de paso habilita a
# futuro correr los E2E instrumentados acá (esta PC ya tiene KVM, AVD y Docker), que en un
# runner virtualizado tardaban ~30 min por la traducción ARM.
#
# Uso:  bash scripts/setup-runner.sh              instala/actualiza y arranca
#       bash scripts/setup-runner.sh --estado     qué hay instalado y si está conectado
#       bash scripts/setup-runner.sh --borrar     lo desregistra de GitHub y borra todo
#
# Requisitos: `gh` autenticado con permiso de admin sobre el repo (de ahí sale el token de
# registro, que es efímero y NUNCA se imprime ni se guarda en disco).
#
# ============================ SEGURIDAD, LEER UNA VEZ ============================
# Un runner self-hosted ejecuta el workflow de cada push CON TU USUARIO: ve ~/.ssh, el
# keystore de firma, .env y el resto de tu HOME. Eso es tolerable acá SOLO porque el repo
# es privado: nadie más que vos puede empujar código que dispare un job.
#
# Si el repo se hiciera público, esto se vuelve una ejecución remota de código para
# cualquiera que abra un PR desde un fork: en ese caso hay que borrar el runner
# (`--borrar`) ANTES de cambiar la visibilidad.
# ================================================================================
set -euo pipefail

# Fijada a propósito: el instalador verifica el sha256 contra el release, y una versión
# flotante haría que "verificado" signifique "verificado contra lo que hubiera hoy".
VERSION="${MARVIN_RUNNER_VERSION:-2.336.0}"
DIR="${MARVIN_RUNNER_DIR:-$HOME/actions-runner}"
UNIDAD="marvin-gh-runner"
ETIQUETAS="self-hosted,linux,x64,marvin"
# Variable de repo que el workflow lee para decidir dónde corre. Vive en GitHub, no en el
# repo: así se vuelve al CI hospedado desde la web, sin un commit.
VAR_REPO="MARVIN_RUNNER"

REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
SVC_DIR="$HOME/.config/systemd/user"

estado() {
    echo "==> runner"
    if [ -f "$DIR/.runner" ]; then
        echo "    instalado en $DIR (v$(cat "$DIR/.runner.version" 2>/dev/null || echo '?'))"
    else
        echo "    no instalado"
    fi
    echo "    servicio: $(systemctl --user is-active "$UNIDAD" 2>/dev/null || echo inactivo)"
    echo "==> lo que ve GitHub"
    # Con reintentos: recién arrancado el servicio, el runner tarda unos segundos en
    # aparecer conectado, y preguntar una sola vez lo mostraba "offline" cuando estaba
    # perfecto — un estado falso es peor que no informar nada.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        VISTO="$(gh api "repos/$REPO/actions/runners" \
            --jq '.runners[] | "    \(.name): \(.status) (\(.busy | if . then "ocupado" else "libre" end))"' \
            2>/dev/null || true)"
        case "$VISTO" in *online*) break ;; esac
        sleep 2
    done
    echo "${VISTO:-    no pude consultarlo}"
    echo "==> a dónde apunta el workflow"
    # `gh variable list`, no `gh variable get`: ese subcomando no existe, y el `|| echo`
    # que tenía acá se tragaba el error e informaba "sin definir" con la variable puesta.
    VALOR="$(gh variable list --repo "$REPO" --json name,value \
        --jq ".[] | select(.name==\"$VAR_REPO\") | .value" 2>/dev/null || true)"
    echo "    $VAR_REPO=${VALOR:-(sin definir -> ubuntu-latest)}"
}

borrar() {
    echo "==> apagando el servicio"
    systemctl --user disable --now "$UNIDAD" 2>/dev/null || true
    rm -f "$SVC_DIR/$UNIDAD.service"
    systemctl --user daemon-reload 2>/dev/null || true
    if [ -f "$DIR/.runner" ]; then
        echo "==> desregistrando de GitHub"
        # Sin esto queda un runner fantasma "offline" y los jobs se encolan 24 h antes de
        # fallar, que es el peor de los mundos: ni corre ni avisa.
        TOKEN="$(gh api -X POST "repos/$REPO/actions/runners/remove-token" --jq .token)"
        (cd "$DIR" && ./config.sh remove --token "$TOKEN")
    fi
    echo "==> devolviendo el workflow a ubuntu-latest"
    gh variable delete "$VAR_REPO" --repo "$REPO" 2>/dev/null || true
    echo "    listo. El directorio $DIR queda por si querés revisar los logs."
}

case "${1:-}" in
    --estado) estado; exit 0 ;;
    --borrar) borrar; exit 0 ;;
    "") ;;
    *) echo "opción desconocida: $1"; sed -n '4,8p' "$0"; exit 2 ;;
esac

# ---------------------------------------------------------------- requisitos
echo "==> requisitos"
gh auth status >/dev/null 2>&1 || { echo "    ✗ gh no está autenticado: corré 'gh auth login'"; exit 1; }
# El runtime .NET del runner necesita ICU; sin él arranca en modo "invariant" y falla al
# parsear fechas de los workflows, con un error que no dice nada de esto.
# `grep -c` y no `grep -q`: con -q grep corta al primer match, ldconfig se come un SIGPIPE y
# sale 141, y bajo `pipefail` el pipeline "falla" aunque la biblioteca esté instalada.
[ "$(ldconfig -p | grep -c libicuuc)" -gt 0 ] || { echo "    ✗ falta libicu (sudo apt-get install libicu-dev)"; exit 1; }
[ "$(gh repo view --json isPrivate --jq .isPrivate)" = "true" ] || {
    echo "    ✗ el repo es PÚBLICO: un runner self-hosted acá deja que cualquier PR desde"
    echo "      un fork ejecute código en tu máquina. Leé el bloque de seguridad de arriba."
    exit 1
}
echo "    gh ✓  ·  libicu ✓  ·  repo privado ✓  ·  $REPO"

# ---------------------------------------------------------------- descarga
TAR="actions-runner-linux-x64-${VERSION}.tar.gz"
mkdir -p "$DIR"
cd "$DIR"
if [ ! -f "$TAR" ]; then
    echo "==> bajando el runner v$VERSION"
    curl -fsSL -o "$TAR" \
        "https://github.com/actions/runner/releases/download/v${VERSION}/${TAR}"
fi

echo "==> verificando el paquete"
# El sha256 lo publica actions/runner en el cuerpo del release, dentro de un comentario
# HTML. Se compara antes de extraer: estamos por ejecutar esto como nuestro usuario.
ESPERADO="$(gh api "repos/actions/runner/releases/tags/v${VERSION}" --jq .body \
    | grep -oP '(?<=<!-- BEGIN SHA linux-x64 -->)[0-9a-f]{64}')"
REAL="$(sha256sum "$TAR" | cut -d' ' -f1)"
[ -n "$ESPERADO" ] || { echo "    ✗ no encontré el sha256 publicado para v$VERSION"; exit 1; }
[ "$ESPERADO" = "$REAL" ] || { echo "    ✗ SHA256 NO COINCIDE — no lo extraigo"; exit 1; }
echo "    sha256 ✓ coincide con el release oficial"

[ -f "$DIR/config.sh" ] || tar xzf "$TAR"
echo "$VERSION" > "$DIR/.runner.version"

# ---------------------------------------------------------------- registro
if [ -f "$DIR/.runner" ]; then
    echo "==> ya estaba registrado (reusando la registración)"
else
    echo "==> registrando en $REPO"
    # Token efímero (1 h) pedido en el momento: no se imprime, no se guarda, no queda en
    # el historial del shell.
    TOKEN="$(gh api -X POST "repos/$REPO/actions/runners/registration-token" --jq .token)"
    ./config.sh --unattended --replace \
        --url "https://github.com/$REPO" \
        --token "$TOKEN" \
        --name "marvin-$(hostname -s)" \
        --labels "$ETIQUETAS" \
        --work _work
fi

# ---------------------------------------------------------------- entorno de los jobs
# En ubuntu-latest el SDK de Android viene preinstalado y con ANDROID_HOME puesto; acá no,
# y el servicio de systemd no hereda nada del shell. Localmente el build lo saca de
# android/local.properties, que está gitignoreado y por lo tanto no existe en el checkout
# del runner: los tres pasos de Gradle morían con "SDK location not found".
#
# Va en el .env del runner (que él lee al arrancar) y no en el workflow, para que el
# workflow no dependa de rutas de esta máquina y siga sirviendo en los runners de GitHub.
echo "==> entorno para los jobs"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.buildozer/android/platform/android-sdk}}"
if [ -d "$SDK/platform-tools" ]; then
    printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$SDK" "$SDK" > "$DIR/.env"
    echo "    SDK de Android: $SDK"
else
    echo "    ⚠ no encontré el SDK de Android en $SDK"
    echo "      los jobs de Gradle van a fallar con 'SDK location not found'."
    echo "      Definí ANDROID_SDK_ROOT y volvé a correr este script."
fi

# ---------------------------------------------------------------- servicio
# El svc.sh oficial instala una unit de SISTEMA y necesita sudo. Esta es de usuario: el
# runner corre con los mismos permisos que si lo lanzaras a mano, que es exactamente el
# nivel de acceso que el workflow va a tener de todos modos.
echo "==> servicio de usuario ($UNIDAD)"
mkdir -p "$SVC_DIR"
cat > "$SVC_DIR/$UNIDAD.service" <<UNIT
[Unit]
Description=GitHub Actions runner de RemoteMarvin
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$DIR/run.sh
WorkingDirectory=$DIR
Restart=always
RestartSec=10
KillMode=process
KillSignal=SIGTERM
TimeoutStopSec=5min

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable "$UNIDAD"
# `restart` y no `enable --now`: sobre un servicio ya activo, --now no hace nada y los
# cambios del .env de arriba no se aplicarían hasta el próximo reinicio de la máquina.
systemctl --user restart "$UNIDAD"

# linger: sin esto el servicio se apaga al cerrar sesión gráfica y el CI se cae sin aviso.
loginctl enable-linger "$USER" >/dev/null 2>&1 || \
    echo "    (no pude habilitar linger; el runner se apagará al cerrar sesión)"

# ---------------------------------------------------------------- apuntar el workflow
echo "==> apuntando el workflow al runner"
# Recién ahora: si se definiera antes de que el runner esté conectado, los pushes del
# medio quedarían encolados esperando a nadie.
gh variable set "$VAR_REPO" --repo "$REPO" --body "self-hosted"

echo ""
estado
echo ""
echo "Para volver al CI hospedado por GitHub cuando se arregle la facturación:"
echo "    gh variable delete $VAR_REPO --repo $REPO      (o desde Settings > Variables)"
echo "y si además querés sacar el runner de la máquina:"
echo "    bash scripts/setup-runner.sh --borrar"
