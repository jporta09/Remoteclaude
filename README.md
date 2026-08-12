# RemoteMarvin

Trabajar de verdad en tu PC desde el celular: terminal nativa que **ejecuta en el host**,
sobrevive al bloqueo del teléfono y a los cambios de red.

La app Android trae **su propio nodo Tailscale** embebido (no hace falta instalar la app de
Tailscale) y hace **SSH directo al sshd del host** como tu usuario, con `tmux` del lado del
host para persistencia. No hay contenedor privilegiado ni gateway: lo único que corre con
permisos especiales es lo que vos hagas con `sudo` dentro de tu propia sesión.

```
┌─ celular ────────────────┐         ┌─ host (tu PC o un server) ──────────────┐
│  RemoteMarvin            │         │                                          │
│   · terminal (Termux     │ tailnet │   sshd del host (solo-clave)             │
│     vendorizado)         │◄───────►│      └── tmux  →  tu shell, tus proyectos│
│   · Tailscale embebido   │  (WireGuard)                                       │
│     (tsnet vía gomobile) │         │   docker: tailscale (red) + display (VNC)│
│   · dictado por voz      │         │   daemons de usuario: render · stt · live│
└──────────────────────────┘         └──────────────────────────────────────────┘
```

## Qué hace

- **Terminal con pestañas.** Cada pestaña es una sesión `tmux` propia en el host, así que
  sobreviven a que cierres la app; podés reenganchar las que quedaron abiertas.
- **Teclado para programar.** Esc, Tab, Ctrl, Alt, flechas, Home/End/PgUp/PgDn y **⇧Tab**
  (la secuencia que Claude Code lee para cambiar de modo).
- **Dictado por voz.** Mantenés 🎤 y hablás: el texto aparece en la terminal como si lo
  hubieras tipeado. Con GPU en el host hay parciales en vivo mientras hablás.
- **Visor de escritorio 🖥.** Un navegador *headed* corre en el host sobre una pantalla
  virtual aislada y lo mirás desde el celular. Útil para ver tests de Playwright, o para
  scraping que necesita display real.
- **Documentos 📄.** Lo que Claude comparte en el host (`~/RemoteMarvinDocs`) se lee desde
  el celular, incluso si lo generó un server remoto al que SSH-easte desde la app.
- **Portapapeles compartido.** Seleccionás con el dedo en la terminal y queda en el
  portapapeles del teléfono (OSC 52).

## Instalación

En el **host** (una vez):

```bash
bash scripts/setup-host.sh     # sshd solo-clave, tmux, daemons de usuario, ssh/config
cp .env.example .env           # completá TS_AUTHKEY (y el OAuth si querés vincular por QR)
docker compose up -d --build   # contenedores: tailscale (red) + display (visor)
```

Después, autorizá el celular: en la app, botón **🔑** → copiás la clave pública → la pegás
en `~/.ssh/authorized_keys` del host.

En el **celular**: instalás el APK (`android/app/build/outputs/apk/release/`) y vinculás su
nodo Tailscale escaneando el QR que imprime `./scripts/ts-link-qr.sh`.

## Uso

Abrís la app, elegís el host y ya estás en tu shell. Los botones de la barra:

| | Qué hace |
|---|---|
| **+** | pestaña nueva (sesión tmux nueva en el host) |
| **⟳** | reenganchar una sesión que quedó viva |
| **🖥** | ver el escritorio virtual del host |
| **📄** | documentos compartidos |
| **🔑** | la clave pública de la app |

Para que el navegador del host dibuje en la pantalla que ves desde el celu:

```bash
# El display exige cookie: la publica el contenedor al arrancar.
XAUTHORITY=~/.config/marvin/Xauthority DISPLAY=localhost:99 npx playwright test --headed
```

Más simple, el helper del plugin la resuelve solo:

```bash
plugins/remotemarvin/skills/headed-browser/scripts/run-visible.sh npx playwright test --headed
```

El plugin `plugins/remotemarvin/` le enseña a Claude Code a usar todo esto desde el host
(compartir documentos, abrir cosas en el visor, correr browsers headed visibles).

## Seguridad

El modelo de amenaza está en [`SECURITY.md`](SECURITY.md). En resumen: nada escucha en la
red salvo el sshd del host; el visor y el dictado viajan **tunelizados por tu propia
conexión SSH**; la app fija (TOFU) la clave del host y **rechaza** la conexión si cambia; y
los secretos de la app se guardan cifrados con una clave del AndroidKeyStore.

## Desarrollo

```bash
make unit           # tests JVM de la app (sin dispositivo)
make host           # tests de los daemons del host
make lint           # lint de Android + shellcheck + ruff
make release-check  # build de release + verificación de las reglas de R8
make e2e            # suite instrumentada en un emulador (ver test/e2e/README.md)
make all            # todo lo que no necesita dispositivo
```

- La app está en [`android/`](android/README.md); su diseño interno, en
  [`android/DESIGN.md`](android/DESIGN.md).
- El nodo Tailscale embebido se construye con `make aar` (ver
  [`tailscale-bridge/README.md`](tailscale-bridge/README.md)).
- `docs/revision-integral.md` es el registro vivo de la revisión de seguridad,
  correctitud y tests: qué se cerró, qué falta y por qué.

## Licencia

GPL-3.0-only. La app incluye el motor de terminal de Termux (GPLv3), así que la obra
combinada va bajo la misma licencia — detalle en [`NOTICE.md`](NOTICE.md).
