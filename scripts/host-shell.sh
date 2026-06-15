#!/usr/bin/env bash
# Shell de login del gateway. Es la pieza central del diseño: salta del
# contenedor al HOST entrando a los namespaces de PID 1 (systemd) y abre una
# sesión como el usuario del host. Desde acá TODO se ejecuta en el host —
# filesystem, PATH, proyectos, uv/node/claude, ~/.claude (conversaciones)— nativo.
#
# Dos modos de invocación:
#   - sshd/mosh ejecutando un comando puntual:  host-shell -c "<cmd>"
#     (p.ej. mosh-server). Eso corre EN EL CONTENEDOR, porque mosh-server tiene
#     que vivir acá; luego mosh-server vuelve a spawnear esta shell sin -c para la
#     sesión, y ahí sí salta al host.
#   - login interactivo (sin -c):  salta al host.
set -euo pipefail

if [ "${1:-}" = "-c" ]; then
    exec /bin/bash "$@"
fi

HOST_USER="$(cat /etc/remoteclaude/host_user 2>/dev/null || echo root)"
exec nsenter --target 1 --mount --uts --ipc --pid -- su - "${HOST_USER}"
