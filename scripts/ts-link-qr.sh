#!/usr/bin/env bash
# Mintea una auth key de Tailscale efímera (un solo uso, vence en 10 min, pre-autorizada)
# vía el OAuth client del .env y la muestra como QR en la terminal. Escaneás ese QR desde
# RemoteMarvin (🔒 → Vincular por QR) y el nodo embebido de la app entra a tu tailnet sin
# tocar la consola web ni la app de Tailscale.
#
# Uso (en la PC, desde el repo):  ./scripts/ts-link-qr.sh
#                                 ./scripts/ts-link-qr.sh --png          (QR como imagen)
#                                 ./scripts/ts-link-qr.sh --mostrar-key  (ver texto)
# Requiere curl, jq y qrencode instalados en el host.
#
# --png: genera el QR como imagen grande y la abre en el visor. El QR ANSI de la terminal
# puede NO engancharse con la cámara (contraste/tamaño de fuente/tema claro — pasó en la
# validación de F10); el PNG escanea al primer intento.
set -euo pipefail

MOSTRAR_KEY=0
PNG=0
case "${1:-}" in
  --mostrar-key) MOSTRAR_KEY=1 ;;
  --png) PNG=1 ;;
esac

# Tomar las credenciales OAuth del .env del repo si no vienen ya del entorno.
if [ -z "${TS_OAUTH_CLIENT_ID:-}" ] || [ -z "${TS_OAUTH_CLIENT_SECRET:-}" ]; then
    ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/.env"
    # la ruta del .env es del usuario, no una constante que shellcheck pueda seguir
    # shellcheck source=/dev/null
    [ -f "$ENV_FILE" ] && { set -a; . "$ENV_FILE"; set +a; }
fi

: "${TS_OAUTH_CLIENT_ID:?falta TS_OAUTH_CLIENT_ID en .env (ver .env.example)}"
: "${TS_OAUTH_CLIENT_SECRET:?falta TS_OAUTH_CLIENT_SECRET en .env (ver .env.example)}"
TAG="${TS_TAG:-tag:remotemarvin}"

# 1) Token de acceso (OAuth client_credentials).
# Los secretos van por --config (stdin) y no como argumentos: todo lo que se pasa por argv
# queda visible en /proc/<pid>/cmdline para CUALQUIER usuario de la maquina mientras dura
# el curl, y `ps` alcanza para leerlo.
token=$(curl -sf https://api.tailscale.com/api/v2/oauth/token --config - <<CFG | jq -r '.access_token // empty'
data = "client_id=${TS_OAUTH_CLIENT_ID}"
data = "client_secret=${TS_OAUTH_CLIENT_SECRET}"
CFG
)
[ -n "$token" ] || { echo "✗ No pude obtener el token OAuth (revisá client id/secret en .env)"; exit 1; }

# 2) Auth key fresca: un solo uso, no efímera (nodo estable), pre-autorizada, tag obligatorio.
BODY="{\"capabilities\":{\"devices\":{\"create\":{\"reusable\":false,\"ephemeral\":false,\"preauthorized\":true,\"tags\":[\"${TAG}\"]}}},\"expirySeconds\":600,\"description\":\"RemoteMarvin enroll\"}"
# El token tambien es un secreto: mismo tratamiento (el cuerpo JSON no lo es).
key=$(curl -sf "https://api.tailscale.com/api/v2/tailnet/-/keys" \
    -H "Content-Type: application/json" -d "$BODY" --config - <<CFG | jq -r '.key // empty'
header = "Authorization: Bearer ${token}"
CFG
)
[ -n "$key" ] || { echo "✗ No pude crear la auth key (¿el tag '${TAG}' está en tagOwners de la ACL?)"; exit 1; }

echo
echo "  ┌─ Vincular RemoteMarvin ────────────────────────────"
echo "  │  Abrí la app → 🔒 (línea de Tailscale) → Vincular por QR"
echo "  │  y escaneá esto. Válido 10 min, un solo uso."
echo "  └────────────────────────────────────────────────────"
echo
# -l L = menor corrección de error -> menos módulos -> QR más grande/legible para la cámara.
# -m 3 = quiet zone (margen): muchos lectores fallan sin ese borde blanco.
if [ "$PNG" = "1" ]; then
    # mktemp y no ruta fija (multiusuario); se borra solo a los 10 min, cuando la key vence.
    QR_PNG="$(mktemp -t marvin-qr.XXXXXX.png)"
    qrencode -t PNG -s 14 -l L -m 4 -o "$QR_PNG" "$key"
    ( sleep 600; rm -f "$QR_PNG" ) >/dev/null 2>&1 &
    echo "  QR en imagen: $QR_PNG (se borra solo en 10 min)"
    xdg-open "$QR_PNG" >/dev/null 2>&1 || echo "  (abrilo a mano si no saltó el visor)"
else
    qrencode -t ANSIUTF8 -l L -m 3 "$key"
    echo
    echo "  Tip: si al lector le cuesta, agrandá la fuente (Ctrl+'+') o usá:  $0 --png"
fi
# La key NO se imprime por defecto: queda en el scrollback de la terminal y, si esto se
# corre dentro de una sesion de la app, en lo que devuelve `tmux capture-pane` — que es
# justo lo que lee la app para el preview de sesiones detacheadas. El QR ya la lleva; que
# ademas quede en texto plano es regalarla.
if [ "$MOSTRAR_KEY" = "1" ]; then
    echo
    echo "  key (no la dejes en el scrollback): $key"
else
    echo "  Si no podés escanear:  ./scripts/ts-link-qr.sh --mostrar-key"
fi
echo
