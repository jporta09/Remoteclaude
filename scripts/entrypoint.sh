#!/usr/bin/env bash
# Levanta TODO el entorno remoto dentro del contenedor:
#   - sshd + mosh : terminal a la que entrás directo por la tailnet (sobrevive al bloqueo)
#   - Xvfb -> x11vnc -> noVNC : navegador real visible desde el celular
# Como el contenedor comparte la red del contenedor de Tailscale, es alcanzable por su
# nombre de MagicDNS sin exponer puertos en el host.
set -euo pipefail

GEOMETRY="${SCREEN_GEOMETRY:-1360x768x24}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
VNC_PORT=5900

# --- Terminal remota (sshd para que entre mosh) ---
# La clave se monta read-only en una ruta de staging; la copiamos a /root/.ssh con
# dueño root y permisos 600 (si no, sshd la rechaza por StrictModes).
AUTH_SRC="/etc/remoteclaude/authorized_keys"
if [ -s "${AUTH_SRC}" ]; then
    install -d -m 700 /root/.ssh
    install -m 600 "${AUTH_SRC}" /root/.ssh/authorized_keys
    # Host keys persistentes (en el volumen /etc/ssh/keys): se generan una sola
    # vez; así la huella SSH no cambia entre rebuilds y el cliente no la rechaza.
    install -d -m 700 /etc/ssh/keys
    [ -f /etc/ssh/keys/ssh_host_ed25519_key ] || ssh-keygen -q -t ed25519 -N "" -f /etc/ssh/keys/ssh_host_ed25519_key
    [ -f /etc/ssh/keys/ssh_host_rsa_key ] || ssh-keygen -q -t rsa -b 3072 -N "" -f /etc/ssh/keys/ssh_host_rsa_key
    /usr/sbin/sshd
    echo "[entrypoint] sshd activo -> entrá con:  mosh root@<host-tailscale>"
else
    echo "[entrypoint] !! sin ${AUTH_SRC}: sshd desactivado."
    echo "[entrypoint]    Montá tu clave pública (ver ssh/authorized_keys.example)."
fi

# --- Entorno gráfico ---
# Limpiar locks de una corrida anterior. Sin esto, al reiniciar el contenedor
# Xvfb encuentra /tmp/.X99-lock viejo, no arranca, y el entrypoint entra en loop.
XNUM="${DISPLAY#:}"
rm -f "/tmp/.X${XNUM}-lock" "/tmp/.X11-unix/X${XNUM}" 2>/dev/null || true

echo "[entrypoint] Display virtual ${DISPLAY} (${GEOMETRY})"
Xvfb "${DISPLAY}" -screen 0 "${GEOMETRY}" -ac +extension RANDR +extension GLX &
# esperar a que exista el socket del display (sin depender de xdpyinfo)
for _ in $(seq 1 50); do
    [ -S "/tmp/.X11-unix/X${XNUM}" ] && break
    sleep 0.2
done

echo "[entrypoint] Window manager (fluxbox)"
fluxbox >/dev/null 2>&1 &

echo "[entrypoint] Compartiendo display por VNC (:${VNC_PORT})"
x11vnc -display "${DISPLAY}" -forever -shared -nopw -rfbport "${VNC_PORT}" -quiet -bg

echo "[entrypoint] Sirviendo noVNC en el puerto ${NOVNC_PORT}"
websockify --web=/usr/share/novnc "${NOVNC_PORT}" "localhost:${VNC_PORT}" &

cat <<EOF

  ============================================================
   Entorno listo (todo dentro del contenedor).
   Navegador:  http://<host-tailscale>:${NOVNC_PORT}/vnc.html?autoconnect=1&resize=remote
   Terminal :  mosh root@<host-tailscale>   ->   tmux new -A -s dev
  ============================================================

EOF

exec "$@"
