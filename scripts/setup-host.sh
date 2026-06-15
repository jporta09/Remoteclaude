#!/usr/bin/env bash
# Prepara un host Linux (tu PC ahora, el server del amigo después) para trabajo
# remoto estable desde el celular:
#   - Tailscale : red que sobrevive cambios de IP / sueño / roaming, sin IP pública
#   - mosh+tmux : terminal que sobrevive al bloqueo del celular
#   - Docker    : para el entorno portable con navegador (docker-compose.yml)
#
# Uso:  bash scripts/setup-host.sh
set -euo pipefail

echo "==> Instalando Tailscale, mosh, tmux y Docker (host Linux)"

# --- Tailscale ---
if ! command -v tailscale >/dev/null 2>&1; then
    curl -fsSL https://tailscale.com/install.sh | sh
else
    echo "    tailscale ya instalado"
fi

# --- mosh + tmux + SSH (mosh necesita SSH para iniciar la sesión) ---
if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y mosh tmux openssh-server
elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y mosh tmux openssh-server
else
    echo "!! Gestor de paquetes no reconocido: instalá mosh, tmux y openssh-server a mano."
fi

# --- Docker ---
if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
    sudo usermod -aG docker "$USER" || true
    echo "    (cerrá y volvé a entrar a la sesión para usar docker sin sudo)"
else
    echo "    docker ya instalado"
fi

# --- Activar SSH (mosh lo usa para el handshake inicial) ---
sudo systemctl enable --now ssh 2>/dev/null || sudo systemctl enable --now sshd 2>/dev/null || true

cat <<'EOF'

============================================================
 Falta un paso manual: autenticar Tailscale
   sudo tailscale up
 (te da un link para loguearte; corré lo mismo en el celular
  instalando la app de Tailscale con la MISMA cuenta)

 Evitar que la PC se suspenda y mate todo (escritorio):
   sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target

 Ver el nombre/IP del host en tu tailnet:
   tailscale status
============================================================
EOF
