---
name: headed-browser
description: Run a real browser in HEADED (visible) mode on a shared virtual display the user watches live from their phone via noVNC. Trigger only when an ACTUAL browser must run non-headless AND be seen or given a real display — the user asks to watch/see a Playwright/Selenium/nodriver/Puppeteer/Chromium run live, debug a browser flow visually, or run browser automation that needs a genuine display to beat anti-bot detection (DataDome, PerimeterX, Cloudflare interactive) that flags headless. Do NOT trigger for data work with no visible browser: API/JSON scraping, HTTP/requests/curl calls, parsing static or already-downloaded HTML, writing test files, or headless test runs and page screenshots where Claude only needs to inspect a page (that is the webapp-testing skill — it drives Playwright headless for Claude's own screenshots). The deciding factor is "a browser window must actually run, headed, and be visible to the human", not the word "scrape" or "test". Part of the Remoteclaude remote-dev setup (display on DISPLAY :99, noVNC :6080).
---

# Headed browser on a shared, watchable display

Some browser work cannot be headless: anti-bot systems (DataDome, PerimeterX,
Cloudflare's interactive challenge) detect headless, and sometimes the human just
wants to *watch* the browser live. In a Remoteclaude session the execution runs on
the host but there is no physical screen the user can see from their phone — so a
headless run is invisible and a naive headed run has no display to draw to.

This skill solves both: the **`display` container** provides an isolated virtual X
screen at **`:99`** plus a **noVNC** web viewer on **`:6080`**. Point any browser
automation at `DISPLAY=localhost:99` and it runs headed, drawing to that screen,
which the user watches from their phone.

## When to use this

- The user says "run it headed", "I want to watch", "show me the browser", "abrí el
  navegador", "mirá el scraping", or is debugging a browser flow from their phone.
- Scraping/automation that **requires a real display** (anti-bot detects headless).
- Any Playwright/Selenium/nodriver/Puppeteer/Chromium run you want visible.

When the goal is just for **Claude** to inspect a page (not the human), prefer the
`webapp-testing` skill in **headless** mode + screenshots — it's faster and needs no
display. Use *this* skill when the browser must be headed and/or human-visible.

## First: ask WHERE it should draw (noVNC remoto vs monitor local)

There are two screens, and picking the wrong one means the user sees nothing:

- **Remoto (noVNC)** — the virtual display `:99` inside the `display` container, watched
  from the phone (or any browser) at `:6080`. Use when the user is **away from the PC**
  (típico: mirando desde el celu).
- **Local (monitor físico)** — the user's own X session (`:0`), the browser pops up as a
  normal window. Use when the user is **sentado en la PC**.

**Unless it's already obvious from the conversation, ASK before launching** (one quick
AskUserQuestion: "¿Lo ves por noVNC desde el celu, o estás local y lo abro en tu
monitor?"). We learned this the hard way: defaulting to noVNC while the user was local
meant the browser drew to a screen they weren't looking at. If the user already said
"estoy local" / "desde el celu", skip the question.

## How to run something visible

Pick the helper for the chosen screen. Each checks its display is up, sets `DISPLAY`,
and runs your command:

```bash
# Remoto — dibuja en :99, se mira por noVNC
scripts/run-visible.sh <your command...>

# Local — dibuja en el monitor físico del usuario (detecta su display X)
scripts/run-local.sh <your command...>
```

**Estás en un SERVER por SSH (no en el host de la app)?** `run-visible.sh` lo maneja solo:
si llegaste a ese server **SSH-eando DESDE la app**, el `~/.ssh/config` del host tendió un
reverse tunnel del display a `localhost:6099`, y el script lo detecta y dibuja ahí → se ve
en el **noVNC del host de la app** (no hay que instalar nada en el server: el Chromium de
Playwright ya está en `~/.cache/ms-playwright`). Va con algo de lag para UI pesada (es X11
por la red). Para algo fluido sin noVNC, usá **CDP** (`chromium --remote-debugging-port=9222`
+ `ssh -L 9222:localhost:9222`) y mirás por DevTools.

Examples:
```bash
scripts/run-visible.sh uv run python scripts/scrape_bus_alternatives.py
scripts/run-local.sh   npx playwright test --headed
```

Equivalent manual form (if you don't use the helpers):
```bash
DISPLAY=localhost:99 <your command...>   # remoto (noVNC)
DISPLAY=:0           <your command...>   # local (ajustá el número si no es :0)
```

**Solo en modo remoto**, decile al usuario dónde mirar (en local lo ve directo):
```
http://remoteclaude:6080/vnc.html?autoconnect=1&resize=remote
```
(or `http://<TS_HOSTNAME>:6080/...` if the node name differs). Always surface this
URL in remote mode — the user can only see the browser if they open it.

## Making the browser actually headed

`DISPLAY` only provides the screen; the automation still must launch non-headless:

- **Python Playwright**: `p.chromium.launch(headless=False)`
- **Playwright test runner**: `npx playwright test --headed`
- **nodriver / Selenium / Puppeteer**: launch in their non-headless mode; they pick
  up `DISPLAY` automatically.

For anti-bot realism a larger screen helps; the display defaults to 1360x768. To run
at 1920x1080, recreate the display container with `SCREEN_GEOMETRY=1920x1080x24`
(see the repo README / .env).

## Composing with `webapp-testing`

Let `webapp-testing` own the Playwright mechanics (server lifecycle via
`with_server.py`, selector discovery, waits). This skill changes only two things when
the user wants to watch: launch with `headless=False` and wrap the run with
`run-visible.sh` (or set `DISPLAY=localhost:99`). Don't duplicate its logic.

## Notes

- The X port (`6099`) is bound to the host's localhost only (X is insecure); noVNC
  (`6080`) is reachable over the tailnet. The user interacts (click/scroll/type) with
  the live browser through noVNC.
- If the helper reports the display is down, bring it up from the repo:
  `docker compose up -d display`.
- Native OpenGL apps (e.g. Kivy) don't render over this remote X; browsers do
  (Chromium uses `--disable-gpu` software rendering, which Playwright headed applies).
