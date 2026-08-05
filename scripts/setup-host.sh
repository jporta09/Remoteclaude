#!/usr/bin/env bash
# Prepara el HOST para RemoteMarvin (variante "SSH directo al host", sin gateway root):
#   - Docker            : para los contenedores tailscale (host en la tailnet) y display/noVNC.
#   - openssh-server     : la app SSH-ea al host como tu usuario (solo-clave).
#   - tmux              : sesiones persistentes (sobreviven cortes/bloqueo del celu).
#   - jq + qrencode      : para `scripts/ts-link-qr.sh` (QR del Tailscale embebido).
# La terminal corre como TU usuario; root solo con `sudo` puntual.
#
# Uso:  bash scripts/setup-host.sh
set -euo pipefail

SCRIPTS="$(cd "$(dirname "$0")" && pwd)"

echo "==> Docker"
if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
    sudo usermod -aG docker "$USER" || true
    echo "    (cerrá y volvé a entrar a la sesión para usar docker sin sudo)"
else
    echo "    ya instalado"
fi
[ -e /dev/net/tun ] || sudo modprobe tun || true

echo "==> sshd + tmux + jq + qrencode"
sudo apt-get update
sudo apt-get install -y openssh-server tmux jq qrencode

echo "==> sshd: solo por clave (sin password)"
sudo tee /etc/ssh/sshd_config.d/remotemarvin.conf >/dev/null <<'EOF'
PasswordAuthentication no
PubkeyAuthentication yes
EOF
sudo systemctl enable --now ssh
sudo systemctl restart ssh

echo "==> tmux.conf (persistencia + portapapeles del celu vía OSC 52)"
if [ ! -f "$HOME/.tmux.conf" ]; then
    cp "$(dirname "$0")/marvin.tmux.conf" "$HOME/.tmux.conf"
    echo "    instalado en ~/.tmux.conf"
else
    echo "    ya existe ~/.tmux.conf — revisá que tenga: set -g mouse on / set -g set-clipboard on"
    echo "    (referencia: scripts/marvin.tmux.conf)"
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

# Bloque delimitado por sentinelas: así se puede ACTUALIZAR en cada corrida. Antes se
# hacía append con un grep como guarda, o sea que una versión vieja quedaba para siempre.
BEGIN="# >>> RemoteMarvin >>>"
END="# <<< RemoteMarvin <<<"
touch "$SSHCFG"; chmod 600 "$SSHCFG"
if grep -qF "$BEGIN" "$SSHCFG"; then
    sed -i "/$BEGIN/,/$END/d" "$SSHCFG"
fi
# Migración del bloque viejo (sin sentinelas) para no dejarlo duplicado y activo.
if grep -q "Match exec \"env | grep -q '\^MARVIN_DISPLAY='\"" "$SSHCFG" 2>/dev/null; then
    sed -i "/Match exec \"env | grep -q '\^MARVIN_DISPLAY='\"/,+3d" "$SSHCFG"
    echo "    (migrado el bloque viejo, que reenviaba a CUALQUIER server)"
fi
cat >> "$SSHCFG" <<CFG
$BEGIN
# A los servers HABILITADOS a los que SSH-ees DESDE la app, llevarles el display (6099,
# headed-browser remoto) y el render-daemon (6090, mostrar HTML/documentos).
# Habilitar uno:  marvin-allow-display <host>
Match exec "\$HOME/.local/bin/marvin-display-allowed %h"
    RemoteForward 6099 127.0.0.1:6099
    RemoteForward 6090 127.0.0.1:6090
    ExitOnForwardFailure no
$END
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
cat > "$HOME/.config/systemd/user/marvin-render.service" <<EOF
[Unit]
Description=RemoteMarvin render/docs daemon (127.0.0.1:6090)

[Service]
ExecStart=$DAEMON
Restart=on-failure
RestartSec=2
EOF
systemctl --user daemon-reload
loginctl enable-linger "$USER" >/dev/null 2>&1 || true
echo "    instalado (lo arranca tmux al conectar la app; no autostart en boot)"

echo "==> stt-daemon (dictado por voz de la app: WAV -> texto con faster-whisper)"
# Bajo demanda: lo arranca el cliente `marvin-stt` en el primer dictado y se apaga solo
# tras 10 min sin uso (idle-exit = salida limpia; por eso sin Restart= ni autostart).
# GPU (CUDA float16) si hay driver NVIDIA; si no, CPU int8 — el daemon decide solo.
UV_BIN="$(command -v uv || echo "$HOME/.local/bin/uv")"
STT_PY="$(cd "$(dirname "$0")" && pwd)/marvin-stt.py"
# Restart=on-failure: revive crashes pero respeta el idle-exit (salida limpia).
# [Install]: permite `marvin-stt mode always` (enable = arranca en boot); por default
# queda disabled (bajo demanda).
cat > "$HOME/.config/systemd/user/marvin-stt.service" <<EOF
[Unit]
Description=RemoteMarvin STT daemon (dictado, 127.0.0.1:6091)

[Service]
ExecStart=$UV_BIN run --with faster-whisper --with nvidia-cublas-cu12 --with nvidia-cudnn-cu12 $STT_PY
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
install -d "$HOME/.local/bin"
ln -sf "$(cd "$(dirname "$0")" && pwd)/marvin-stt" "$HOME/.local/bin/marvin-stt"
echo "    instalado (cliente: marvin-stt; daemon bajo demanda en :6091)"

echo "==> stt-live (dictado EN VIVO: parciales por WebSocket mientras hablás)"
# WhisperLiveKit en :6092. Sin idle-exit propio: en modo ondemand queda apagado y la
# app lo arranca fire-and-forget al primer dictado; `marvin-stt mode always` lo deja
# resident junto al batch (VRAM: ~2GB batch + ~2GB live, entra en una placa de 6GB).
STT_LIVE_PY="$(cd "$(dirname "$0")" && pwd)/marvin-stt-live.py"
cat > "$HOME/.config/systemd/user/marvin-stt-live.service" <<EOF
[Unit]
Description=RemoteMarvin STT live daemon (dictado en vivo, 127.0.0.1:6092)

[Service]
ExecStart=$UV_BIN run --with whisperlivekit --with nvidia-cublas-cu12 --with nvidia-cudnn-cu12 $STT_LIVE_PY
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
echo "    instalado (se activa con: marvin-stt mode always, o al primer dictado)"
# Prewarm en background: fuerza la descarga del modelo (~1.6GB) AHORA y no en el primer
# dictado. Manda 1s de silencio; resultado en /tmp/marvin-stt-prewarm.log.
(
  python3 - <<'PY'
import wave
w = wave.open("/tmp/marvin-stt-prewarm.wav", "wb")
w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
w.writeframes(b"\x00\x00" * 16000)
w.close()
PY
  "$HOME/.local/bin/marvin-stt" < /tmp/marvin-stt-prewarm.wav >/dev/null \
    && echo "prewarm STT OK" || echo "prewarm STT falló (se reintenta al primer uso)"
) >/tmp/marvin-stt-prewarm.log 2>&1 &
echo "    prewarm del modelo en background (log: /tmp/marvin-stt-prewarm.log)"

cat <<'EOF'

============================================================
 Falta:
 1) Autorizar la clave de la app: pegá el texto del botón 🔑 de
    RemoteMarvin en  ~/.ssh/authorized_keys
      install -d -m700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys
 2) cp .env.example .env  y completar TS_AUTHKEY (+ OAuth para el QR)
 3) docker compose up -d --build
 Desde la app:  conectá a  <tu-usuario>@remoteclaude:22

 Tip: que la PC no se suspenda y mate todo:
   sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
============================================================
EOF
