# Expiry del nodo Tailscale del HOST — filtro jq CANÓNICO, compartido por marvin-doctor y por la app
# (SshTerminalSession.EXPIRY_HOST_JQ). test/host/test_expiry_filter_parity.py exige que las tres copias
# sean idénticas. Entrada: `tailscale status --json`. Salida (una línea):
#   SINSELF  el JSON no trae Self (contenedor sin nodo)      NORUN   BackendState != Running (NeedsLogin = vencido/revocado)
#   TAGGED   nodo con tag (key expiry deshabilitado)          NOEXP   KeyExpiry null (expiry deshabilitado)
#   <int>    días hasta el vencimiento; NEGATIVO = ya venció hace -N días
# El sub() recorta los nanosegundos: jq 1.7 rechaza "2026-12-12T10:00:00.123456789Z" en fromdateiso8601.
if .Self == null then "SINSELF" elif (.BackendState != null and .BackendState != "Running") then "NORUN" elif (.Self.Tags // [] | length) > 0 then "TAGGED" elif .Self.KeyExpiry == null then "NOEXP" else (((.Self.KeyExpiry | sub("\\.[0-9]+"; "") | fromdateiso8601) - now) / 86400 | floor | tostring) end
