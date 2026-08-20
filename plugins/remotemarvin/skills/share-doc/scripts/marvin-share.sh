#!/usr/bin/env bash
# Comparte archivos con la app RemoteMarvin: aparecen en su visor de Documentos (📄).
# Pensado para que Claude lo corra al generar imágenes / PDF / txt / csv / etc.
#
#   scripts/marvin-share.sh informe.pdf grafico.png datos.csv
#
# Dónde aterrizan los archivos (auto):
#   - En el HOST de la app           -> ~/RemoteMarvinDocs (copia local).
#   - En un SERVER SSH-eado DESDE la app -> se suben por el reverse tunnel (:6090) al
#     render-daemon del HOST, que los deja en SU ~/RemoteMarvinDocs (el que lee el celu).
#     Así no quedan varados en el server, que la app no mira.
# En ambos casos terminan en el ~/RemoteMarvinDocs del host. Sin daemon alcanzable, copia
# local (best-effort). REMOTEMARVIN_DOCS cambia el directorio local.
set -euo pipefail

DEST="${REMOTEMARVIN_DOCS:-$HOME/RemoteMarvinDocs}"
TOKENF="$HOME/.config/marvin/render-token"
AUTH=()
[ -f "$TOKENF" ] && AUTH=(-H "X-Marvin-Token: $(cat "$TOKENF")")
SINK="http://127.0.0.1:${MARVIN_RENDER_PORT:-6090}"

[ "$#" -ge 1 ] || { echo "uso: scripts/marvin-share.sh <archivo> [archivo...]" >&2; exit 2; }

# Nombre de destino seguro para el visor de la app: neutraliza lo que su parser considera
# inseguro (nombreDeDocInseguro: control chars, comilla simple, barra —ésta ya la saca
# basename—, o vacío) y preserva lo legítimo (espacios, acentos, emoji, puntuación). Defensa
# en profundidad del lado del PRODUCTOR, igual que safe_name de marvin-render: no creamos en
# ~/RemoteMarvinDocs nombres que después corran los campos del listado o spoofeen el label de
# procedencia. Saneamos (no rechazamos) para que compartir siga funcionando.
safe_name() {
    local out
    out="$(basename -- "$1")"
    out="$(printf '%s' "$out" | LC_ALL=C tr "\001-\037\177'" '_')"
    case "$out" in
        "" | "." | "..") out="documento" ;;
    esac
    printf '%s' "$out"
}

# ¿Hay docs-sink alcanzable? (daemon local en el host, o el del host por el túnel reverso
# si estamos en un server SSH-eado desde la app). En los dos casos cae en el host.
use_sink=0
if curl -fsS --max-time 2 "$SINK/" 2>/dev/null | grep -q marvin-render; then
    use_sink=1
else
    mkdir -p "$DEST"
fi

n=0
for f in "$@"; do
    if [ ! -f "$f" ]; then echo "✗ no existe: $f" >&2; continue; fi
    name="$(safe_name "$f")"
    if [ "$use_sink" = 1 ]; then
        if curl -fsS "${AUTH[@]}" --max-time 60 -T "$f" -H "X-Filename: $name" "$SINK/doc" >/dev/null 2>&1; then
            echo "✓ $name (→ host)"
            n=$((n + 1))
        else
            echo "✗ falló subir al host: $name" >&2
        fi
    else
        cp -f -- "$f" "$DEST/$name" || { echo "✗ no pude copiar: $name" >&2; continue; }
        echo "✓ $name"
        n=$((n + 1))
    fi
done
echo "→ $n archivo(s) · abrí 📄 Documentos en RemoteMarvin"
