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
    [ "$MODE" = "--device" ] || "$ADB" -s "${ANDROID_SERIAL:-emulator-5556}" emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== fixture"
docker compose -f "$COMPOSE" up -d --build --wait
for _ in $(seq 1 30); do
    ssh-keyscan -p 2222 127.0.0.1 >/dev/null 2>&1 && break
    sleep 1
done

if [ "$MODE" = "--device" ]; then
    # AGP DESINSTALA la app al terminar connectedAndroidTest (mensaje "uninstalling" en
    # DeviceConnector). En un teléfono de uso real eso borra la lista de hosts, la clave
    # del Keystore y la auth key de Tailscale. Ya pasó una vez: por eso este camino pide
    # confirmación explícita y deja las APKs instaladas.
    if [ "${E2E_DEVICE_OK:-}" != "1" ]; then
        cat >&2 <<'WARN'
!! Correr la suite contra un DISPOSITIVO REAL borra los datos de la app al terminar
   (AGP desinstala las APKs tras connectedAndroidTest). Perdés: hosts, clave del
   Keystore (hay que re-autorizarla en el host) y auth key de Tailscale (re-escanear QR).

   Usá el AVD:            make e2e
   Si aun así querés:     E2E_DEVICE_OK=1 make e2e-device
WARN
        exit 2
    fi
    echo "== dispositivo conectado (no repetible: para CI/rutina usar el AVD)"
    "$ADB" wait-for-device
    # el fixture vive en el loopback del host: se lo acercamos al dispositivo
    "$ADB" reverse tcp:2222 tcp:2222
    "$ADB" reverse tcp:2223 tcp:2223
else
    echo "== emulador $AVD"
    : "${ANDROID_SDK_ROOT:=$HOME/.buildozer/android/platform/android-sdk}"
    export ANDROID_SDK_ROOT
    EMU="${EMULATOR:-$ANDROID_SDK_ROOT/emulator/emulator}"
    PORT="${E2E_EMU_PORT:-5556}"
    export ANDROID_SERIAL="emulator-$PORT"
    # -gpu host + PRIME offload: esta máquina tiene una NVIDIA libre (el escritorio va con
    # la Intel). Con render por software el emulador compite con Gradle por CPU y ya colgó
    # la máquina una vez.
    __NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia \
    "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        -memory "${E2E_MEM:-2048}" -cores "${E2E_CORES:-2}" -gpu "${E2E_GPU:-host}" \
        -port "$PORT" >/tmp/e2e-emulator.log 2>&1 &
    echo "   esperando boot…"
    for _ in $(seq 1 40); do
        [ "$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 5
    done
    [ "$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
        echo "!! el emulador no booteó — ver /tmp/e2e-emulator.log" >&2; tail -5 /tmp/e2e-emulator.log >&2; exit 1; }
    # Espresso exige animaciones apagadas
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
        "$ADB" -s "$ANDROID_SERIAL" shell settings put global "$s" 0
    done
    # Despertar la pantalla y sacar el keyguard: headless, el emulador arranca con la
    # pantalla apagada y las ventanas nunca toman foco — Espresso se queda esperando
    # "window focus" y falla aunque el diálogo esté visible.
    "$ADB" -s "$ANDROID_SERIAL" shell input keyevent 224 || true          # WAKEUP
    "$ADB" -s "$ANDROID_SERIAL" shell wm dismiss-keyguard || true
    "$ADB" -s "$ANDROID_SERIAL" shell input keyevent 82 || true
    "$ADB" -s "$ANDROID_SERIAL" shell settings put secure lockscreen.disabled 1 || true
    # desde el emulador, el host es 10.0.2.2 (no hace falta adb reverse)
    : "${FIXTURE_HOST:=10.0.2.2}"
fi

echo "== suite"
cd "$REPO/android"
# leaveApksInstalledAfterRun: evita el desinstalado automático de AGP al terminar.
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.package=com.remoteclaude.app \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.remoteclaude.app.HarnessSetupTest \
    -Pandroid.testInstrumentationRunnerArguments.fixtureHost="${FIXTURE_HOST:-127.0.0.1}"

echo "== reporte: android/app/build/reports/androidTests/connected/"
