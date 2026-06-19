#!/usr/bin/env bash
# Corre cualquier comando con el navegador VISIBLE en la pantalla virtual compartida
# (Remoteclaude). Setea DISPLAY al display :99, verifica que el contenedor responda y
# anuncia la URL de noVNC para mirar desde el celular.
#
#   run-visible.sh uv run python scraper.py
#   run-visible.sh npx playwright test --headed
#
# Variables (con defaults):
#   REMOTE_DISPLAY  número de display X         (99)
#   NOVNC_PORT      puerto del visor noVNC       (6080)
#   REMOTE_HOST     host de Tailscale a mostrar  (remoteclaude)
set -euo pipefail

DISPLAY_NUM="${REMOTE_DISPLAY:-99}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
REMOTE_HOST="${REMOTE_HOST:-remoteclaude}"

if [ "$#" -eq 0 ]; then
    echo "uso: run-visible.sh <comando...>" >&2
    exit 2
fi

# ¿está la pantalla virtual arriba?
if ! curl -fsS -o /dev/null "http://localhost:${NOVNC_PORT}/vnc.html" 2>/dev/null; then
    echo "!! La pantalla virtual no responde en localhost:${NOVNC_PORT}." >&2
    echo "   Levantá el contenedor 'display' desde el repo Remoteclaude:" >&2
    echo "     docker compose up -d display" >&2
    exit 1
fi

echo ">> Navegador visible en: http://${REMOTE_HOST}:${NOVNC_PORT}/vnc.html?autoconnect=1&resize=remote"
echo ">> (DISPLAY=localhost:${DISPLAY_NUM})"
exec env DISPLAY="localhost:${DISPLAY_NUM}" "$@"
