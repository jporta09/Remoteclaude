#!/usr/bin/env bash
# Levanta la pantalla virtual aislada:
#   Xvfb (con TCP, para que el host dibuje) -> x11vnc -> noVNC (visor web).
set -euo pipefail

GEOM="${SCREEN_GEOMETRY:-1360x768x24}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
VNC_PORT=5900
XNUM=99

# locks viejos (importante si el contenedor se reinicia)
rm -f "/tmp/.X${XNUM}-lock" "/tmp/.X11-unix/X${XNUM}" 2>/dev/null || true

echo "[display] Xvfb :${XNUM} (${GEOM}) con TCP habilitado"
Xvfb ":${XNUM}" -screen 0 "${GEOM}" -listen tcp -ac +extension RANDR +extension GLX &
for _ in $(seq 1 50); do [ -S "/tmp/.X11-unix/X${XNUM}" ] && break; sleep 0.2; done

echo "[display] window manager (fluxbox)"
DISPLAY=":${XNUM}" fluxbox >/dev/null 2>&1 &
# fluxbox abre un xmessage quejándose de que no hay app de wallpaper; lo cerramos.
( sleep 3; pkill -x xmessage 2>/dev/null || true ) &

echo "[display] x11vnc sobre :${XNUM}"
x11vnc -display ":${XNUM}" -forever -shared -nopw -rfbport "${VNC_PORT}" -quiet -bg

echo "[display] noVNC en :${NOVNC_PORT}"
websockify --web=/usr/share/novnc "${NOVNC_PORT}" "localhost:${VNC_PORT}" &

cat <<EOF

  ============================================================
   Pantalla virtual lista.
   En el HOST, para que el navegador dibuje aca:
     DISPLAY=localhost:${XNUM} npx playwright test --headed
   Miralo en el celu:
     http://remoteclaude:${NOVNC_PORT}/vnc.html?autoconnect=1&resize=scale
  ============================================================

EOF

exec "$@"
