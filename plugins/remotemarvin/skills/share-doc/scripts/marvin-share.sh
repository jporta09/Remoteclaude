#!/usr/bin/env bash
# Comparte archivos con la app RemoteMarvin: los copia a ~/RemoteMarvinDocs/ (en la PC),
# de donde la app los lista y muestra en su visor de Documentos (botón 📄 en la terminal).
# Pensado para que Claude lo corra al generar imágenes / PDF / txt / csv / etc.
#
#   scripts/marvin-share.sh informe.pdf grafico.png datos.csv
#
# El directorio se puede cambiar con REMOTEMARVIN_DOCS.
set -euo pipefail

DEST="${REMOTEMARVIN_DOCS:-$HOME/RemoteMarvinDocs}"
mkdir -p "$DEST"

[ "$#" -ge 1 ] || { echo "uso: scripts/marvin-share.sh <archivo> [archivo...]" >&2; exit 2; }

n=0
for f in "$@"; do
    if [ -f "$f" ]; then
        cp -f -- "$f" "$DEST/"
        echo "✓ $(basename -- "$f")"
        n=$((n + 1))
    else
        echo "✗ no existe: $f" >&2
    fi
done
echo "→ $n archivo(s) en $DEST · abrí 📄 Documentos en RemoteMarvin"
