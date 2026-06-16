# Remoteclaude — gateway de trabajo remoto desde el celular

Conecta tu celular con tu PC (o un server) de forma **estable** y **agnóstica**,
para programar de verdad: terminal nativa que **ejecuta en el host**, sobreviviendo
al bloqueo del teléfono.

## Idea central

El contenedor **NO** es donde trabajás: es solo la **capa de conexión**. La
ejecución ocurre en el **host**, con sus herramientas, proyectos, `claude` e
historial de conversaciones — todo nativo, sin instalar ni montar nada del host.

```
┌─ host (PC o server): solo necesita Docker ───────────────────────┐
│                                                                   │
│   ┌─ contenedor tailscale ─┐   ┌─ contenedor gateway ─────────┐   │
│   │ pone al HOST en tu     │   │ sshd + mosh                  │   │
│   │ tailnet (network=host) │   │ al loguearte: nsenter -> HOST│   │
│   └────────────────────────┘   └──────────────┬───────────────┘   │
│                                                │ nsenter PID 1     │
│                                  ╔═════════════▼═══════════════╗   │
│                                  ║  SHELL DEL HOST (jporta)    ║   │
│                                  ║  uv · node · claude · ~/... ║   │
│                                  ╚═════════════════════════════╝   │
└───────────────────────────────────────────────────────────────────┘
```

- **Agnóstico**: el contenedor solo asume "Linux con Docker". Lo mismo en tu PC y
  en el server del amigo; lo único que cambia es `HOST_USER` en `.env`.
- **Sin duplicar tu stack**: no reinstalás nada. La shell salta al host y usa lo
  que el host ya tiene (uv, node, claude, libs, configs, conversaciones).
- **Estable al bloqueo**: `mosh` resume al reconectar; `tmux` (del host) persiste.

## Cómo funciona el "salto al host"

El gateway corre **privilegiado** con `pid: host`. Al loguearte por SSH/mosh, la
shell de login es `host-shell`, que hace `nsenter --target 1` a los namespaces de
`systemd` (PID 1) y abre la sesión como `HOST_USER`. Esa shell **es un proceso del
host**: ve el filesystem, el `$PATH`, los procesos y los archivos del host.

> El contenedor privilegiado con acceso a los namespaces del host es inherente a
> "controlar remotamente la propia máquina". Es tu equipo y tu tailnet privada.

## Instalación (una vez por host)

Requisito del host: **Docker** + `/dev/net/tun` (universal en Linux).

```bash
bash scripts/setup-host.sh        # instala Docker si falta

cp .env.example .env              # completá:
#   TS_AUTHKEY   -> Admin console de Tailscale -> Settings -> Keys (Reusable)
#   HOST_USER    -> el usuario del host (ej. jporta)
cp ssh/authorized_keys.example ssh/authorized_keys   # pegá tu clave pública

docker compose up -d --build
```

En el **celular**: app **Tailscale** (mismo login) + **Termux** (desde F-Droid),
con `pkg install openssh mosh`.

## Uso diario (desde el celular)

```bash
mosh root@remoteclaude        # 'remoteclaude' = nombre de MagicDNS del host
# -> caés directo en la shell del host como tu usuario
tmux new -A -s dev            # sesión persistente (tmux del host)
cd ~/proyectos/...            # tus proyectos reales
claude                        # Claude Code con tu historial
```

- Te conectás como `root` (usuario del *contenedor*); la shell salta solo al
  `HOST_USER` configurado. El comando es el mismo sin importar el usuario del host.
- Bloqueás el celular → `mosh` resume al volver. Si mosh muere, reconectás y
  `tmux attach -t dev`.
- Cualquier server/dev-server que levantes en el host es alcanzable desde el celu
  como `http://remoteclaude:<puerto>` (Tailscale carga la red del host).

## Ver el navegador headed (servicio `display`)

El servicio `display` levanta una **pantalla virtual aislada** (Xvfb) + noVNC. Tu
navegador/Playwright corre **en el host** pero dibuja en esa pantalla; vos la mirás
desde el celular. No expone tu escritorio real y anda igual en un server sin monitor.

En la shell del host (vía mosh), apuntá el navegador a la pantalla con `DISPLAY`:

```bash
DISPLAY=localhost:99 npx playwright test --headed
DISPLAY=localhost:99 google-chrome https://ejemplo.com   # cualquier app X sirve
```

Y miralo desde el celular:

```
http://remoteclaude:6080/vnc.html?autoconnect=1&resize=scale
```

- El puerto X (`6099`) queda **solo en localhost del host** (X es inseguro); el
  noVNC (`6080`) sí es visible por la tailnet.
- Resolución: variable `SCREEN_GEOMETRY` (ej. `SCREEN_GEOMETRY=1920x1080x24`).

> **Navegadores sí, OpenGL nativo no.** Chromium/Playwright headed renderizan bien
> sobre esta pantalla (con `--disable-gpu`, que Playwright headed ya aplica). Las
> apps OpenGL nativas (p.ej. Kivy) NO dibujan bien sobre X remoto/TCP; para eso
> habría que correrlas en un contenedor con su propio Xvfb local. Para testing de
> webs, que es el caso de uso, funciona.

## Skill de Claude: `headed-browser`

El repo trae una skill publicable en `skills/headed-browser/` que le enseña a Claude
a correr **cualquier** automatización de browser **headed** (Playwright, nodriver,
Selenium, Chromium) sobre la pantalla virtual `:99`, visible desde el celular. Útil
para mirar tests en vivo y para scraping que **necesita** display real (anti-bot tipo
DataDome). Compone con la skill oficial `webapp-testing` (que corre headless para los
screenshots de Claude); ésta agrega el modo headed+visible.

Helper:
```bash
skills/headed-browser/scripts/run-visible.sh <comando>     # setea DISPLAY=localhost:99
# ej: run-visible.sh uv run python scraper.py
#     run-visible.sh npx playwright test --headed
```

Instalar para usarla en todos tus proyectos (symlink o copia a las skills de usuario):
```bash
ln -sfn "$PWD/skills/headed-browser" ~/.claude/skills/headed-browser
```

## Mover al server del amigo

Idéntico: cloná el repo, `bash scripts/setup-host.sh`, poné `.env`
(`HOST_USER` el de ese server) + `ssh/authorized_keys`, `docker compose up -d --build`.
Cambiá `TS_HOSTNAME` si querés otro nombre de MagicDNS.

## Configuración (`.env`)

| Variable | Qué es | Default |
|---|---|---|
| `TS_AUTHKEY` | Auth key de Tailscale | (obligatorio) |
| `HOST_USER` | Usuario del host al que salta la shell | `root` |
| `TS_HOSTNAME` | Nombre del nodo en la tailnet (MagicDNS) | `remoteclaude` |
| `SSH_PORT` | Puerto del sshd del gateway | `22` |

## Pendiente

- App Android propia (wrapper de terminal nativa) como proyecto aparte.
