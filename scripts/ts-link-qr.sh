#!/usr/bin/env bash
# Mintea una auth key de Tailscale efímera (un solo uso, vence en 10 min, pre-autorizada)
# vía el OAuth client del .env y la muestra como QR en la terminal. Escaneás ese QR desde
# RemoteMarvin (🔒 → Vincular por QR) y el nodo embebido de la app entra a tu tailnet sin
# tocar la consola web ni la app de Tailscale.
#
# Uso (en la PC, desde el repo):  ./scripts/ts-link-qr.sh
# Requiere curl, jq y qrencode instalados en el host.
set -euo pipefail

# Tomar las credenciales OAuth del .env del repo si no vienen ya del entorno.
if [ -z "${TS_OAUTH_CLIENT_ID:-}" ] || [ -z "${TS_OAUTH_CLIENT_SECRET:-}" ]; then
    ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/.env"
    [ -f "$ENV_FILE" ] && { set -a; . "$ENV_FILE"; set +a; }
fi

: "${TS_OAUTH_CLIENT_ID:?falta TS_OAUTH_CLIENT_ID en .env (ver .env.example)}"
: "${TS_OAUTH_CLIENT_SECRET:?falta TS_OAUTH_CLIENT_SECRET en .env (ver .env.example)}"
TAG="${TS_TAG:-tag:remotemarvin}"

# 1) Token de acceso (OAuth client_credentials).
token=$(curl -sf https://api.tailscale.com/api/v2/oauth/token \
    -d "client_id=${TS_OAUTH_CLIENT_ID}" \
    -d "client_secret=${TS_OAUTH_CLIENT_SECRET}" \
    | jq -r '.access_token // empty')
[ -n "$token" ] || { echo "✗ No pude obtener el token OAuth (revisá client id/secret en .env)"; exit 1; }

# 2) Auth key fresca: un solo uso, no efímera (nodo estable), pre-autorizada, tag obligatorio.
key=$(curl -sf -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \
    "https://api.tailscale.com/api/v2/tailnet/-/keys" \
    -d "{\"capabilities\":{\"devices\":{\"create\":{\"reusable\":false,\"ephemeral\":false,\"preauthorized\":true,\"tags\":[\"${TAG}\"]}}},\"expirySeconds\":600,\"description\":\"RemoteMarvin enroll\"}" \
    | jq -r '.key // empty')
[ -n "$key" ] || { echo "✗ No pude crear la auth key (¿el tag '${TAG}' está en tagOwners de la ACL?)"; exit 1; }

echo
echo "  ┌─ Vincular RemoteMarvin ────────────────────────────"
echo "  │  Abrí la app → 🔒 (línea de Tailscale) → Vincular por QR"
echo "  │  y escaneá esto. Válido 10 min, un solo uso."
echo "  └────────────────────────────────────────────────────"
echo
# -l L = menor corrección de error -> menos módulos -> QR más grande/legible para la cámara.
# -m 3 = quiet zone (margen): muchos lectores fallan sin ese borde blanco.
qrencode -t ANSIUTF8 -l L -m 3 "$key"
echo
echo "  Tip: si al lector le cuesta, agrandá la fuente de la terminal (Ctrl+'+')."
echo "  Si no podés escanear, pegá esta key a mano:"
echo "  $key"
echo
