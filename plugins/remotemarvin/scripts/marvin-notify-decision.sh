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
  line=$(jq -cn --arg m "$msg" --argjson ts "$ts" '{type:"permission_prompt",message:$m,ts:$ts}' 2>/dev/null || true)
fi
# Sin jq (o si falló): línea fija. $msg es un literal controlado (sin comillas ni control chars).
[ -z "${line:-}" ] && line="{\"type\":\"permission_prompt\",\"message\":\"$msg\",\"ts\":$ts}"

printf '%s\n' "$line" >>"$file" 2>/dev/null || true

# Tope: quedarse con las últimas ~200 líneas (igual que marvin-notify.sh). `tail -F` de la app reabre
# tras el reemplazo de inode.
n=$(wc -l <"$file" 2>/dev/null || echo 0)
if [ "${n:-0}" -gt 200 ] 2>/dev/null; then
  tail -n 200 "$file" >"$file.tmp" 2>/dev/null && mv "$file.tmp" "$file" 2>/dev/null || true
fi

exit 0
