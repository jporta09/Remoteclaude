#!/usr/bin/env bash
# Corre cualquier comando con el navegador VISIBLE en el MONITOR FÍSICO del que está
# sentado en la PC (la sesión X local), no en la pantalla virtual de noVNC. Usalo cuando
# el usuario está local y quiere ver/usar la ventana de forma nativa.
#
#   run-local.sh uv run python scraper.py
#   run-local.sh npx playwright test --headed
#
# Detecta el display X activo (o usá LOCAL_DISPLAY=:1 para forzarlo).
set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "uso: run-local.sh <comando...>" >&2
    exit 2
fi

# Display local: LOCAL_DISPLAY -> sesión activa (loginctl) -> who -> :0.
disp="${LOCAL_DISPLAY:-}"
if [ -z "$disp" ]; then
    disp=$(who 2>/dev/null | grep -oE '\(:[0-9]+(\.[0-9]+)?\)' | tr -d '()' | head -1)
fi
[ -z "$disp" ] && disp=":0"

# Verificar que el socket X exista.
sock="/tmp/.X11-unix/X${disp#:}"; sock="${sock%%.*}"
if [ ! -e "$sock" ]; then
    echo "!! No encuentro una sesión gráfica local en $disp (socket $sock)." >&2
    echo "   ¿Estás local en la PC con sesión iniciada? Si no, usá run-visible.sh (noVNC)." >&2
    exit 1
fi

# XAUTHORITY del usuario, si hace falta para autenticar contra su X server.
if [ -z "${XAUTHORITY:-}" ] && [ -f "$HOME/.Xauthority" ]; then
    export XAUTHORITY="$HOME/.Xauthority"
fi

echo ">> Navegador en tu monitor local (DISPLAY=$disp)"
exec env DISPLAY="$disp" "$@"
