#!/usr/bin/env bash
# Levanta la pantalla virtual aislada con Xvnc (TigerVNC): servidor X + VNC en un
# solo proceso, con soporte NATIVO de redimensionado remoto (SetDesktopSize). Así el
# visor del celu pide resize=remote y la pantalla se adapta a vertical/horizontal.
#   Xvnc :99 (X11 TCP en 6099 + VNC en 5900)  ->  noVNC (websockify :6080)
#
# El entrypoint SUPERVISA lo que levanta: si cualquiera de los procesos muere, sale y deja
# que `restart: unless-stopped` rehaga el contenedor. Antes terminaba en `exec sleep
# infinity`, así que con Xvnc o websockify caídos el contenedor seguía figurando "Up" y el
# visor quedaba mudo sin que nada lo reiniciara ni avisara.
set -euo pipefail

GEOM="${SCREEN_GEOMETRY:-1360x768x24}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
VNC_PORT=5900
XNUM=99
INIT_SIZE="${GEOM%x*}"   # WxH inicial (el visor lo ajusta por resize remoto)

# pid -> nombre, para poder decir CUÁL se murió en vez de "algo se cayó".
declare -A SERVICIOS=()

lanzar() {
    local nombre="$1"; shift
    "$@" &
    SERVICIOS[$!]="$nombre"
}

apagar() {
    # Al parar el contenedor: bajar todo el árbol en vez de dejar procesos colgados.
    trap - TERM INT EXIT
    [ ${#SERVICIOS[@]} -gt 0 ] && kill "${!SERVICIOS[@]}" 2>/dev/null || true
}
trap apagar TERM INT EXIT

# --- password del VNC ------------------------------------------------------------------
# Xvnc corria con -SecurityTypes None: cualquier proceso del host que llegara al 5900 (o al
# 6080) entraba al escritorio sin credencial. El puerto ya no sale del loopback, asi que
# esto es defensa en profundidad contra lo que corra EN la propia maquina.
#
# El password se genera nuevo en cada arranque y se publica en el bind mount para que lo lea
# tu usuario; la app lo toma por SSH y se lo pasa al visor, asi que nunca lo tipeás.
if [ ! -d /marvin ]; then
    echo "[display] ERROR: falta el bind mount /marvin, no puedo publicar el password del VNC" >&2
    echo "           (docker-compose.yml monta \$HOME/.config/marvin ahi)" >&2
    exit 1
fi
# 8 caracteres: es el maximo que admite VncAuth, lo de mas se ignora en silencio.
# Se lee una cantidad ACOTADA de /dev/urandom y recien despues se filtra: con
# `tr < /dev/urandom | head -c 8`, head cierra el pipe, tr se come un SIGPIPE y con
# `pipefail` el script aborta con 141 (pasó: el contenedor quedo en bucle de reinicio).
VNC_PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-8)"
install -d -m700 /root/.vnc
printf '%s\n' "$VNC_PASS" | vncpasswd -f > /root/.vnc/passwd
chmod 600 /root/.vnc/passwd
printf '%s' "$VNC_PASS" > /marvin/vnc-pass
chmod 600 /marvin/vnc-pass
# El contenedor corre como root y el bind mount es del usuario: sin este chown el archivo
# queda de root en 0600 y su duenio no puede leer su propio password.
chown "$(stat -c %u /marvin)":"$(stat -c %g /marvin)" /marvin/vnc-pass
echo "[display] password del VNC generado y publicado en ~/.config/marvin/vnc-pass"

# --- cookie de X -----------------------------------------------------------------------
# Xvnc corria con -ac, o sea SIN control de acceso: cualquier proceso local que llegara al
# 6099 podia conectarse al display, leer la pantalla e inyectar teclas. El VncAuth de arriba
# no cubre esto: es la otra puerta, la del servidor X.
#
# Ahora hace falta la cookie MIT-MAGIC-COOKIE-1. Se publica en el bind mount para que la
# usen los que dibujan acá: el navegador headed del host y los servers habilitados.
COOKIE="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
XAUTH_SRV=/root/.Xauthority-marvin

# El archivo se escribe a mano, en su formato binario, con una entrada FamilyWild (0xFFFF):
# matchea CUALQUIER dirección. `xauth add localhost:99` no sirve acá — resuelve "localhost"
# como la máquina local y guarda una entrada de familia unix, que no aplica a la conexión
# TCP que hace el navegador del host desde afuera del contenedor.
escribir_xauth() {
    python3 - "$1" "$XNUM" "$COOKIE" <<'PY'
import struct, sys
destino, display, cookie = sys.argv[1], sys.argv[2].encode(), bytes.fromhex(sys.argv[3])
nombre = b"MIT-MAGIC-COOKIE-1"
def campo(b): return struct.pack(">H", len(b)) + b
entrada = struct.pack(">H", 0xFFFF) + campo(b"") + campo(display) + campo(nombre) + campo(cookie)
with open(destino, "wb") as f:
    f.write(entrada)
PY
}
escribir_xauth "$XAUTH_SRV"
escribir_xauth /marvin/Xauthority
chmod 600 "$XAUTH_SRV" /marvin/Xauthority
chown "$(stat -c %u /marvin)":"$(stat -c %g /marvin)" /marvin/Xauthority
echo "[display] cookie de X publicada en ~/.config/marvin/Xauthority"

# locks viejos (importante si el contenedor se reinicia)
rm -f "/tmp/.X${XNUM}-lock" "/tmp/.X11-unix/X${XNUM}" 2>/dev/null || true

echo "[display] Xvnc :${XNUM} (${INIT_SIZE}, resize dinámico) — X11 TCP :60${XNUM} + VNC :${VNC_PORT}"
# -SecurityTypes VncAuth: pide el password. La app lo lee por SSH y lo pasa.
# -listen tcp: el navegador del HOST dibuja vía DISPLAY=localhost:99 (TCP 6099), pero ahora
# con cookie (-auth) en vez de -ac. Quien dibuje acá necesita ~/.config/marvin/Xauthority.
lanzar Xvnc \
    Xvnc ":${XNUM}" -geometry "${INIT_SIZE}" -depth 24 -rfbport "${VNC_PORT}" \
    -SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd \
    -AlwaysShared -listen tcp -auth "$XAUTH_SRV" -desktop marvin

# Esperar el socket ANTES de seguir: si Xvnc no levanta, xrandr y fluxbox fallan con errores
# crípticos y el contenedor quedaba igual "arriba". Mejor morir acá, con el motivo claro.
for _ in $(seq 1 50); do [ -S "/tmp/.X11-unix/X${XNUM}" ] && break; sleep 0.2; done
if [ ! -S "/tmp/.X11-unix/X${XNUM}" ]; then
    echo "[display] ERROR: Xvnc no levantó en 10s" >&2
    exit 1
fi

# Modo landscape fijo "1920x1080" para el modo "Escritorio" del visor: la app lo
# selecciona (xrandr --output VNC-0 --mode 1920x1080) cuando NO está ajustado a pantalla.
# Más resolución = más detalle para el zoom nítido (noVNC re-rasteriza del framebuffer).
XAUTHORITY="$XAUTH_SRV" DISPLAY=":${XNUM}" xrandr --newmode 1920x1080 173.00 1920 2048 2248 2576 1080 1083 1088 1120 -hsync +vsync 2>/dev/null || true
XAUTHORITY="$XAUTH_SRV" DISPLAY=":${XNUM}" xrandr --addmode VNC-0 1920x1080 2>/dev/null || true

echo "[display] window manager (fluxbox) + fondo petróleo"
# Fondo inicial petróleo. fluxbox llama a fbsetbg (wrapper -> xsetroot petróleo)
# al arrancar y en cada resize, así el fondo se mantiene y NO aparece ningún warning.
XAUTHORITY="$XAUTH_SRV" DISPLAY=":${XNUM}" xsetroot -solid "#0F232D" 2>/dev/null || true
lanzar fluxbox env XAUTHORITY="$XAUTH_SRV" DISPLAY=":${XNUM}" fluxbox

echo "[display] noVNC en :${NOVNC_PORT}"
lanzar websockify websockify --web=/usr/share/novnc "${NOVNC_PORT}" "localhost:${VNC_PORT}"

# Un comando explícito (docker run … otra-cosa) también entra a la supervisión, en vez de
# reemplazar al entrypoint y dejar los servicios sin vigilar.
if [ "$#" -gt 0 ] && [ "$1" != "sleep" ]; then
    lanzar "cmd:$1" "$@"
fi

cat <<EOF

  ============================================================
   Pantalla virtual lista (Xvnc, resize remoto).
   En el HOST, para que el navegador dibuje aca (el display exige cookie):
     XAUTHORITY=~/.config/marvin/Xauthority DISPLAY=localhost:${XNUM} <comando>
   Miralo desde el celu: boton 🖥 de la app (tuneliza el ${NOVNC_PORT} por SSH)
  ============================================================

EOF

# Acá se queda: `wait -n` vuelve en cuanto CUALQUIERA de los servicios termine.
wait -n || true
muerto="(desconocido)"
for pid in "${!SERVICIOS[@]}"; do
    kill -0 "$pid" 2>/dev/null || { muerto="${SERVICIOS[$pid]}"; break; }
done
echo "[display] ERROR: murió '$muerto' — salgo para que docker rehaga el contenedor" >&2
exit 1
