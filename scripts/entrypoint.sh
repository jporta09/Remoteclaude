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
if [ -s /root/.ssh/authorized_keys ]; then
    chmod 700 /root/.ssh && chmod 600 /root/.ssh/authorized_keys
    ssh-keygen -A >/dev/null 2>&1   # genera host keys si faltan
    /usr/sbin/sshd
    echo "[entrypoint] sshd activo -> entrá con:  mosh root@<host-tailscale>"
else
    echo "[entrypoint] !! sin /root/.ssh/authorized_keys: sshd desactivado."
    echo "[entrypoint]    Montá tu clave pública (ver ssh/authorized_keys.example)."
fi

# --- Entorno gráfico ---
echo "[entrypoint] Display virtual ${DISPLAY} (${GEOMETRY})"
Xvfb "${DISPLAY}" -screen 0 "${GEOMETRY}" -ac +extension RANDR +extension GLX &
for _ in $(seq 1 30); do
    xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1 && break
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
