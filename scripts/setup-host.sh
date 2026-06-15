#!/usr/bin/env bash
# Con el enfoque containerizado, el host casi no necesita nada: solo Docker
# y el device /dev/net/tun (para que el contenedor de Tailscale arme la VPN).
# Todo lo demás (Tailscale, sshd, mosh, tmux, navegador) vive en contenedores.
#
# Uso:  bash scripts/setup-host.sh
set -euo pipefail

echo "==> Preparando host (solo Docker + /dev/net/tun)"

if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
    sudo usermod -aG docker "$USER" || true
    echo "    (cerrá y volvé a entrar a la sesión para usar docker sin sudo)"
else
    echo "    docker ya instalado"
fi

# /dev/net/tun: presente en casi todo kernel Linux; lo cargamos por las dudas.
if [ ! -e /dev/net/tun ]; then
    sudo modprobe tun || true
fi

cat <<'EOF'

============================================================
 Falta configurar 2 cosas antes de levantar:

 1) Auth key de Tailscale:
      cp .env.example .env   y pegá tu TS_AUTHKEY
      (Admin console -> Settings -> Keys -> Generate auth key, "Reusable")

 2) Tu clave pública SSH (para entrar con mosh):
      cp ssh/authorized_keys.example ssh/authorized_keys
      y pegá la pública de tu celular

 Después:
      docker compose up -d --build

 Tip: que la PC no se suspenda y mate todo (escritorio):
      sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
============================================================
EOF
