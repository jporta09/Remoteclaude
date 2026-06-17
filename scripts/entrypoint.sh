#!/usr/bin/env bash
# Entrypoint del gateway: configura sshd para que el login salte al host.
set -euo pipefail

HOST_USER="${HOST_USER:-root}"
SSH_PORT="${SSH_PORT:-22}"
ENROLL_PASSWORD="${ENROLL_PASSWORD:-}"
# Claves enroladas en runtime por la app (auto-enrolamiento). Persisten en volumen,
# separadas de las claves "bootstrap" del repo (que el entrypoint regenera).
ENROLLED="/var/lib/remoteclaude/enrolled_keys"

# host-shell lee el usuario del host de acá (sshd puede limpiar el environment).
echo "${HOST_USER}" > /etc/remoteclaude/host_user

# tmux corre EN EL CONTENEDOR (que sí lo tiene) y cada panel hace nsenter al host
# via host-shell. Así la sesión persiste en el contenedor y sobrevive a cortes de
# SSH / bloqueo del celular, sin instalar tmux en el host.
cat > /root/.tmux.conf <<'EOF'
set -g default-command "/usr/local/bin/host-shell"
set -g default-terminal "xterm-256color"
set -g destroy-unattached off
# mouse on: el deslizamiento del dedo entra al historial de tmux (scroll).
set -g mouse on
# Las flechas SIEMPRE controlan la app (no el scroll): si estás en copy-mode,
# salen del scroll y mandan la flecha al panel (p.ej. menús de claude). El scroll
# queda solo para el dedo. (Se bindea en ambas tablas: emacs y vi.)
bind -T copy-mode    Up    send -X cancel \; send-keys Up
bind -T copy-mode    Down  send -X cancel \; send-keys Down
bind -T copy-mode    Left  send -X cancel \; send-keys Left
bind -T copy-mode    Right send -X cancel \; send-keys Right
bind -T copy-mode-vi Up    send -X cancel \; send-keys Up
bind -T copy-mode-vi Down  send -X cancel \; send-keys Down
bind -T copy-mode-vi Left  send -X cancel \; send-keys Left
bind -T copy-mode-vi Right send -X cancel \; send-keys Right
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

# Store persistente de claves enroladas en runtime (lo lee sshd además de las
# bootstrap; sin tocar /root/.ssh/authorized_keys, que se regenera del repo).
install -d -m 700 /var/lib/remoteclaude
touch "${ENROLLED}"; chmod 600 "${ENROLLED}"

# Auto-enrolamiento: usuario 'enroll' cuya CONTRASEÑA sólo permite AGREGAR una clave
# (ForceCommand), nunca abrir shell. Se habilita sólo si hay ENROLL_PASSWORD.
id enroll >/dev/null 2>&1 || useradd -m -s /bin/bash enroll
if [ -n "${ENROLL_PASSWORD}" ]; then
    echo "enroll:${ENROLL_PASSWORD}" | chpasswd
    printf 'enroll ALL=(root) NOPASSWD: /usr/local/bin/rc-enroll-key\n' > /etc/sudoers.d/rc-enroll
    chmod 440 /etc/sudoers.d/rc-enroll
    echo "[gateway] auto-enrolamiento ON (usuario 'enroll', sólo agrega claves)."
else
    passwd -l enroll >/dev/null 2>&1 || true   # sin password => deshabilitado
    echo "[gateway] auto-enrolamiento OFF (seteá ENROLL_PASSWORD para habilitarlo)."
fi

cat > /etc/ssh/sshd_config.d/remoteclaude.conf <<EOF
Port ${SSH_PORT}
PermitRootLogin prohibit-password
PasswordAuthentication no
PubkeyAuthentication yes
AuthorizedKeysFile /root/.ssh/authorized_keys ${ENROLLED}
HostKey /etc/ssh/keys/ssh_host_ed25519_key
AcceptEnv LANG LC_*

# 'enroll': sólo password, sin pubkey, sin TTY ni forwarding; ForceCommand fija
# que lo único que puede hacer es enrolar una clave.
Match User enroll
    PasswordAuthentication yes
    PubkeyAuthentication no
    AuthenticationMethods password
    ForceCommand /usr/local/bin/rc-enroll-forced
    PermitTTY no
    AllowTcpForwarding no
    X11Forwarding no
EOF

/usr/sbin/sshd
echo "[gateway] sshd en :${SSH_PORT}. Login SSH/mosh -> shell en el HOST como '${HOST_USER}'."
echo "[gateway] Desde el celular:  mosh root@<host-tailscale>"

exec "$@"
