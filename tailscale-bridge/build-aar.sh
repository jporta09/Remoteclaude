#!/usr/bin/env bash
# Reconstruye android/app/libs/marvints.aar (el nodo Tailscale embebido) con gomobile.
#
#   ./build-aar.sh              # arm64 + x86_64 (default: el x86_64 es para el emulador)
#   ./build-aar.sh --arm-only   # sólo arm64 (AAR la mitad de grande)
#
# Descubre la toolchain en vez de asumir rutas: antes esto estaba documentado con rutas
# absolutas de una máquina puntual en el README, así que nadie más podía reconstruirlo.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../android/app/libs/marvints.aar"
TARGETS="android/arm64,android/amd64"
[ "${1:-}" = "--arm-only" ] && TARGETS="android/arm64"

need() { command -v "$1" >/dev/null 2>&1; }

# --- Go -----------------------------------------------------------------------------
# La búsqueda vive en scripts/go-bin.sh porque el Makefile la necesita igual.
if ! need go; then
    PATH="$("$HERE/../scripts/go-bin.sh")":"$PATH" || {
        echo "falta Go (go.mod pide $(grep -m1 '^go ' "$HERE/go.mod" | awk '{print $2}'))" >&2
        exit 1
    }
fi

# --- gomobile ------------------------------------------------------------------------
PATH="$(go env GOPATH)/bin:$PATH"
if ! need gomobile; then
    echo "==> instalando gomobile"
    go install golang.org/x/mobile/cmd/gomobile@latest
    need gomobile || { echo "no pude instalar gomobile" >&2; exit 1; }
fi

# --- NDK ------------------------------------------------------------------------------
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    for c in "$HOME"/.buildozer/android/platform/android-ndk-r* \
             "$HOME"/Android/Sdk/ndk/* "${ANDROID_HOME:-/nonexistent}"/ndk/* /opt/android-ndk*; do
        [ -d "$c" ] && [ -f "$c/source.properties" ] && { ANDROID_NDK_HOME="$c"; break; }
    done
fi
[ -n "${ANDROID_NDK_HOME:-}" ] || { echo "no encontré el NDK; exportá ANDROID_NDK_HOME" >&2; exit 1; }
export ANDROID_NDK_HOME

# --- SDK (gomobile lo exige aunque sólo compile el .so) -------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then
    for c in "$HOME/.buildozer/android/platform/android-sdk" "$HOME/Android/Sdk" /opt/android-sdk; do
        [ -d "$c/platform-tools" ] && { ANDROID_HOME="$c"; break; }
    done
fi
[ -n "${ANDROID_HOME:-}" ] || { echo "no encontré el SDK; exportá ANDROID_HOME" >&2; exit 1; }
export ANDROID_HOME

echo "==> go:   $(go version)"
echo "==> ndk:  $ANDROID_NDK_HOME"
echo "==> sdk:  $ANDROID_HOME"
echo "==> abis: $TARGETS"

cd "$HERE"
gofmt -l . | grep -q . && { echo "!! hay archivos sin formatear (gofmt -w .)" >&2; exit 1; }
go vet ./...
go test ./...

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
gomobile bind -target="$TARGETS" -androidapi 26 -o "$tmp/marvints.aar" .

mkdir -p "$(dirname "$OUT")"
mv "$tmp/marvints.aar" "$OUT"
echo "==> $OUT"
echo "    ABIs:     $(unzip -l "$OUT" | grep -oE 'jni/[^/]+' | sort -u | tr '\n' ' ')"
echo "    tamaño:   $(du -h "$OUT" | cut -f1)"
echo "    sha256:   $(sha256sum "$OUT" | cut -c1-16)…"
sha256sum "$OUT" | awk '{print $1}' > "$OUT.sha256"
echo "    (checksum en $(basename "$OUT").sha256, versionado: sirve para detectar drift)"

# Hash DETERMINÍSTICO del source Go que entra al AAR (los .go no-test + go.mod/go.sum). El
# .sha256 de arriba es del BINARIO, que gomobile no reconstruye byte-a-byte, así que no sirve
# para detectar "editaron el bridge y olvidaron reconstruir el AAR". Este sí: CI lo re-computa
# del source y lo compara, sin necesitar gomobile/NDK (DEVOPS-2).
srchash_go() { { for f in *.go; do case "$f" in *_test.go) ;; *) sha256sum "$f" ;; esac; done; sha256sum go.mod go.sum 2>/dev/null; } | sort | sha256sum | awk '{print $1}'; }
srchash_go > "$OUT.srchash"
echo "    (srchash del source Go en $(basename "$OUT").srchash — CI lo re-verifica)"
