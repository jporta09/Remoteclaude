# Remoteclaude App (Android)

Cliente Android del setup Remoteclaude: terminales nativas (SSH+tmux) + visualizador
noVNC. Ver el diseño completo en [DESIGN.md](DESIGN.md).

Estado: **M5** — terminal SSH usable **+ resiliente al bloqueo/cortes de red**: corre
sobre `tmux` (en el contenedor gateway, con paneles que hacen nsenter al host → el host
no necesita tmux) y la app **reconecta sola** y reengancha la sesión (scrollback y línea
en progreso intactos). Detección rápida de caída vía ConnectivityManager. Teclado de
teclas extra (`Esc Tab Ctrl Alt ›` + flechas, chevron a `Home End PgUp PgDn`), Ctrl/Alt
de una pulsación, zoom (pinch) y resize del PTY. Verificado en dispositivo: matar la
conexión SSH → reconexión + reattach con estado preservado. Falta multi-tab (M3),
visualizador (M6), conexión+claves (M7) y pulido (M8).
Falta multi-tab (M3), auto-reconexión + tmux (M5), visualizador (M6), gestión de
claves/conexión (M7) y pulido (M8). El motor Termux vendorizado y sus modificaciones
están en `terminal-emulator/VENDORED.md`.

> Nota dev: la clave SSH de prueba (`app/src/main/assets/m2_test_key`) está fuera de
> git; en M7 la app genera su propio par y guarda la privada en el Android Keystore.

## Compilar (línea de comandos, sin Android Studio)

Requiere un JDK 17 y un Android SDK con `platforms;android-34` + `build-tools;34.0.0`.
En este host se reusa el SDK que dejó buildozer.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/.buildozer/android/platform/android-sdk
# (una sola vez, si faltan componentes)
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # no se commitea
./gradlew assembleDebug
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

## Instalar en el celular

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# o copiá el APK al teléfono y abrilo (permitir "fuentes desconocidas")
```

## Estructura

```
android/
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── gradlew + gradle/wrapper/        # Gradle 8.9 por wrapper
└── app/
    ├── build.gradle.kts             # AGP 8.5.2, Kotlin 1.9.24, minSdk 26
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/remoteclaude/app/MainActivity.kt
        └── res/values/{strings,themes}.xml
```
