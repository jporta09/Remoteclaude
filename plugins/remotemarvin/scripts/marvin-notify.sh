#!/usr/bin/env bash
# Notification hook (matcher: permission_prompt): Claude quedó esperando que apruebes/decidas una
# herramienta. Appendea una línea JSON a ~/.config/marvin/notify.jsonl; la app RemoteMarvin tiene un
# canal SSH persistente que `tail -F`ea ese archivo y postea una notificación "Claude te espera".
#
# Contrato Notification: stdin = JSON con notification_type / message. Corre local (token-free) y
# NUNCA falla ni bloquea a Claude (siempre sale 0). Es la señal PRECISA host-side que reemplaza al
# parseo de la pantalla (la vieja hoja de aprobación).
#
# SEGURIDAD: el `message` de Claude NO se propaga (era texto atacante-controlado si Claude está
# inyectado, renderizado con autoridad de la app en una notif HIGH). Emitimos un mensaje FIJO por
# `type`; el detalle real se ve al TOCAR (abre el terminal vivo). El `type` es la única señal útil.
set -uo pipefail

# Drenar stdin (el JSON del hook) sin usarlo: el mensaje es fijo por seguridad (no leemos `.message`).
cat >/dev/null 2>&1 || true

dir="${XDG_CONFIG_HOME:-$HOME/.config}/marvin"
file="$dir/notify.jsonl"
mkdir -p "$dir" 2>/dev/null || true

ts=$(date +%s 2>/dev/null || echo 0)
# Identidad de sesión: si el hook corre dentro de tmux, el nombre de la sesión desambigua "qué Claude"
# (dos sesiones esperando no colapsan en una notif ambigua). Best-effort.
sesion=$(tmux display-message -p '#S' 2>/dev/null || true)
# Mensaje FIJO (el matcher ya garantiza permission_prompt). No se lee `.message`.
msg="Claude está esperando una decisión"

if command -v jq >/dev/null 2>&1; then
  line=$(jq -cn --arg m "$msg" --arg s "$sesion" --argjson ts "$ts" \
    '{type:"permission_prompt",message:$m,session:$s,ts:$ts}' 2>/dev/null || true)
fi
# Sin jq (o si jq falló): línea fija y segura. $msg es un literal controlado; $sesion puede traer
# caracteres raros, así que sin jq lo omitimos para no romper el JSON.
[ -z "${line:-}" ] && line="{\"type\":\"permission_prompt\",\"message\":\"$msg\",\"ts\":$ts}"

# Append + rotación serializados con flock (evita que los dos hooks —Notification y PreToolUse/R1—
# roten a la vez sobre el mismo archivo y se pierdan líneas). Tmp ÚNICO por proceso (no `$file.tmp`
# compartido). Si no hay flock, degrada a append sin lock (sin romper).
lock="$dir/.notify.lock"
{
  flock 9 2>/dev/null || true
  printf '%s\n' "$line" >>"$file" 2>/dev/null || true
  n=$(wc -l <"$file" 2>/dev/null || echo 0)
  if [ "${n:-0}" -gt 200 ] 2>/dev/null; then
    tmp="$file.$$.tmp"
    tail -n 200 "$file" >"$tmp" 2>/dev/null && mv "$tmp" "$file" 2>/dev/null || rm -f "$tmp" 2>/dev/null || true
  fi
} 9>"$lock"

exit 0
