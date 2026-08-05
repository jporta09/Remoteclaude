#!/usr/bin/env bash
# Corre la suite E2E instrumentada contra el fixture desechable (test/e2e).
#
#   scripts/e2e.sh            # AVD liviano (default; ver test/e2e/README.md)
#   scripts/e2e.sh --device   # dispositivo ya conectado por adb (no repetible)
#
# El fixture escucha SÓLO en 127.0.0.1. Con --device se usa `adb reverse`, así que no
# queda expuesto a ninguna red.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE="$REPO/test/e2e/docker-compose.e2e.yml"
ADB="${ADB:-adb}"
AVD="${AVD:-marvin-e2e}"
MODE="${1:-}"
KEEP="${E2E_KEEP:-0}"

cleanup() {
    [ "$KEEP" = "1" ] && { echo "== fixture y emulador quedan arriba (E2E_KEEP=1)"; return; }
    echo "== bajando el fixture"
    docker compose -f "$COMPOSE" down -v >/dev/null 2>&1 || true
    [ "$MODE" = "--device" ] || "$ADB" emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== fixture"
docker compose -f "$COMPOSE" up -d --build --wait
for _ in $(seq 1 30); do
    ssh-keyscan -p 2222 127.0.0.1 >/dev/null 2>&1 && break
    sleep 1
done

if [ "$MODE" = "--device" ]; then
    echo "== dispositivo conectado (no repetible: para CI/rutina usar el AVD)"
    "$ADB" wait-for-device
    # el fixture vive en el loopback del host: se lo acercamos al dispositivo
    "$ADB" reverse tcp:2222 tcp:2222
    "$ADB" reverse tcp:2223 tcp:2223
else
    echo "== emulador $AVD"
    # -gpu host: esta máquina tiene una NVIDIA libre (el escritorio usa la Intel).
    # Con render por software el emulador compite con Gradle y puede colgar la máquina.
    "${EMULATOR:-emulator}" -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -no-snapshot -wipe-data -memory "${E2E_MEM:-2048}" -cores 2 \
        -gpu "${E2E_GPU:-host}" >/tmp/e2e-emulator.log 2>&1 &
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
    # Espresso exige animaciones apagadas
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
        "$ADB" shell settings put global "$s" 0
    done
    "$ADB" shell input keyevent 82 || true   # sacar el keyguard
fi

echo "== suite"
cd "$REPO/android"
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.remoteclaude.app.TerminalE2ETest \
    -Pandroid.testInstrumentationRunnerArguments.fixtureHost="${FIXTURE_HOST:-127.0.0.1}"

echo "== reporte: android/app/build/reports/androidTests/connected/"
