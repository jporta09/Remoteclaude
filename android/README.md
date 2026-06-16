# Remoteclaude App (Android)

Cliente Android del setup Remoteclaude: terminales nativas (SSH+tmux) + visualizador
noVNC. Ver el diseño completo en [DESIGN.md](DESIGN.md).

Estado: **M4** — terminal SSH **completamente usable**: teclado de teclas extra fijo
(sin scroll) — una fila `Esc Tab Ctrl Alt ›` (chevron angosto que conmuta a
`Home End PgUp PgDn`) y otra fila de flechas `← ↓ ↑ →`. Ctrl/Alt como modificadores de
una pulsación, zoom de fuente (pinch) y resize del PTY (vim/claude se reflowean y
reacomodan con el teclado). Verificado en dispositivo: Ctrl-C, Tab-completion y el
chevron andando.
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
