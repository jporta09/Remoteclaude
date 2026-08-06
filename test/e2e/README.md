# Tests E2E

## Dónde correrlos

**Por defecto: AVD liviano.** Es la forma repetible y desatendida, y no toca el teléfono
de trabajo.

> ⚠️ El AVD `marvin` original (Pixel 6, 3 GB de RAM, GPU por software) **congeló la
> máquina** al arrancar junto con Gradle y los daemons de STT. Para las próximas
> corridas usar un AVD chico y con aceleración:
>
> El AVD `marvin-e2e` ya está creado. Para recrearlo desde cero:
>
> ```bash
> avdmanager create avd -n marvin-e2e -k "system-images;android-34;google_apis;x86_64" \
>     -d pixel_2        # pantalla chica = menos memoria de framebuffer
> ```
>
> ⚠️ **Con el SDK de buildozer hay que corregir el `config.ini` del AVD**: `avdmanager`
> escribe `image.sysdir.1=android-sdk/system-images/…` (relativo a una raíz distinta) y el
> emulador aborta con *"Broken AVD system path"*. Tiene que quedar:
>
> ```
> image.sysdir.1=system-images/android-34/google_apis/x86_64/
> ```
>
> Después, `make e2e` hace todo lo demás. Detalles de por qué está así:
>
> - **`-gpu host` + PRIME offload** (`__NV_PRIME_RENDER_OFFLOAD=1`,
>   `__GLX_VENDOR_LIBRARY_NAME=nvidia`): la **NVIDIA GTX 1660 Ti está libre** porque el
>   escritorio va con la Intel. Con `swiftshader_indirect` el render va por CPU y compite
>   con Gradle — eso fue lo que tiró la máquina abajo. Medido con `-gpu host`: **boot en
>   ~80 s**, 171 MiB de VRAM, 1 % de GPU.
> - Si el STT está en modo ⚡, pasalo a 🌙 (`marvin-stt mode ondemand`) antes de correr:
>   libera ~2 GB de VRAM.
> - Desde el emulador el fixture se alcanza en **`10.0.2.2`** — no hace falta `adb
>   reverse` (eso es sólo para el camino `--device`).

**Excepción: el teléfono.** Sirve para verificar algo puntual, pero **no es repetible**
ni desatendido, y tiene tres condiciones no negociables:

0. **AGP DESINSTALA la app al terminar `connectedAndroidTest`** (se ve en su log:
   `DeviceConnector: uninstalling …`). En un teléfono de uso real eso **borra todos los
   datos**: lista de hosts, clave del Keystore (hay que re-autorizarla en el host) y auth
   key de Tailscale (hay que re-escanear el QR). Ya pasó una vez. Por eso `e2e.sh
   --device` exige `E2E_DEVICE_OK=1` y pasa
   `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Firmar el debug con
   la clave de release **no alcanza**: eso arregla la instalación, no el desinstalado
   posterior.

1. **La build de debug tiene que firmarse con el keystore de release.** Si no, instalarla
   exige desinstalar la release y eso **borra la clave del Keystore y la auth key de
   Tailscale** (habría que re-autorizar la clave en el host y re-escanear el QR).
2. **Nunca usar `clearPackageData`** ni apuntar los tests al host real: los tests crean y
   matan sesiones tmux, y ahí viven sesiones de trabajo. Siempre contra el fixture.

## Fixture

`docker compose -f test/e2e/docker-compose.e2e.yml up -d` levanta un sshd desechable con
tmux, aislado del host real:

- `127.0.0.1:2222` — host key **A**
- `127.0.0.1:2223` — misma imagen, host key **B** (para el test de "la clave del host
  cambió": se apunta el mismo host de la app a los dos puertos)
- usuario `tester`, password `e2e` (sólo para el canal de control del harness; la app
  entra con la clave del Keystore, que el propio test autoriza)
- `~/RemoteMarvinDocs` sembrado, y un stub de `marvin-stt`

Desde el emulador el fixture se alcanza en `10.0.2.2:2222`; desde el teléfono, por la IP
de la tailnet o de la LAN.

## Qué NO cubre (y por qué)

| Brecha | Motivo |
|---|---|
| Captura real de micrófono | el emulador entrega silencio sintético: se prueba todo el plumbing (start/stop/cancel, cabecera WAV, envío por SSH) pero no que tu voz se transcriba bien |
| Cámara / QR de Tailscale | ZXing necesita imagen real; el `virtualscene` es lento y no determinista |
| Tailscale embebido (tsnet) | requiere una tailnet real y una auth key (secreto en el harness); además el AAR es sólo arm64. Los E2E corren con Tailscale deshabilitado, que es el default sin key |
| Fidelidad de render (PDF/imagen/noVNC) | se asserta "hay bitmap / hay páginas / cargó", no pixels: un diff de pixels sería intermitente por densidad y fuentes |
| Calidad de transcripción | corre en el host con GPU; se valida a mano |
