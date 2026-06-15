#!/usr/bin/env bash
# Levanta el entorno gráfico persistente DENTRO del contenedor:
#   Xvfb (display virtual)  ->  x11vnc (lo comparte por VNC)  ->  noVNC (lo sirve por web)
# Como todo vive del lado del servidor, bloquear el celular NO rompe nada:
# recargás la pestaña de noVNC y el navegador sigue donde estaba.
set -euo pipefail

GEOMETRY="${SCREEN_GEOMETRY:-1360x768x24}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
VNC_PORT=5900

echo "[entrypoint] Display virtual ${DISPLAY} (${GEOMETRY})"
Xvfb "${DISPLAY}" -screen 0 "${GEOMETRY}" -ac +extension RANDR +extension GLX &
# esperar a que el display esté listo
for i in $(seq 1 30); do
    if xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1; then break; fi
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
   Entorno listo.
   Abrí en el navegador del celular (vía Tailscale):
     http://<nombre-tailscale-del-host>:${NOVNC_PORT}/vnc.html?autoconnect=1&resize=remote
   Entrá a la shell del contenedor desde el host con:
     docker exec -it remoteclaude-devbox bash
  ============================================================

EOF

exec "$@"
