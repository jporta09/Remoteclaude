#!/usr/bin/env bash
# ForceCommand del usuario 'enroll'. Su contraseña SOLO sirve para enrolar una clave
# (agregarla a authorized_keys), nunca para abrir una shell ni correr comandos: este
# wrapper ignora lo que pida el cliente salvo la clave, que viaja en
# $SSH_ORIGINAL_COMMAND y se pasa, ya como dato, al script privilegiado.
set -euo pipefail
exec sudo -n /usr/local/bin/rc-enroll-key "${SSH_ORIGINAL_COMMAND:-}"
