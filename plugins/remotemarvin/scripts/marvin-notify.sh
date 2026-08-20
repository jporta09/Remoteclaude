#!/usr/bin/env bash
# Notification hook (matcher: permission_prompt): Claude quedó esperando que apruebes/decidas una
# herramienta. Appendea una línea JSON a ~/.config/marvin/notify.jsonl; la app RemoteMarvin tiene un
# canal SSH persistente que `tail -F`ea ese archivo y postea una notificación "Claude te espera".
#
# Contrato Notification: stdin = JSON con notification_type / message. Corre local (token-free) y
# NUNCA falla ni bloquea a Claude (siempre sale 0). Es la señal PRECISA host-side que reemplaza al
# parseo de la pantalla (la vieja hoja de aprobación).
set -uo pipefail

input=$(cat 2>/dev/null || true)

dir="${XDG_CONFIG_HOME:-$HOME/.config}/marvin"
file="$dir/notify.jsonl"
mkdir -p "$dir" 2>/dev/null || true

ts=$(date +%s 2>/dev/null || echo 0)
if command -v jq >/dev/null 2>&1; then
  # jq arma JSON válido (escapa el mensaje, que puede traer comillas/rutas).
  t=$(jq -r '.notification_type // "permission_prompt"' <<<"$input" 2>/dev/null || echo permission_prompt)
  m=$(jq -r '.message // empty' <<<"$input" 2>/dev/null || true)
  [ -z "$m" ] && m="Claude está esperando una decisión"
  line=$(jq -cn --arg t "$t" --arg m "$m" --argjson ts "$ts" '{type:$t,message:$m,ts:$ts}' 2>/dev/null || true)
fi
# Sin jq (o si jq falló): línea fija y segura (el matcher ya garantiza que es permission_prompt).
[ -z "${line:-}" ] && line="{\"type\":\"permission_prompt\",\"message\":\"Claude está esperando una decisión\",\"ts\":$ts}"

printf '%s\n' "$line" >>"$file" 2>/dev/null || true

# Tope: quedarse con las últimas ~200 líneas. `tail -F` de la app reabre tras el reemplazo de inode.
n=$(wc -l <"$file" 2>/dev/null || echo 0)
if [ "${n:-0}" -gt 200 ] 2>/dev/null; then
  tail -n 200 "$file" >"$file.tmp" 2>/dev/null && mv "$file.tmp" "$file" 2>/dev/null || true
fi

exit 0
