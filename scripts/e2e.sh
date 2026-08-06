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

# AGP necesita un JDK 17 CON jlink (transforma core-for-system-modules.jar). El java por
# defecto acá es un 21 sin jlink, y el fallo aparecía recién al compilar una variante no
# cacheada — o sea, en el momento menos obvio. Se busca uno servible salvo que ya haya
# JAVA_HOME válido.
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/jlink" ]; then
    for j in /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/openjdk-17 /usr/lib/jvm/*17*; do
        [ -x "$j/bin/jlink" ] && { export JAVA_HOME="$j"; break; }
    done
fi
[ -x "${JAVA_HOME:-/nonexistent}/bin/jlink" ] || {
    echo "!! falta un JDK 17 con jlink (probá: apt install openjdk-17-jdk)" >&2; exit 1; }

# Despertar la pantalla y sacar el keyguard. Headless, el emulador arranca con la pantalla
# apagada y las ventanas nunca toman foco: Espresso espera "window focus" y falla aunque lo
# que busca esté visible.
despertar() {
    for _ in $(seq 1 30); do
        "$ADB" -s "$ANDROID_SERIAL" shell input keyevent 224 >/dev/null 2>&1 || true   # WAKEUP
        "$ADB" -s "$ANDROID_SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
        "$ADB" -s "$ANDROID_SERIAL" shell svc power stayon true >/dev/null 2>&1 || true
        # No alcanza con mandar el WAKEUP: hay que CONFIRMAR que el window manager quedó con
        # alguna ventana enfocada. Recién booteado responde "mCurrentFocus=null", y si la
        # suite arranca en ese estado ninguna activity toma foco en toda la corrida: Espresso
        # falla esperando "window focus" con el diálogo visible en pantalla, y el síntoma
        # aparece lejos de la causa (parece un bug de la app, o de R8).
        case "$("$ADB" -s "$ANDROID_SERIAL" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)" in
            *"Window{"*) return 0 ;;
        esac
        sleep 2
    done
    echo "!! el emulador no llegó a tener ventana con foco; los tests de UI van a fallar" >&2
}

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
    "$ADB" -s "$ANDROID_SERIAL" shell settings put secure lockscreen.disabled 1 || true
    despertar
    # desde el emulador, el host es 10.0.2.2 (no hace falta adb reverse)
    : "${FIXTURE_HOST:=10.0.2.2}"
fi

# Repetir antes de la suite: con E2E_KEEP=1 el emulador se reusa entre corridas y llega
# dormido o con el foco en el launcher, y Espresso falla esperando "window focus" aunque el
# diálogo esté visible. Sale gratis y evita perseguir un fantasma.
[ "$MODE" = "--device" ] || despertar

echo "== suite"
cd "$REPO/android"
# E2E_RELEASE=1: corre contra la variante MINIFICADA, que es la única forma de validar que
# las reglas de R8 no rompieron nada (una keep faltante compila bien y falla en runtime).
# Es más lenta en el emulador x86_64: el release es sólo arm64 y va bajo traducción.
if [ "${E2E_RELEASE:-0}" = "1" ]; then
    TASK=:app:connectedReleaseAndroidTest; EXTRA=-PmarvinTestRelease
    echo "   (variante release/R8)"
else
    TASK=:app:connectedDebugAndroidTest; EXTRA=
fi
# leaveApksInstalledAfterRun: evita el desinstalado automático de AGP al terminar.
./gradlew "$TASK" ${EXTRA:+"$EXTRA"} \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.package=com.remoteclaude.app \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.remoteclaude.app.HarnessSetupTest \
    -Pandroid.testInstrumentationRunnerArguments.fixtureHost="${FIXTURE_HOST:-127.0.0.1}"

echo "== reporte: android/app/build/reports/androidTests/connected/"
