#!/usr/bin/env bash
# Corre la suite E2E instrumentada contra el fixture desechable (test/e2e).
#
#   scripts/e2e.sh              # AVD liviano (default; ver test/e2e/README.md)
#   scripts/e2e.sh --device     # dispositivo ya conectado por adb (no repetible)
#   scripts/e2e.sh --caja-negra # valida el APK PUBLICADO manejándolo desde afuera
#
# --caja-negra corre los tests de :blackbox, que no referencian ninguna clase de la app y
# por eso no obligan a R8 a dejar costuras: el APK que se prueba tiene el mismo bytecode
# que el que se publica, y el script lo comprueba en vez de pedir que se le crea.
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

# AGP necesita un JDK 17 CON jlink (transforma core-for-system-modules.jar), y el fallo
# aparecía recién al compilar una variante no cacheada — el momento menos obvio. Lo mismo
# le pasaba al Makefile, así que la búsqueda vive en un solo lado.
JAVA_HOME="$("$(dirname "$0")/jdk17.sh")" || exit 1
export JAVA_HOME

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
    if [ "$MODE" != "--device" ]; then
        "$ADB" -s "${ANDROID_SERIAL:-emulator-5556}" emu kill >/dev/null 2>&1 || true
        # `emu kill` habla por adb, así que NO sirve justo cuando más hace falta: si el
        # emulador no llegó a bootear (por ejemplo, sin contexto gráfico no arranca el
        # renderer), no hay adb con quien hablar y el proceso queda vivo. Pasó de verdad:
        # dos emuladores huérfanos envenenaron la corrida siguiente, que fallo 6 tests con
        # errores de conexión al fixture — un sintoma que no se parece en nada a la causa.
        #
        # El patrón va con corchetes para que pgrep no matchee esta misma línea de comandos
        # y el script no se mate a sí mismo.
        sleep 1
        for p in $(pgrep -f "[q]emu-system.*$AVD" 2>/dev/null); do
            echo "== emulador $p seguía vivo tras emu kill; lo termino"
            kill "$p" 2>/dev/null || true
        done
    fi
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
    #
    # Pero el camino acelerado del emulador pasa por X11: SIN sesión gráfica no arranca y
    # —lo peor— no se degrada solo, muere con "Could not start renderer". Se elige según lo
    # que haya en vez de dejarlo fijo: así el mismo comando sirve en una terminal del
    # escritorio y en el runner de CI, que arranca con el boot y no tiene ninguna sesión.
    # Antes esto era un default fijo y el resultado dependía de si alguien estaba logueado.
    if [ -z "${E2E_GPU:-}" ]; then
        if [ -n "${DISPLAY:-}" ]; then E2E_GPU=host; else E2E_GPU=swiftshader_indirect; fi
    fi
    echo "   gpu: $E2E_GPU${DISPLAY:+ (DISPLAY=$DISPLAY)}"
    __NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia \
    "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        -memory "${E2E_MEM:-2048}" -cores "${E2E_CORES:-2}" -gpu "$E2E_GPU" \
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
if [ "$MODE" = "--caja-negra" ]; then
    # El APK bajo prueba se construye SIN -PmarvinTestRelease, o sea con las reglas de R8
    # del APK publicado. -PmarvinEmuAbi agrega la .so de x86_64 para que corra nativo en el
    # emulador y no toca una sola regla; que el DEX sea el mismo no se afirma, se comprueba
    # abajo contra un build limpio.
    echo "== APK de release (reglas del publicado)"
    ./gradlew :app:assembleRelease -PmarvinEmuAbi
    # Sin android/keystore.properties (gitignoreado: existe en la máquina de quien publica,
    # no en un checkout limpio) AGP nombra la salida "-unsigned". El script buscaba sólo el
    # nombre firmado y moría con un `cp` sin archivo, DESPUÉS de un build exitoso — el peor
    # lugar para fallar.
    SALIDA="$REPO/android/app/build/outputs/apk/release"
    APK="$SALIDA/app-release.apk"
    [ -f "$APK" ] || APK="$SALIDA/app-release-unsigned.apk"
    [ -f "$APK" ] || { echo "!! no encontré el APK de release en $SALIDA" >&2; ls -1 "$SALIDA" >&2; exit 1; }
    cp "$APK" /tmp/marvin-caja-negra.apk

    case "$APK" in *-unsigned.apk)
        # Un APK sin firmar no se instala. Se firma con la clave de debug SOLO para poder
        # instalarlo: la firma vive en el bloque de firma del zip y no toca el bytecode, que
        # es exactamente lo que esta validación mira (y que se compara byte a byte abajo).
        echo "   (sin keystore.properties: se firma con la clave de debug para poder instalar)"
        DEBUG_KS="$HOME/.android/debug.keystore"
        [ -f "$DEBUG_KS" ] || keytool -genkeypair -keystore "$DEBUG_KS" -storepass android \
            -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US" >/dev/null
        APKSIGNER="$(ls -1 "${ANDROID_SDK_ROOT:-$HOME/.buildozer/android/platform/android-sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
        [ -n "$APKSIGNER" ] || { echo "!! no encontré apksigner en el SDK" >&2; exit 1; }
        "$APKSIGNER" sign --ks "$DEBUG_KS" --ks-pass pass:android --key-pass pass:android \
            --ks-key-alias androiddebugkey /tmp/marvin-caja-negra.apk
        ;;
    esac

    echo "== comprobando que el DEX sea el del APK publicado"
    ./gradlew :app:assembleRelease            # sin la ABI extra: exactamente lo que se publica
    python3 - "$APK" /tmp/marvin-caja-negra.apk <<'PY'
import hashlib, sys, zipfile
def dex(p):
    z = zipfile.ZipFile(p)
    return {n: hashlib.sha256(z.read(n)).hexdigest()
            for n in sorted(z.namelist()) if n.endswith(".dex")}
pub, probado = dex(sys.argv[1]), dex(sys.argv[2])
if pub != probado:
    print("!! el APK a probar NO tiene el bytecode del publicado — la validación no valdría", file=sys.stderr)
    for n in sorted(set(pub) | set(probado)):
        print(f"   {n}: publicado={pub.get(n,'(falta)')[:16]} probado={probado.get(n,'(falta)')[:16]}", file=sys.stderr)
    raise SystemExit(1)
print("   ✓ DEX idéntico al publicado (" + ", ".join(f"{n}={h[:12]}" for n, h in pub.items()) + ")")
PY

    # Instalación por adb y no por Gradle: Gradle instalaría el APK de la variante que él
    # construye, y acá el punto es probar ESTE archivo. -r conserva los datos (la clave del
    # Keystore vive ahí; borrarla sería perder justo lo que interesa que sobreviva).
    echo "== instalando el APK publicado"
    "$ADB" -s "${ANDROID_SERIAL:-emulator-5556}" install -r /tmp/marvin-caja-negra.apk

    # Conceder POST_NOTIFICATIONS por adb (como si tocaras "Allow"): en Android 13+ la app pide el
    # permiso al arrancar y ese diálogo del sistema queda ENCIMA, tapando la pantalla que la caja
    # negra espera ("Falta autorizar este dispositivo"). El suite de :app ya lo concede vía
    # MarvinTestRunner; la caja negra prueba el APK desde afuera y no pasa por ese runner. El permiso
    # es ortogonal a lo que testea (SSH + sesión).
    "$ADB" -s "${ANDROID_SERIAL:-emulator-5556}" shell pm grant com.remoteclaude.app \
        android.permission.POST_NOTIFICATIONS 2>/dev/null || true

    # Despertar OTRA VEZ: entre el despertar de arriba y este punto pasaron dos builds de
    # release y una instalación, o sea varios minutos. Si la pantalla se durmió en el medio,
    # ninguna ventana toma foco y toda la suite falla con "no apareció en pantalla…", que
    # parece un problema de la app. Ya se vio una corrida así.
    [ "$MODE" = "--device" ] || despertar

    echo "== suite de caja negra"
    ./gradlew :blackbox:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        -Pandroid.testInstrumentationRunnerArguments.fixtureHost="${FIXTURE_HOST:-127.0.0.1}" \
        -Pandroid.testInstrumentationRunnerArguments.fixturePort=2222
    echo "== reporte: android/blackbox/build/reports/androidTests/connected/"
    exit 0
fi

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
