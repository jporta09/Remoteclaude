#!/usr/bin/env bash
# Entrypoint del gateway: configura sshd para que el login salte al host.
set -euo pipefail

HOST_USER="${HOST_USER:-root}"
SSH_PORT="${SSH_PORT:-22}"

# host-shell lee el usuario del host de acá (sshd puede limpiar el environment).
echo "${HOST_USER}" > /etc/remoteclaude/host_user

# tmux corre EN EL CONTENEDOR (que sí lo tiene) y cada panel hace nsenter al host
# via host-shell. Así la sesión persiste en el contenedor y sobrevive a cortes de
# SSH / bloqueo del celular, sin instalar tmux en el host.
cat > /root/.tmux.conf <<'EOF'
set -g default-command "/usr/local/bin/host-shell"
set -g default-terminal "xterm-256color"
set -g destroy-unattached off
EOF

# Clave pública del cliente (montada read-only) -> login del contenedor (root).
AUTH_SRC="/etc/remoteclaude/authorized_keys"
install -d -m 700 /root/.ssh
if [ -s "${AUTH_SRC}" ]; then
    install -m 600 "${AUTH_SRC}" /root/.ssh/authorized_keys
else
    echo "[gateway] !! sin ${AUTH_SRC}: nadie podrá entrar. Montá tu clave pública."
fi

# Host keys SSH persistentes (volumen) -> la huella no cambia entre rebuilds.
install -d -m 700 /etc/ssh/keys
[ -f /etc/ssh/keys/ssh_host_ed25519_key ] || ssh-keygen -q -t ed25519 -N "" -f /etc/ssh/keys/ssh_host_ed25519_key

# El usuario de login del contenedor es root, pero su shell salta al host.
usermod -s /usr/local/bin/host-shell root

cat > /etc/ssh/sshd_config.d/remoteclaude.conf <<EOF
Port ${SSH_PORT}
PermitRootLogin prohibit-password
PasswordAuthentication no
PubkeyAuthentication yes
HostKey /etc/ssh/keys/ssh_host_ed25519_key
AcceptEnv LANG LC_*
EOF

/usr/sbin/sshd
echo "[gateway] sshd en :${SSH_PORT}. Login SSH/mosh -> shell en el HOST como '${HOST_USER}'."
echo "[gateway] Desde el celular:  mosh root@<host-tailscale>"

exec "$@"
