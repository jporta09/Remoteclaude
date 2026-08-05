#!/usr/bin/env bash
# ¿Esta copia del plugin remotemarvin está al día con el remoto?
# Compara SOLO la versión de plugin.json (ignora commits no relacionados del repo) contra
# origin de la branch actual. Fail-open: si no hay git/red/credenciales, no molesta (exit 0).
# Lo corre el hook SessionStart (hooks/hooks.json); su stdout entra al contexto de Claude.
#
# Para no ensuciar el contexto cada sesión, cuando está AL DÍA o no se pudo chequear queda
# SILENCIOSO bajo el hook; solo habla si hay que actualizar. Corriéndolo a mano (TTY) sí
# informa siempre el estado.
#
#   check-version.sh           # estado (a mano: siempre; como hook: solo si desactualizado)
set -euo pipefail

# ¿salida interactiva? (corrida a mano) -> informa siempre. Bajo el hook -> calla si todo ok.
[ -t 1 ] && TTY=1 || TTY=0
say_ok()   { [ "$TTY" = 1 ] && echo "$1" || true; }

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel 2>/dev/null || true)"
[ -n "$REPO" ] || { say_ok "remotemarvin: ok (no es un git clone, nada que chequear)"; exit 0; }

PJ="plugins/remotemarvin/.claude-plugin/plugin.json"
ver() { grep -o '"version"[^,]*' | grep -o '[0-9][0-9.]*' | head -1; }
local_v="$(ver < "$REPO/$PJ" 2>/dev/null || true)"
# Sin versión local no hay nada que comparar (p.ej. el plugin vive dentro de otro repo):
# antes esto avisaba "instalado v, disponible vX" en CADA sesión, para siempre.
[ -n "$local_v" ] || { say_ok "remotemarvin: ok (sin plugin.json acá)"; exit 0; }
BR="$(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
[ -n "$BR" ] && [ "$BR" != "HEAD" ] || { say_ok "remotemarvin: ok (HEAD detached, no comparo)"; exit 0; }

# fetch acotado y con timeout; si falla (sin red/credenciales), no molestamos.
# Sin prompts interactivos: bajo el hook no hay TTY, así que si la key pide passphrase o
# faltan credenciales, que falle YA en vez de colgarse (igual lo cubre el timeout).
GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND="ssh -o BatchMode=yes -o ConnectTimeout=5" \
    timeout 8 git -C "$REPO" fetch --quiet origin "$BR" 2>/dev/null || {
    say_ok "remotemarvin: no pude chequear actualizaciones (sin red/credenciales)"; exit 0; }

remote_v="$(git -C "$REPO" show "origin/$BR:$PJ" 2>/dev/null | ver || true)"
[ -n "$remote_v" ] || { say_ok "remotemarvin: ok"; exit 0; }

# sort -V: comparar como strings daba "1.10 < 1.9" y avisaba al revés. Sólo se avisa si
# el remoto es ESTRICTAMENTE mayor (tener local adelantado es normal en desarrollo).
mayor="$(printf '%s\n%s\n' "$local_v" "$remote_v" | sort -V | tail -1)"
if [ "$local_v" = "$remote_v" ] || [ "$mayor" = "$local_v" ]; then
    say_ok "remotemarvin: al día (v$local_v)"
else
    echo "⚠️  El plugin remotemarvin está DESACTUALIZADO: instalado v$local_v, disponible v$remote_v en origin/$BR."
    echo "    Avisale al usuario y sugerile actualizar:  git -C '$REPO' pull"
    echo "    y luego en Claude:  /plugin marketplace update remotemarvin  →  /reload-plugins"
fi
