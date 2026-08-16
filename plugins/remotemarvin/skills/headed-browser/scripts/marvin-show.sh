#!/usr/bin/env bash
# Manda una URL o un archivo HTML al daemon de render del HOST de la app (haviland), por el
# reverse tunnel en localhost:6090, para que lo muestre en el noVNC -> celu.
#
# Para cuando ESTE server no puede correr el navegador headed (faltan libs de sistema, sin
# sudo). Solo sirve para MOSTRAR un HTML / URL pública (el render pasa en el host de la app,
# que no ve la red interna de este server).
#
#   marvin-show https://example.com
#   marvin-show ./informe.html
set -euo pipefail

# Token opcional del render-daemon (lo genera setup-host). Si no existe, no se manda.
TOKENF="$HOME/.config/marvin/render-token"
AUTH=()
[ -f "$TOKENF" ] && AUTH=(-H "X-Marvin-Token: $(cat "$TOKENF")")
PORT="${MARVIN_RENDER_PORT:-6090}"
BASE="http://127.0.0.1:${PORT}"

[ "$#" -ge 1 ] || { echo "uso: marvin-show <url-o-archivo.html>" >&2; exit 2; }
arg="$1"

# ¿el daemon está alcanzable por el túnel?
if ! curl -fsS -o /dev/null "${BASE}/" 2>/dev/null; then
    echo "✗ No llego al render del host de la app (${BASE})." >&2
    echo "  Asegurate de haber SSH-eado DESDE la app (tiende el túnel) y que el daemon" >&2
    echo "  esté corriendo allá:  ./scripts/marvin-render.py" >&2
    exit 1
fi

if printf '%s' "$arg" | grep -qiE '^https?://'; then
        curl -fsS "${AUTH[@]}" -G --data-urlencode "url=$arg" "${BASE}/open"
elif [ -f "$arg" ]; then
    # El daemon exige nombres [A-Za-z0-9][A-Za-z0-9._-]* (whitelist estricta, a propósito).
    # Sin normalizar, "informe final.html" moría acá con "curl: (3) bad URL" —el espacio
    # hace ilegal la URL— y aunque llegara, el server lo rechazaría con 400. Espacios a
    # guión bajo y se descarta el resto de lo prohibido; si no sobrevive nada usable, se
    # avisa con el nombre original para que el error se entienda.
    nombre="$(basename -- "$arg" | tr ' ' '_' | tr -cd 'A-Za-z0-9._-')"
    case "$nombre" in
        [A-Za-z0-9]*) ;;
        *) echo "✗ el nombre no se puede normalizar al formato del daemon: $(basename -- "$arg")" >&2; exit 2 ;;
    esac
    curl -fsS "${AUTH[@]}" -T "$arg" "${BASE}/file/${nombre}"
else
    echo "✗ no es URL ni archivo: $arg" >&2; exit 2
fi
echo "→ Abrilo en el celu: 🖥 (noVNC) de la app."
