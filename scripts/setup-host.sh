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
