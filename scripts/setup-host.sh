#!/usr/bin/env bash
# Prepara el HOST para RemoteMarvin (variante "SSH directo al host", sin gateway root):
#   - Docker            : para los contenedores tailscale (host en la tailnet) y display/noVNC.
#   - openssh-server     : la app SSH-ea al host como tu usuario (solo-clave).
#   - tmux              : sesiones persistentes (sobreviven cortes/bloqueo del celu).
#   - jq + qrencode      : para `scripts/ts-link-qr.sh` (QR del Tailscale embebido).
# La terminal corre como TU usuario; root solo con `sudo` puntual.
#
# Uso:  bash scripts/setup-host.sh
#       bash scripts/setup-host.sh --sin-sudo   (saltea lo que necesita root)
#
# --sin-sudo: para hosts donde no tenés permiso de instalar paquetes ni tocar sshd. Todo lo
# demás (daemons de usuario, ~/.tmux.conf, ~/.ssh/config) es a nivel usuario y se aplica
# igual. Sin esa opción el script moría en el primer `sudo apt-get`, así que no se podía
# usar en un servidor ajeno.
set -euo pipefail

SIN_SUDO=0
[ "${1:-}" = "--sin-sudo" ] && SIN_SUDO=1

SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/setup-host-lib.sh
. "$SCRIPTS/setup-host-lib.sh"

# uv se exige ANTES de tocar nada: es requisito del host (sin uv no hay dictado y el
# setup FALLA más abajo). Chequearlo recién a mitad de corrida obligaba a un segundo
# intento con sshd/tmux ya modificados — el round-trip clásico del primer instalador.
if ! command -v uv >/dev/null 2>&1 && [ ! -x "$HOME/.local/bin/uv" ]; then
    echo "ERROR: falta uv (requisito del host — ejecuta los daemons de dictado)."
    echo "   Instalalo con:  curl -LsSf https://astral.sh/uv/install.sh | sh"
    echo "   y volvé a correr este script. (Nada fue modificado todavía.)"
    exit 1
fi

# Endurecimiento común de las units. No se usa ProtectHome: el render-daemon sirve
# ~/RemoteMarvinDocs y los de STT guardan el modelo en ~/.cache.
HARDENING="NoNewPrivileges=yes
ProtectSystem=full
ProtectKernelTunables=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes"

if [ "$SIN_SUDO" = 1 ]; then
echo "==> saltando lo que necesita root (--sin-sudo)"
echo "    Verificá a mano que el host tenga openssh-server, tmux, jq y qrencode."
# Lo de sshd NO se deja librado a "verificá a mano": es la premisa de todo el modelo.
sshd_acepta_password && RC=0 || RC=$?
case $RC in
    0) echo ""
       echo "    !! ATENCIÓN: el sshd de este host ACEPTA CONTRASEÑAS."
       echo "       RemoteMarvin asume solo-clave: con passwords habilitados el servidor"
       echo "       queda expuesto a fuerza bruta y el pinning de clave no protege de nada."
       echo "       Pedile al admin:  PasswordAuthentication no  en sshd_config"
       echo "" ;;
    1) echo "    sshd: solo-clave ✓ (verificado)" ;;
    *) echo "    sshd: no pude verificarlo (¿no escucha en 127.0.0.1:22?) — chequealo a mano" ;;
esac
else
echo "==> Docker"
DOCKER_RECIEN_INSTALADO=0
if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
    sudo usermod -aG docker "$USER" || true
    DOCKER_RECIEN_INSTALADO=1
    echo "    (cerrá y volvé a entrar a la sesión para usar docker sin sudo)"
else
    echo "    ya instalado"
fi
[ -e /dev/net/tun ] || sudo modprobe tun || true

echo "==> sshd + tmux + jq + qrencode"
# Multi-distro: antes asumía apt-get y moría en no-Debian. El paquete de sshd cambia de nombre
# por familia (openssh-server en Debian/RHEL, openssh en Arch).
if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y openssh-server tmux jq qrencode
elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y openssh-server tmux jq qrencode
elif command -v pacman >/dev/null 2>&1; then
    sudo pacman -Sy --needed --noconfirm openssh tmux jq qrencode
elif command -v zypper >/dev/null 2>&1; then
    sudo zypper install -y openssh tmux jq qrencode
else
    echo "!! no reconozco el gestor de paquetes de esta distro."
    echo "   Instalá a mano: sshd (openssh-server/openssh), tmux, jq, qrencode — y volvé a correr."
    exit 1
fi

echo "==> sshd: solo por clave (sin password)"
sudo tee /etc/ssh/sshd_config.d/remotemarvin.conf >/dev/null <<'EOF'
PasswordAuthentication no
PubkeyAuthentication yes
EOF
sudo systemctl enable --now ssh
sudo systemctl restart ssh
fi

echo "==> tmux.conf (persistencia + portapapeles del celu vía OSC 52)"
if [ ! -f "$HOME/.tmux.conf" ]; then
    cp "$SCRIPTS/marvin.tmux.conf" "$HOME/.tmux.conf"
    echo "    instalado en ~/.tmux.conf"
else
    # Antes acá sólo se imprimía "revisá que tenga…", así que quien ya tenía su tmux.conf se
    # quedaba sin OSC 52 (el portapapeles del celu no funcionaba) y sin el hook que arranca
    # el render-daemon, sin ninguna señal de por qué. Se agrega SÓLO lo que la app necesita,
    # en un bloque con sentinelas que se puede reescribir en cada corrida, respetando el
    # resto de la config. La referencia completa sigue en scripts/marvin.tmux.conf.
    bloque_sentinelas "$HOME/.tmux.conf" "# >>> RemoteMarvin >>>" "# <<< RemoteMarvin <<<" <<TMUX
# Lo mínimo que RemoteMarvin necesita (tu config previa queda intacta arriba).
set -g destroy-unattached off
set -g mouse on
set -g set-clipboard on
set -as terminal-features ',*:clipboard'
set -ga update-environment MARVIN_DISPLAY
set-hook -g client-attached 'run-shell -b "tmux show-environment MARVIN_DISPLAY >/dev/null 2>&1 && XDG_RUNTIME_DIR=/run/user/\$(id -u) systemctl --user start marvin-render.service 2>/dev/null || true"'
TMUX
    echo "    ~/.tmux.conf actualizado (bloque RemoteMarvin; tu config previa intacta)"
fi

echo "==> ssh/config: el display sigue a tu SSH (solo desde sesiones de la app)"
# Si SSH-eás a otro server DESDE una sesión de la app (MARVIN_DISPLAY seteada por la app),
# se tiende un reverse tunnel del display local (X en 6099) al server remoto, para que el
# headed-browser de allá dibuje en ESTE noVNC. Cualquier otro ssh no tiende nada.
SSHCFG="$HOME/.ssh/config"
install -d -m700 "$HOME/.ssh"
install -m755 "$SCRIPTS/marvin-display-allowed" "$HOME/.local/bin/marvin-display-allowed" 2>/dev/null || {
    install -d "$HOME/.local/bin"
    install -m755 "$SCRIPTS/marvin-display-allowed" "$HOME/.local/bin/marvin-display-allowed"; }
install -m755 "$SCRIPTS/marvin-allow-display" "$HOME/.local/bin/marvin-allow-display"
# Diagnóstico del canal de avisos (config-scoping): detecta si Claude corre con un config sin el plugin
# y la notif quedaría muda. Corré `marvin-doctor` (o `marvin-doctor --probe`) cuando algo no avise.
install -m755 "$SCRIPTS/marvin-doctor" "$HOME/.local/bin/marvin-doctor"

# Bloque delimitado por sentinelas: así se puede ACTUALIZAR en cada corrida. Antes se
# hacía append con un grep como guarda, o sea que una versión vieja quedaba para siempre.
BEGIN="# >>> RemoteMarvin >>>"
END="# <<< RemoteMarvin <<<"
touch "$SSHCFG"; chmod 600 "$SSHCFG"
# Migración del bloque viejo (sin sentinelas) para no dejarlo duplicado y activo.
if grep -q "Match exec \"env | grep -q '\^MARVIN_DISPLAY='\"" "$SSHCFG" 2>/dev/null; then
    sed -i "/Match exec \"env | grep -q '\^MARVIN_DISPLAY='\"/,+3d" "$SSHCFG"
    echo "    (migrado el bloque viejo, que reenviaba a CUALQUIER server)"
fi
bloque_sentinelas "$SSHCFG" "$BEGIN" "$END" <<CFG
# A los servers HABILITADOS a los que SSH-ees DESDE la app, llevarles el display (6099,
# headed-browser remoto) y el render-daemon (6090, mostrar HTML/documentos).
# Habilitar uno:  marvin-allow-display <host>
Match exec "\$HOME/.local/bin/marvin-display-allowed %h"
    RemoteForward 6099 127.0.0.1:6099
    RemoteForward 6090 127.0.0.1:6090
    ExitOnForwardFailure no
CFG
echo "    ~/.ssh/config actualizado (ahora sólo a servers habilitados)"

echo "==> token del render-daemon (opcional, NO se genera solo)"
install -d -m700 "$HOME/.config/marvin"
# A propósito no se crea acá: el control primario es el allowlist de arriba (un server no
# habilitado ni siquiera tiene el túnel). Si además querés token, tené en cuenta que los
# clientes que corren EN EL SERVER REMOTO lo buscan en el home de ESE server, así que hay
# que copiárselo o se van a comer un 403:
#     head -c 32 /dev/urandom | base64 | tr -d "\n" > ~/.config/marvin/render-token
#     scp ~/.config/marvin/render-token <server>:~/.config/marvin/render-token
if [ -s "$HOME/.config/marvin/render-token" ]; then
    echo "    activo (recordá copiarlo a los servers habilitados)"
else
    echo "    sin token (el allowlist ya limita quién puede llegar)"
fi

echo "==> render-daemon (mostrar HTML/URL + recibir docs de servers a los que SSH-eás)"
# Servicio systemd --user, pero NO autostart en boot: lo arranca el hook 'client-attached'
# de tmux (marvin.tmux.conf) cuando la APP se conecta. Así sólo corre mientras usás la app,
# no desde el arranque de la PC. Restart=on-failure lo revive si cae estando en uso.
# El linger mantiene vivo el gestor --user para que 'systemctl --user start' ande desde la
# sesión SSH de la app.
DAEMON="$(cd "$(dirname "$0")" && pwd)/marvin-render.py"
install -d "$HOME/.config/systemd/user"
# Sin PrivateTmp a propósito: este daemon deja archivos en /tmp/marvin-render y le pasa
# rutas file:// al browser. Con /tmp privado, un browser lanzado por fuera de la unit
# (MARVIN_BROWSER_CMD) no las vería y el render fallaría en silencio. Ese directorio ya
# está en 0700, que es lo que protege en una máquina multiusuario.
escribir_unidad marvin-render.service <<EOF
[Unit]
Description=RemoteMarvin render/docs daemon (127.0.0.1:6090)

[Service]
ExecStart=$DAEMON
Restart=on-failure
RestartSec=2
$HARDENING
EOF
loginctl enable-linger "$USER" >/dev/null 2>&1 || true
echo "    instalado (lo arranca tmux al conectar la app; no autostart en boot)"

echo "==> stt-daemon (dictado por voz de la app: WAV -> texto con faster-whisper)"
# Bajo demanda: lo arranca el cliente `marvin-stt` en el primer dictado y se apaga solo
# tras 10 min sin uso (idle-exit = salida limpia; por eso sin Restart= ni autostart).
# GPU (CUDA float16) si hay driver NVIDIA; si no, CPU int8 — el daemon decide solo.
# uv es lo que ejecuta los daemons de dictado, y el dictado es parte del contrato del
# host: la app BLOQUEA la conexión a hosts sin configurar (marker setup-ok, más abajo).
# Antes, sin uv, se salteaban las units en silencio y quedaba un host "configurado a
# medias" — permitir ese estado era exactamente el bug. Ahora sin uv el setup FALLA.
UV_BIN="$(command -v uv || true)"
if [ -z "$UV_BIN" ] && [ -x "$HOME/.local/bin/uv" ]; then UV_BIN="$HOME/.local/bin/uv"; fi
if [ -z "$UV_BIN" ]; then
    echo "    ERROR: falta uv, y sin uv no hay dictado (requisito del host)."
    echo "       Instalalo con:  curl -LsSf https://astral.sh/uv/install.sh | sh"
    echo "       y volvé a correr este script."
    exit 1
fi
STT_PY="$(cd "$(dirname "$0")" && pwd)/marvin-stt.py"
# Restart=on-failure: revive crashes pero respeta el idle-exit (salida limpia).
# [Install]: permite `marvin-stt mode always` (enable = arranca en boot); por default
# queda disabled (bajo demanda).
escribir_unidad marvin-stt.service <<EOF
[Unit]
Description=RemoteMarvin STT daemon (dictado, 127.0.0.1:6091)

[Service]
ExecStart=$UV_BIN run --with faster-whisper --with nvidia-cublas-cu12 --with nvidia-cudnn-cu12 $STT_PY
Restart=on-failure
RestartSec=2
$HARDENING
PrivateTmp=yes

[Install]
WantedBy=default.target
EOF
install -d "$HOME/.local/bin"
ln -sf "$(cd "$(dirname "$0")" && pwd)/marvin-stt" "$HOME/.local/bin/marvin-stt"
echo "    instalado (cliente: marvin-stt; daemon bajo demanda en :6091)"

echo "==> stt-live (dictado EN VIVO: parciales por WebSocket mientras hablás)"
# WhisperLiveKit en :6092, con idle-exit propio (marvin-stt-live.py lo apaga tras
# MARVIN_STT_LIVE_IDLE=600s sin uso — una conexión establecida cuenta como uso, y la
# app sostiene una mientras está en primer plano). La app lo despierta al conectar;
# `marvin-stt mode always` lo deja resident junto al batch (VRAM: ~2GB batch + ~2GB
# live, entra en una placa de 6GB).
STT_LIVE_PY="$(cd "$(dirname "$0")" && pwd)/marvin-stt-live.py"
escribir_unidad marvin-stt-live.service <<EOF
[Unit]
Description=RemoteMarvin STT live daemon (dictado en vivo, 127.0.0.1:6092)

[Service]
ExecStart=$UV_BIN run --with whisperlivekit --with nvidia-cublas-cu12 --with nvidia-cudnn-cu12 $STT_LIVE_PY
Restart=on-failure
RestartSec=2
$HARDENING
PrivateTmp=yes

[Install]
WantedBy=default.target
EOF
echo "    instalado (se activa con: marvin-stt mode always, o al primer dictado)"
# Prewarm en background: fuerza la descarga del modelo (~1.6GB) AHORA y no en el primer
# dictado. Manda 1s de silencio; resultado en /tmp/marvin-stt-prewarm.log.
# mktemp en vez de rutas fijas: con un nombre predecible, en una máquina multiusuario
# cualquiera podía dejar el archivo creado de antemano.
PREWARM_WAV="$(mktemp -t marvin-stt-prewarm.XXXXXX.wav)"
PREWARM_LOG="$(mktemp -t marvin-stt-prewarm.XXXXXX.log)"
(
  python3 - "$PREWARM_WAV" <<'PY'
import sys, wave
w = wave.open(sys.argv[1], "wb")
w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
w.writeframes(b"\x00\x00" * 16000)
w.close()
PY
  "$HOME/.local/bin/marvin-stt" < "$PREWARM_WAV" >/dev/null \
    && echo "prewarm STT OK" || echo "prewarm STT falló (se reintenta al primer uso)"
  rm -f "$PREWARM_WAV"
) >"$PREWARM_LOG" 2>&1 &
echo "    prewarm del modelo en background (log: $PREWARM_LOG)"

# Marker de "host configurado": la app lo verifica al conectar y BLOQUEA hosts sin setup
# (antes se conectaba igual y las features fallaban de a una, cada cual con su error).
# Se escribe al FINAL: si algo de arriba falló (set -e), el marker no queda.
escribir_marker_setup "$(dirname "$0")"
echo "==> marker de host configurado: ~/.config/marvin/setup-ok"

cat <<'EOF'

============================================================
 Falta:
 1) Autorizar la clave de la app: pegá el texto del botón 🔑 de
    RemoteMarvin en  ~/.ssh/authorized_keys
      install -d -m700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys
 2) cp .env.example .env  y completar TS_AUTHKEY (con tag:remotemarvin,
    si no el nodo vence a los ~180 dias) + OAuth para el QR
 3) docker compose up -d --build
 Desde la app:  conectá a  <tu-usuario>@remoteclaude:22

 Tip: que la PC no se suspenda y mate todo:
   sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target

 Si las notificaciones "Claude te espera" no llegan, corré:
   marvin-doctor          # dice si Claude corre con un config SIN el plugin (canal mudo)
   marvin-doctor --probe  # manda un aviso de prueba al celu
============================================================
EOF
if [ "${DOCKER_RECIEN_INSTALADO:-0}" = 1 ]; then
    echo " !! docker se instaló EN ESTA corrida: cerrá la sesión y volvé a entrar ANTES"
    echo "    del paso 3 (docker compose…), o va a fallar con 'permission denied'."
    echo ""
fi
