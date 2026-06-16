# Remoteclaude App (Android)

Cliente Android del setup Remoteclaude: terminales nativas (SSH+tmux) + visualizador
noVNC. Ver el diseño completo en [DESIGN.md](DESIGN.md).

Estado: **M1** — `TerminalView` de Termux (motor vendorizado, GPLv3) renderizando
una sesión de **eco local**. El transporte SSH entra en M2. Ver el motor vendorizado
y sus modificaciones en `terminal-emulator/VENDORED.md`.

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
