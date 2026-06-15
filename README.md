# Remoteclaude — trabajo remoto estable desde el celular

Setup para programar, levantar servers, deployar y testear webs con **Playwright
(navegador visible)** desde el celular, **sin que se rompa al bloquear el teléfono**.

## El problema que resuelve

El escritorio remoto clásico (TeamViewer/AnyDesk/RustDesk) se cae al bloquear el
celular porque **la app del teléfono mantiene un túnel vivo** que el sistema
operativo suspende. Acá es al revés: **toda la persistencia vive en el servidor** y
el celular es solo un visor *descartable*. Bloqueás → se cierra el visor, no la
sesión. Desbloqueás, recargás, y seguís donde estabas.

## Diseño: todo containerizado (despliegue agnóstico)

Lo único que necesita el host es **Docker** (+ el device `/dev/net/tun`). Todo lo
demás vive en contenedores, así que cualquier máquina con Docker se vuelve drop-in:

```
┌─ host: solo Docker ─────────────────────────────────┐
│  ┌─ contenedor tailscale ─┐  ┌─ contenedor devbox ─┐ │
│  │ une el stack a tu      │←─┤ sshd + mosh + tmux  │ │
│  │ tailnet (IP/MagicDNS)  │  │ Xvfb+x11vnc+noVNC   │ │
│  └────────────────────────┘  │ Playwright/Chromium │ │
│         (red compartida)      └─────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

| Necesidad | Dónde corre | Por qué aguanta el bloqueo |
|---|---|---|
| Red sin IP pública ni cortes | contenedor **Tailscale** | Sobrevive cambios de IP, sueño y roaming |
| Terminal / servers / deploys | **mosh + tmux** en el devbox | mosh resume al reconectar; tmux mantiene los procesos vivos |
| Navegador real / Playwright | **noVNC** en el devbox | El display vive en el server; el celular solo lo mira |

## Instalación (una sola vez por host)

```bash
bash scripts/setup-host.sh              # instala Docker (lo único del host)

cp .env.example .env                    # 1) pegá tu TS_AUTHKEY de Tailscale
cp ssh/authorized_keys.example ssh/authorized_keys   # 2) pegá tu clave pública

docker compose up -d --build            # levanta tailscale + devbox
```

- **TS_AUTHKEY**: Admin console de Tailscale → Settings → Keys → *Generate auth key*
  (marcala **Reusable** para que el nodo conserve el nombre `remoteclaude`).
- **Clave pública**: la generás/copiás desde la app de SSH del celular.

En el **celular** instalá: app de **Tailscale** (mismo login) + un cliente
**mosh/SSH** (Termius o Blink en iOS; Termux o Termius en Android).

> Evitá que la PC se duerma:
> `sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`

## Flujo de trabajo diario (desde el celular)

1. **Terminal estable** — entrás directo al contenedor por su nombre de MagicDNS:
   ```bash
   mosh root@remoteclaude        # "remoteclaude" = hostname del compose
   tmux new -A -s dev            # sesión que nunca muere
   ```
   Si bloqueás el celular, al volver mosh resume solo. Si se cortó del todo:
   `mosh root@remoteclaude` de nuevo y `tmux attach -t dev` — todo intacto.

2. **Ver el navegador** — en el navegador del celular:
   ```
   http://remoteclaude:6080/vnc.html?autoconnect=1&resize=remote
   ```

3. **Playwright con navegador visible** (dentro del devbox, dir. `/work`):
   ```bash
   npx playwright test --headed     # lo ves en la pestaña de noVNC
   npx playwright test --ui         # UI mode -> http://remoteclaude:9323
   npx playwright show-report       # reporte  -> http://remoteclaude:9323
   ```

4. **Levantar tu app / deployar** — corré tus servers dentro de `tmux` para que
   sigan vivos. Como el devbox comparte la red de Tailscale, cualquier puerto que
   abras es alcanzable como `http://remoteclaude:<puerto>` desde el celular.

## Mover al server del amigo

Idéntico: cloná el repo, `bash scripts/setup-host.sh`, poné `.env` +
`ssh/authorized_keys`, `docker compose up -d --build`. Cambiá el `hostname:` del
servicio `tailscale` en `docker-compose.yml` si querés otro nombre de MagicDNS.

## Tradeoffs de containerizar Tailscale

- El contenedor de Tailscale necesita cap `NET_ADMIN` y el device `/dev/net/tun`
  (levemente privilegiado). En tu PC y el server del amigo no hay problema; algunos
  hosts gestionados lo bloquean.
- `.env` y `ssh/authorized_keys` quedan fuera de git (ver `.gitignore`).

## Ajustes frecuentes

- **Resolución del navegador**: variable `SCREEN_GEOMETRY` (ej. `1920x1080x24`).
- **Acceso local en el host** (sin Tailscale): puertos publicados en el servicio
  `tailscale` (`6080`, `9323`); agregá los de tu app ahí.
- **Chromium crashea**: ya está `shm_size: 1gb`; subilo si hace falta.
