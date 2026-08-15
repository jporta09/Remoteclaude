#!/usr/bin/env bash
# Imprime el directorio donde vive el `go` de esta máquina, o falla con un mensaje claro.
#
# Existe por la misma razón que jdk17.sh: acá Go está instalado pero NO en el PATH, así que
# `command -v go` no lo encuentra y es fácil concluir que falta. Yo mismo lo concluí, y con
# esa premisa falsa dejé los chequeos de Go afuera de `make all` — o sea que "todo lo que no
# necesita dispositivo" no incluía gofmt, vet ni los tests con -race del bridge.
#
# Si ya hay un `go` en el PATH se respeta.
set -euo pipefail

if command -v go >/dev/null 2>&1; then
    dirname "$(command -v go)"; exit 0
fi

for c in "$HOME/toolchain/go/bin" /usr/local/go/bin "$HOME/go/bin"; do
    [ -x "$c/go" ] && { echo "$c"; exit 0; }
done

echo "!! no encontré el toolchain de Go (probá: https://go.dev/dl/)" >&2
exit 1
