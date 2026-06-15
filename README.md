# Remoteclaude — trabajo remoto estable desde el celular

Setup para programar, levantar servers, deployar y testear webs con **Playwright
(navegador visible)** desde el celular, **sin que se rompa al bloquear el teléfono**.

## El problema que resuelve

El escritorio remoto clásico (TeamViewer/AnyDesk/RustDesk) se cae al bloquear el
celular porque **la app del teléfono mantiene un túnel vivo** que el sistema
operativo suspende. La idea acá es la opuesta: **toda la persistencia vive en el
servidor** y el celular es solo un visor *descartable*. Bloquear el teléfono cierra
el visor, no la sesión. Desbloqueás, recargás, y seguís donde estabas.

## Las tres piezas

| Necesidad | Herramienta | Por qué aguanta el bloqueo |
|---|---|---|
| Red sin IP pública ni cortes | **Tailscale** | Sobrevive cambios de IP, sueño y roaming |
| Terminal / servers / deploys | **mosh + tmux** | mosh resume al reconectar; tmux mantiene los procesos vivos |
| Navegador real / Playwright | **noVNC** (en Docker) | El display vive en el server; el celular solo lo mira |

## Instalación (una sola vez por host)

En tu PC Linux (y después igual en el server del amigo):

```bash
bash scripts/setup-host.sh   # instala Tailscale, mosh, tmux, Docker
sudo tailscale up            # autenticá (mismo login en la app del celular)
docker compose up -d --build # levanta el entorno con navegador
```

Instalá en el **celular**: la app de **Tailscale** (mismo login) y un cliente
**SSH/mosh** (Termius o Blink en iOS; Termux o Termius en Android).

> Evitá que la PC se duerma:
> `sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`

## Flujo de trabajo diario (desde el celular)

1. **Terminal estable** — desde tu cliente SSH:
   ```bash
   mosh usuario@nombre-del-host        # nombre que muestra `tailscale status`
   tmux new -A -s dev                  # sesión que nunca muere
   docker exec -it remoteclaude-devbox bash   # entrás al entorno
   ```
   Si bloqueás el celular, al volver mosh resume solo. Si se cortó del todo:
   `mosh ...` de nuevo y `tmux attach -t dev` — todo intacto.

2. **Ver el navegador** — abrí en el navegador del celular:
   ```
   http://nombre-del-host:6080/vnc.html?autoconnect=1&resize=remote
   ```
   (`nombre-del-host` es el de Tailscale). Ahí ves el navegador real del contenedor.

3. **Correr Playwright con navegador visible** (dentro del contenedor):
   ```bash
   npx playwright test --headed          # lo ves en la pestaña de noVNC
   npx playwright test --ui              # UI mode -> http://host:9323
   npx playwright show-report            # reporte -> http://host:9323
   ```

4. **Levantar tu app / deployar** — corré tus servers dentro de `tmux` para que
   sigan vivos aunque cierres todo. Exponé sus puertos en `docker-compose.yml`.

## Mover al server del amigo

Es idéntico: clonás este repo en el server, corrés los tres comandos de
instalación, `tailscale up`, y ya aparece en tu tailnet. El contenedor garantiza
que el entorno sea el mismo en las dos máquinas.

## Ajustes frecuentes

- **Puertos de tu app**: editá la sección `ports` de `docker-compose.yml`.
- **Resolución del navegador**: variable `SCREEN_GEOMETRY` (ej. `1920x1080x24`).
- **Chromium crashea**: ya está `shm_size: 1gb`; subilo si hace falta.
