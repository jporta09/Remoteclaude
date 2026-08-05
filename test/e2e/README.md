# Tests E2E

## Dónde correrlos

**Por defecto: AVD liviano.** Es la forma repetible y desatendida, y no toca el teléfono
de trabajo.

> ⚠️ El AVD `marvin` original (Pixel 6, 3 GB de RAM, GPU por software) **congeló la
> máquina** al arrancar junto con Gradle y los daemons de STT. Para las próximas
> corridas usar un AVD chico y con aceleración:
>
> ```bash
> avdmanager create avd -n marvin-e2e -k "system-images;android-34;google_apis;x86_64" \
>     -d pixel_2        # pantalla chica = menos memoria de framebuffer
>
> emulator -avd marvin-e2e -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
>     -memory 2048 -cores 2 -gpu host &
> ```
>
> `-gpu host` es importante acá: esta máquina tiene una **NVIDIA GTX 1660 Ti que el
> escritorio no usa** (el escritorio va con la Intel integrada), así que está libre para
> el emulador. Con `swiftshader_indirect` el render va por CPU y compite con Gradle, que
> es justo lo que tiró la máquina abajo. Si el STT está en modo ⚡ conviene pasarlo a
> 🌙 antes de correr la suite, para no disputarle la VRAM.

**Excepción: el teléfono.** Sirve para verificar algo puntual, pero **no es repetible**
ni desatendido, y tiene dos condiciones no negociables:

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
