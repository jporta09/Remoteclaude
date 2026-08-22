#!/usr/bin/env bash
# PreToolUse hook (matcher: ExitPlanMode|AskUserQuestion): Claude quedó esperando que apruebes un
# PLAN o respondas una PREGUNTA. El hook Notification/permission_prompt NO cubre estos casos (ningún
# notification_type los emite; verificado contra la doc de Claude Code), pero PreToolUse dispara ante
# cualquier tool, así que es la señal correcta e inmediata.
#
# Appendea a ~/.config/marvin/notify.jsonl una línea con type "permission_prompt" —el MISMO que la app
# RemoteMarvin ya reacciona (NotificacionesRemotas)— para que postee "Claude te espera" sin ningún
# cambio de app. Corre local (token-free), NUNCA bloquea ni falla (siempre exit 0) y NO emite decisión
# de permiso (sólo notifica).
#
# Contrato PreToolUse: stdin = JSON con tool_name / tool_input (distinto del hook Notification, que
# trae notification_type / message).
set -uo pipefail

input=$(cat 2>/dev/null || true)

dir="${XDG_CONFIG_HOME:-$HOME/.config}/marvin"
file="$dir/notify.jsonl"
mkdir -p "$dir" 2>/dev/null || true

ts=$(date +%s 2>/dev/null || echo 0)

# Debounce: PreToolUse no tiene el proxy ~6 s del hook Notification, así que planes/preguntas seguidos
# generarían un buzz cada uno. Si avisamos hace < 6 s, no re-notificamos.
stamp="$dir/.decision.stamp"
prev=$(cat "$stamp" 2>/dev/null || echo 0)
if [ "$((ts - prev))" -lt 6 ] 2>/dev/null; then exit 0; fi
echo "$ts" >"$stamp" 2>/dev/null || true

sesion=$(tmux display-message -p '#S' 2>/dev/null || true)

tool=""
if command -v jq >/dev/null 2>&1; then
  tool=$(jq -r '.tool_name // empty' <<<"$input" 2>/dev/null || true)
fi

case "$tool" in
  ExitPlanMode)    msg="Claude te espera para aprobar un plan" ;;
  AskUserQuestion) msg="Claude te hizo una pregunta" ;;
  *)               msg="Claude está esperando una decisión" ;;
esac

if command -v jq >/dev/null 2>&1; then
  # jq arma JSON válido; el mensaje es fijo (sin comillas/control), pero mantenemos el mismo patrón.
  line=$(jq -cn --arg m "$msg" --arg s "$sesion" --argjson ts "$ts" \
    '{type:"permission_prompt",message:$m,session:$s,ts:$ts}' 2>/dev/null || true)
fi
# Sin jq (o si falló): línea fija. $msg es un literal controlado (sin comillas ni control chars).
[ -z "${line:-}" ] && line="{\"type\":\"permission_prompt\",\"message\":\"$msg\",\"ts\":$ts}"

# Append + rotación serializados con flock + tmp único (igual que marvin-notify.sh): evita que los dos
# hooks roten a la vez y se pierdan líneas. Degrada a append sin lock si no hay flock.
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
