#!/usr/bin/env bash
# Levanta la pantalla virtual aislada con Xvnc (TigerVNC): servidor X + VNC en un
# solo proceso, con soporte NATIVO de redimensionado remoto (SetDesktopSize). Así el
# visor del celu pide resize=remote y la pantalla se adapta a vertical/horizontal.
#   Xvnc :99 (X11 TCP en 6099 + VNC en 5900)  ->  noVNC (websockify :6080)
set -euo pipefail

GEOM="${SCREEN_GEOMETRY:-1360x768x24}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
VNC_PORT=5900
XNUM=99
INIT_SIZE="${GEOM%x*}"   # WxH inicial (el visor lo ajusta por resize remoto)

# locks viejos (importante si el contenedor se reinicia)
rm -f "/tmp/.X${XNUM}-lock" "/tmp/.X11-unix/X${XNUM}" 2>/dev/null || true

echo "[display] Xvnc :${XNUM} (${INIT_SIZE}, resize dinámico) — X11 TCP :60${XNUM} + VNC :${VNC_PORT}"
# -SecurityTypes None: sin password (el acceso lo limita la tailnet / localhost).
# -listen tcp + -ac: el navegador del HOST dibuja vía DISPLAY=localhost:99 (TCP 6099).
Xvnc ":${XNUM}" -geometry "${INIT_SIZE}" -depth 24 -rfbport "${VNC_PORT}" \
    -SecurityTypes None -AlwaysShared -listen tcp -ac -desktop marvin >/dev/null 2>&1 &
for _ in $(seq 1 50); do [ -S "/tmp/.X11-unix/X${XNUM}" ] && break; sleep 0.2; done

# Modo landscape fijo "1280x720" para el modo "Escritorio" del visor: la app lo
# selecciona (xrandr --output VNC-0 --mode 1280x720) cuando NO está ajustado a pantalla.
DISPLAY=":${XNUM}" xrandr --newmode 1280x720 74.50 1280 1344 1472 1664 720 723 728 748 -hsync +vsync 2>/dev/null || true
DISPLAY=":${XNUM}" xrandr --addmode VNC-0 1280x720 2>/dev/null || true

echo "[display] window manager (fluxbox) + fondo petróleo"
# Fondo inicial petróleo. fluxbox llama a fbsetbg (wrapper -> xsetroot petróleo)
# al arrancar y en cada resize, así el fondo se mantiene y NO aparece ningún warning.
DISPLAY=":${XNUM}" xsetroot -solid "#0F232D" 2>/dev/null || true
DISPLAY=":${XNUM}" fluxbox >/dev/null 2>&1 &

echo "[display] noVNC en :${NOVNC_PORT}"
websockify --web=/usr/share/novnc "${NOVNC_PORT}" "localhost:${VNC_PORT}" &

cat <<EOF

  ============================================================
   Pantalla virtual lista (Xvnc, resize remoto).
   En el HOST, para que el navegador dibuje aca:
     DISPLAY=localhost:${XNUM} npx playwright test --headed
   Miralo en el celu:
     http://remoteclaude:${NOVNC_PORT}/vnc.html?autoconnect=1&resize=remote
  ============================================================

EOF

exec "$@"
