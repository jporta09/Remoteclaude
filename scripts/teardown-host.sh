#!/usr/bin/env bash
# Deshace lo que scripts/setup-host.sh dejó en el HOST. Es el par de setup: quita las units de
# usuario, los helpers de ~/.local/bin, los bloques con sentinelas en ~/.tmux.conf y ~/.ssh/config,
# el drop-in de sshd, el linger, y baja los contenedores de docker.
#
# Uso:
#   bash scripts/teardown-host.sh                 # desinstala; conserva datos y volúmenes
#   bash scripts/teardown-host.sh --purgar-datos  # además borra el estado del nodo Tailscale
#                                                 # (volumen ts-state) y ~/.config/marvin
#   bash scripts/teardown-host.sh --sin-sudo      # no toca sshd (para hosts sin root)
#
# A propósito NO toca ~/.ssh/authorized_keys (la clave de la app la agregaste vos a mano; borrarla
# a ciegas por una etiqueta podía llevarse OTRA clave), NI te saca del grupo docker que el setup
# agrega cuando instala Docker (podías estar en él desde antes), NI borra los nodos del tailnet
# (eso se hace desde la consola de admin). Las tres quedan como pasos guiados al final.
set -euo pipefail

PURGAR=0
SIN_SUDO=0
for arg in "$@"; do
    case "$arg" in
        --purgar-datos) PURGAR=1 ;;
        --sin-sudo)     SIN_SUDO=1 ;;
        -h|--help)      sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "opción desconocida: $arg (ver --help)"; exit 2 ;;
    esac
done

SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$SCRIPTS/.." && pwd)"
# shellcheck source=scripts/setup-host-lib.sh
. "$SCRIPTS/setup-host-lib.sh"

echo "==> Servicios de usuario (marvin-render / stt / stt-live)"
for u in marvin-render.service marvin-stt.service marvin-stt-live.service; do
    quitar_unidad "$u"
    echo "    quitado: $u"
done

echo "==> Helpers en ~/.local/bin"
for h in marvin-stt marvin-display-allowed marvin-allow-display; do
    if [ -e "$HOME/.local/bin/$h" ] || [ -L "$HOME/.local/bin/$h" ]; then
        rm -f "$HOME/.local/bin/$h"
        echo "    quitado: ~/.local/bin/$h"
    fi
done

echo "==> Bloque RemoteMarvin en ~/.tmux.conf y ~/.ssh/config"
BEGIN="# >>> RemoteMarvin >>>"
END="# <<< RemoteMarvin <<<"
quitar_bloque_sentinelas "$HOME/.tmux.conf" "$BEGIN" "$END"
quitar_bloque_sentinelas "$HOME/.ssh/config" "$BEGIN" "$END"
echo "    listo (el resto de esos archivos queda intacto)"
# Si ~/.tmux.conf era EXACTAMENTE nuestra copia (instalada entera cuando no existía uno previo),
# ya no queda nada útil y sin el bloque quedaría un archivo raro; avisamos en vez de borrarlo solo.
if [ -f "$HOME/.tmux.conf" ] && cmp -s "$HOME/.tmux.conf" "$SCRIPTS/marvin.tmux.conf"; then
    echo "    (~/.tmux.conf es la copia que instaló el setup; borralo si no lo querés: rm ~/.tmux.conf)"
fi

if [ "$SIN_SUDO" = 1 ]; then
    echo "==> sshd: salteado (--sin-sudo)"
else
    echo "==> sshd: quitar el drop-in solo-clave"
    if [ -f /etc/ssh/sshd_config.d/remotemarvin.conf ]; then
        sudo rm -f /etc/ssh/sshd_config.d/remotemarvin.conf
        # ssh o sshd según la distro; reiniciar el que exista.
        sudo systemctl restart ssh 2>/dev/null || sudo systemctl restart sshd 2>/dev/null || true
        echo "    quitado /etc/ssh/sshd_config.d/remotemarvin.conf"
        echo "    !! OJO: sin ese drop-in vuelve el default de tu sshd. Si permitía contraseñas,"
        echo "       ahora tu server las vuelve a aceptar — revisá sshd_config si te importa."
    else
        echo "    no estaba (nada que quitar)"
    fi
fi

echo "==> Contenedores de docker (tailscale + display/noVNC)"
if command -v docker >/dev/null 2>&1 && [ -f "$REPO/docker-compose.yml" ]; then
    if [ "$PURGAR" = 1 ]; then
        ( cd "$REPO" && docker compose down -v ) && \
            echo "    bajados y volúmenes borrados (ts-state incluido: el nodo se re-enrola de cero)"
    else
        ( cd "$REPO" && docker compose down ) && \
            echo "    bajados (el volumen ts-state se conserva; usá --purgar-datos para borrarlo)"
    fi
else
    echo "    docker o docker-compose.yml no disponibles — salteado"
fi

echo "==> linger del usuario"
# El setup lo habilitó para que 'systemctl --user' ande desde la sesión SSH de la app. Lo
# deshabilitamos, pero avisamos por si algún otro servicio tuyo dependía de él.
loginctl disable-linger "$USER" >/dev/null 2>&1 || true
echo "    deshabilitado (si tenías otros servicios --user que lo necesitaban, reactivalo:"
echo "    loginctl enable-linger $USER)"

if [ "$PURGAR" = 1 ]; then
    echo "==> ~/.config/marvin (token del render-daemon + password del VNC)"
    rm -f "$HOME/.config/marvin/render-token" "$HOME/.config/marvin/vnc-pass" 2>/dev/null || true
    rmdir "$HOME/.config/marvin" 2>/dev/null || true
    echo "    borrado lo que hubiera (si quedaba algo tuyo ahí, el rmdir lo respeta)"
fi

# Grupo docker: el setup agrega al usuario CUANDO instala Docker (usermod -aG docker). NO lo
# revertimos solos —podías estar en el grupo desde ANTES y sacarte a ciegas rompería otra cosa,
# la misma razón por la que no tocamos authorized_keys— pero sí lo nombramos: quien llega al
# socket de docker es root efectivo en el host, y eso no puede quedar como residuo mudo.
if id -nG "$USER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
    PASO_DOCKER=" 3) Tu usuario SIGUE en el grupo \"docker\" (= root efectivo en el host: quien
    llega al socket de docker puede todo). El setup te agrega ahí sólo cuando
    INSTALA Docker; si fue por eso y ya no lo querés:
        sudo gpasswd -d $USER docker
    (después, cerrá y volvé a entrar). No te sacamos solos: podías estar antes."
else
    PASO_DOCKER=" 3) Grupo \"docker\": tu usuario no está en él, nada que hacer."
fi

# .env sólo para nombrar el nodo del host en la guía de abajo.
TS_HOSTNAME="remoteclaude"
[ -f "$REPO/.env" ] && TS_HOSTNAME="$(grep -E '^TS_HOSTNAME=' "$REPO/.env" | tail -1 | cut -d= -f2- || true)"
TS_HOSTNAME="${TS_HOSTNAME:-remoteclaude}"

cat <<EOF

============================================================
 Desinstalación local: LISTA. Falta lo que este script NO toca a propósito:

 1) Clave de la app en ~/.ssh/authorized_keys
    Borrala vos (a mano): abrí el archivo y quitá la línea de la clave de
    RemoteMarvin. No la tocamos solos para no llevarnos otra clave por error.

 2) Nodos del tailnet (desde https://login.tailscale.com/admin/machines)
    - el host:  "$TS_HOSTNAME"
    - el celu:  el nodo del teléfono (el que enrolaste por QR)
    Borralos ahí si ya no vas a usar RemoteMarvin. (En el celu, además, la app
    tiene su propio "olvidar / desvincular".)

$PASO_DOCKER

 4) Paquetes (openssh-server, tmux, jq, qrencode) y Docker: quedan instalados.
    Si los pusiste sólo para esto, desinstalalos con tu gestor de paquetes.
============================================================
EOF
