# RemoteMarvin (app Android)

Cliente Android: terminal SSH+tmux con pestañas, visor noVNC, visor de documentos y dictado
por voz, sobre un nodo Tailscale embebido. El diseño interno está en [DESIGN.md](DESIGN.md);
el modelo de amenaza, en [`../SECURITY.md`](../SECURITY.md).

Versión actual: **1.6.0** (`versionCode` 10), `minSdk` 26, `targetSdk` 34.

## Compilar

Requiere un **JDK 17 con `jlink`** (AGP lo necesita para transformar
`core-for-system-modules.jar`) y un Android SDK con `platforms;android-34` y
`build-tools;34.0.0`. En este host se reusa el SDK que dejó buildozer.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/.buildozer/android/platform/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # no se commitea

./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease   # app/build/outputs/apk/release/  (minificado con R8)
```

Para firmar el release, `keystore.properties` en la raíz de `android/` (gitignored) con
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

> **El debug se firma con la MISMA clave que el release, a propósito.** Así el APK de debug
> entra como actualización sobre la app instalada en vez de exigir desinstalarla — y
> desinstalar borraría la clave del AndroidKeyStore (habría que re-autorizarla en el host) y
> la auth key de Tailscale (habría que re-escanear el QR).

### ABIs

El release lleva **sólo `arm64-v8a`**. El debug agrega `x86_64` para que el emulador de los
E2E corra nativo en vez de bajo traducción ARM. El AAR de Tailscale trae las dos.

## Instalar

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` (actualización) y **nunca** `adb uninstall`: ver la nota de firma más arriba.

## Verificar

```bash
make unit           # tests JVM (51): lógica pura, sin dispositivo
make lint           # lint de Android
make release-check  # build de release + portero de las reglas de R8
make e2e            # suite instrumentada (6 tests) contra un fixture SSH desechable
make e2e-release    # la misma suite contra el APK minificado
```

El detalle de los E2E —cómo se levanta el emulador, qué cubre el fixture y qué queda fuera—
está en [`../test/e2e/README.md`](../test/e2e/README.md).

## Estructura

```
android/
├── app/                      # la aplicación
│   └── src/
│       ├── main/java/com/remoteclaude/app/
│       ├── test/             # tests JVM (sin dispositivo)
│       └── androidTest/      # tests instrumentados (necesitan emulador o teléfono)
├── terminal-emulator/        # motor de terminal de Termux, vendorizado (GPLv3)
├── terminal-view/            # vista de terminal de Termux, vendorizada (GPLv3)
└── app/libs/marvints.aar     # nodo Tailscale embebido (tsnet vía gomobile)
```

Los cambios sobre el código vendorizado están documentados en
`terminal-emulator/VENDORED.md`; la idea es mantenerlos al mínimo para poder seguir los
upstream. El AAR se reconstruye con `make aar` (ver
[`../tailscale-bridge/README.md`](../tailscale-bridge/README.md)).
