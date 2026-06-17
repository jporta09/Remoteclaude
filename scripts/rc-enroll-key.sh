#!/usr/bin/env bash
# Agrega una clave pública SSH al store de claves ENROLADAS (persistente), si es
# válida y no está ya. Corre como root (vía sudo) desde el ForceCommand del usuario
# 'enroll'. La clave llega como $1 en texto plano: se trata como DATO, NUNCA se
# evalúa, y se valida el formato antes de guardarla.
set -euo pipefail

KEY="${1:-}"
STORE="/var/lib/remoteclaude/enrolled_keys"

# Debe parecer una clave SSH soportada (tipo + base64 + comentario opcional).
case "$KEY" in
    "ssh-ed25519 "* | "ecdsa-sha2-nistp256 "* | "ecdsa-sha2-nistp384 "* | \
    "ecdsa-sha2-nistp521 "* | "ssh-rsa "*) ;;
    *) echo "ERROR: clave invalida" >&2; exit 1 ;;
esac
# Una sola línea (defensa extra anti-inyección de líneas).
if [ "$(printf '%s' "$KEY" | wc -l)" != "0" ]; then
    echo "ERROR: la clave debe ser una sola linea" >&2; exit 1
fi

install -d -m 700 "$(dirname "$STORE")"
touch "$STORE"; chmod 600 "$STORE"

if grep -qxF "$KEY" "$STORE"; then
    echo "OK: ya estaba autorizada"
else
    printf '%s\n' "$KEY" >> "$STORE"
    echo "OK: dispositivo autorizado"
fi
