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

- **Visual / navegador headed** (ver Playwright corriendo): bajo este diseño la
  ejecución es en el host, así que lo visual será "ver la pantalla del host" o
  acceder a sus dev-servers por el browser del celular. A definir.
- App Android propia (wrapper de terminal nativa) como proyecto aparte.
