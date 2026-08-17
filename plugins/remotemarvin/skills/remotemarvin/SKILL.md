---
name: remotemarvin
description: Overview and router for the RemoteMarvin remote-dev app — use when the user works through RemoteMarvin/Remoteclaude (controlling their PC from an Android phone over Tailscale) and asks what the app can do, how to use a capability, or to use one of its features. Covers the persistent terminal, embedded Tailscale (QR enrollment), the headed-browser/noVNC viewer, and the two-way document viewer (share to the phone; files the user uploads from the phone land in ~/RemoteMarvinDocs/subidos/). Routes to the specific skills (share-doc, headed-browser) and host helpers (marvin-share, ts-link-qr) for the actual work. Trigger on "RemoteMarvin", "la app", "qué puede hacer / cómo uso la app", "mostrame en el celu", or any request that maps to one of the capabilities below. Defer to the specific skill once the intent is clear.
---

# RemoteMarvin — capabilities & routing

RemoteMarvin is an Android app to drive the user's PC from their phone over an
**embedded Tailscale** node (no separate Tailscale app needed). The user is usually on
their phone. This skill is the map; it points to the specific skill/helper for each
capability. Use the most specific one once the intent is clear.

## Capabilities and where each lives

- **Terminal (SSH + tmux, persistente)** — the core. Multi-pestaña, sobrevive cortes y
  bloqueo (auto-reconexión); es SSH directo al sshd del *host* como tu usuario (sin root;
  root sólo con `sudo` puntual). Las sesiones de la app corren Claude Code en **modo
  fullscreen** (`CLAUDE_CODE_NO_FLICKER=1` en el env de tmux) — dato útil si el usuario
  reporta problemas de scroll/render del chat en el celu. No skill needed; it's the app
  itself. Each host's tabs persist.

- **Mostrar un documento que generaste** (imagen, PDF, txt, csv, informe, gráfico) en el
  visor de Documentos del celular → use the **`share-doc`** skill: corré
  `scripts/marvin-share.sh <archivos>` (viene bundleado en esa skill) y avisá que está en
  Documentos. Hacelo proactivamente al producir algo visible. El visor abre hasta 8 MB.

- **Recibir un archivo que el usuario subió DESDE el celular** (foto, captura, archivo del
  picker de Android) → cae en `~/RemoteMarvinDocs/subidos/` del host, con nombre
  normalizado y tope de 25 MB. "Te subí X desde el celu" = mirá ahí
  (`ls -t ~/RemoteMarvinDocs/subidos/`). La pantalla de Documentos además deja ordenar
  la lista (5 criterios) y borrar archivos del host con long-press.

- **Mostrar un navegador en vivo** (scraping/automatización que el usuario *mira*, o que
  necesita display real anti-bot) → use the **`headed-browser`** skill: corré con
  `run-visible.sh` (noVNC `:99`, se mira desde el celu) o `run-local.sh` (monitor físico).
  Preguntá noVNC-remoto vs local si no está claro.

- **Inspección headless para vos** (no para mostrarle al usuario) → no es de RemoteMarvin;
  usá la skill `webapp-testing` (Playwright headless + screenshots).

- **Vincular el celular a la tailnet (QR)** → en el host:
  `./scripts/ts-link-qr.sh` imprime un QR (auth key OAuth, un solo uso).
  En la app: línea de Tailscale → Escanear QR. El estado pasa a *conectada ✓* y reconecta
  solo después.

- **Host / setup** → `bash scripts/setup-host.sh` (sshd+tmux+docker) y `docker compose up -d`
  (contenedores tailscale + display, sin privilegios). Credenciales en `.env` (incluido el
  OAuth client para los QR). Ver `.env.example` y el README del repo.

## Cómo elegir rápido

- "generá / armá / hacé un (gráfico, PDF, reporte, csv)" o "mostrámelo / pasámelo" →
  producilo y **share-doc**.
- "abrí / mirá el navegador", "quiero ver el browser", "mirá el scraping" →
  **headed-browser**.
- "no me conecta el celu / vincular / QR de Tailscale" → `ts-link-qr` (arriba).
- "te subí un archivo / una foto desde el celu" → `~/RemoteMarvinDocs/subidos/`.
- "qué puede hacer la app / cómo uso X" → respondé con esta guía y derivá. Para el
  detalle de uso existe el **manual publicado** (`~/RemoteMarvinDocs/RemoteMarvin-Manual.pdf`,
  visible en Documentos; se regenera con `scripts/gen-manual.py`) y la app trae una
  **demo de primer uso** por pantalla (repetible con long-press en "_hosts").

Las skills específicas (`share-doc`, `headed-browser`) también disparan solas; esta es
solo el índice para cuando el pedido es genérico o sobre la app en conjunto.
