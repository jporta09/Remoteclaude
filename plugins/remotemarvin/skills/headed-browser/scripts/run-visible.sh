#!/usr/bin/env bash
# Corre cualquier comando con el navegador VISIBLE en el noVNC que mira el celular.
# Auto-detecta DÓNDE está el display:
#   - LOCAL : contenedor 'display' en esta máquina (noVNC en :6080).
#   - REMOTO: estás en un server al que SSH-easte DESDE la app -> el display del host de
#             la app llega por un reverse tunnel a localhost:6099 (lo tiende el ~/.ssh/config
#             cuando MARVIN_DISPLAY está seteada). El browser dibuja ahí y se ve en el noVNC
#             del host de la app.
# En ambos casos el browser usa DISPLAY=localhost:99 (X por TCP en 6099).
#
#   run-visible.sh uv run python scraper.py
#   run-visible.sh npx playwright test --headed
#
# Variables (con defaults):
#   REMOTE_DISPLAY  número de display X        (99 -> TCP 6099)
#   NOVNC_PORT      puerto del visor noVNC      (6080)
#   REMOTE_HOST     host de la tailnet a mirar  (remoteclaude)
set -euo pipefail

DISPLAY_NUM="${REMOTE_DISPLAY:-99}"
XPORT=$((6000 + DISPLAY_NUM))
NOVNC_PORT="${NOVNC_PORT:-6080}"
REMOTE_HOST="${REMOTE_HOST:-remoteclaude}"

[ "$#" -ge 1 ] || { echo "uso: run-visible.sh <comando...>" >&2; exit 2; }

# ¿hay un X alcanzable en localhost:XPORT? (contenedor local o túnel reverso)
xreachable() { (exec 3<>"/dev/tcp/127.0.0.1/${XPORT}") 2>/dev/null && exec 3>&- 2>/dev/null; }

if curl -fsS -o /dev/null "http://localhost:${NOVNC_PORT}/vnc.html" 2>/dev/null; then
    echo ">> Display LOCAL (contenedor 'display' en esta máquina)."
elif xreachable; then
    echo ">> Display REMOTO (túnel reverso al noVNC de ${REMOTE_HOST}). El X viaja por SSH:"
    echo "   puede ir con algo de lag para UI pesada (si molesta, usá CDP)."
else
    cat >&2 <<EOF
!! No hay display alcanzable en localhost:${XPORT}.
   - En el host de la app:   docker compose up -d display
   - En un server por SSH:   conectate DESDE la app (el display se tunela solo).
   - Sin display (más fluido): CDP -> chromium --remote-debugging-port=9222 + ssh -L 9222.
EOF
    exit 1
fi

# ¿Chromium tiene sus librerías de sistema? El modo HEADED las necesita; se instalan con
# 'sudo playwright install-deps chromium' (una vez, root). Sin ellas Chromium no abre, así
# que avisamos claro y salimos con código 3 (la skill cae al fallback "render en haviland").
chrome="$(ls "$HOME"/.cache/ms-playwright/chromium*/chrome-linux*/chrome 2>/dev/null | head -1)"
if [ -n "$chrome" ]; then
    missing="$(ldd "$chrome" 2>/dev/null | awk '/not found/{print $1}' | sort -u | tr '\n' ' ')"
    if [ -n "$missing" ]; then
        cat >&2 <<EOF
!! Chromium no puede correr acá: faltan librerías de sistema:
   $missing
   Se instalan UNA vez con:  sudo playwright install-deps chromium   (necesita root).
   Sin sudo no se puede correr el navegador HEADED en esta máquina.
   -> Fallback (solo mostrar un HTML / URL pública): renderizalo en el HOST de la app,
      que sí tiene libs + display, con  marvin-show <url-o-archivo.html>
EOF
        exit 3
    fi
fi

echo ">> Mirá desde el celu: http://${REMOTE_HOST}:${NOVNC_PORT}/vnc.html?autoconnect=1&resize=remote"
echo ">> (DISPLAY=localhost:${DISPLAY_NUM})"
exec env DISPLAY="localhost:${DISPLAY_NUM}" "$@"
