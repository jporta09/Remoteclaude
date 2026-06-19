#!/usr/bin/env python3
"""Daemon de render en el HOST de la app (haviland).

Escucha pedidos "mostrá esta URL / este HTML" y los abre en un navegador HEADED sobre el
display local (-> noVNC -> celu). Lo usan los servers a los que SSH-eás DESDE la app cuando
NO pueden correr el browser (les faltan las libs de sistema): te mandan la URL o el archivo
por el reverse tunnel (puerto 6090) y este host lo renderiza.

  ./scripts/marvin-render.py            # arranca (foreground)
Variables: MARVIN_RENDER_PORT (6090), MARVIN_DISPLAY_NUM (99)

Endpoints:
  GET  /open?url=<URL>        -> abre la URL headed
  PUT  /file/<nombre.html>    -> guarda el body y abre file://... (para "pasar el HTML")
  PUT  /doc  (X-Filename:..)  -> deposita el archivo en ~/RemoteMarvinDocs (visor 📄 del
                                celu); NO lo renderiza. Lo usa marvin-share desde un server
                                remoto para que el doc termine en el host que lee la app.
  GET  /                      -> estado
"""
import os
import shlex
import subprocess
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MARVIN_RENDER_PORT", "6090"))
DISPLAY_NUM = os.environ.get("MARVIN_DISPLAY_NUM", "99")
DROP = "/tmp/marvin-render"
DOCS = os.environ.get("REMOTEMARVIN_DOCS", os.path.expanduser("~/RemoteMarvinDocs"))
os.makedirs(DROP, exist_ok=True)
os.makedirs(DOCS, exist_ok=True)

# Comando para abrir una URL headed y dejarla abierta. Usa Playwright (vía uv) en el display
# local. Se puede override con MARVIN_BROWSER_CMD ("%U" se reemplaza por la URL).
DEFAULT_CMD = (
    "uv run --with playwright python -c "
    "\"import sys,time;from playwright.sync_api import sync_playwright as S;"
    "b=S().start().chromium.launch(headless=False);"
    "b.new_context(no_viewport=True).new_page().goto(sys.argv[1]);"
    "print('abierto',sys.argv[1]);time.sleep(3600)\" %U"
)


def render(url: str) -> None:
    cmd = os.environ.get("MARVIN_BROWSER_CMD", DEFAULT_CMD)
    cmd = cmd.replace("%U", shlex.quote(url))
    env = dict(os.environ, DISPLAY=f"localhost:{DISPLAY_NUM}")
    # Detached, no bloquea el daemon; queda abierto ~1h o hasta que abras otra cosa.
    subprocess.Popen(["bash", "-lc", cmd], env=env,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)


class H(BaseHTTPRequestHandler):
    def _ok(self, msg):
        self.send_response(200); self.end_headers()
        self.wfile.write((msg + "\n").encode())

    def log_message(self, *a):  # silencioso
        pass

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/open":
            q = urllib.parse.parse_qs(u.query)
            url = (q.get("url") or [""])[0]
            if not url:
                self.send_response(400); self.end_headers(); return
            render(url)
            self._ok(f"render: {url}")
        else:
            self._ok("marvin-render OK")

    def do_PUT(self):
        path = urllib.parse.urlparse(self.path).path
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        # /doc -> sólo depositar en RemoteMarvinDocs (visor 📄), sin abrir browser.
        if path == "/doc" or path.startswith("/doc/"):
            name = os.path.basename(
                self.headers.get("X-Filename", "") or path[len("/doc/"):]
            ) or "archivo"
            dest = os.path.join(DOCS, name)
            with open(dest, "wb") as f:
                f.write(body)
            self._ok(f"doc: {dest}")
            return
        # /file/<name> -> guardar y renderizar headed (pasar un HTML).
        name = os.path.basename(path) or "page.html"
        dest = os.path.join(DROP, name)
        with open(dest, "wb") as f:
            f.write(body)
        render("file://" + dest)
        self._ok(f"render: file://{dest}")


if __name__ == "__main__":
    print(f"[marvin-render] escuchando en 127.0.0.1:{PORT} (display :{DISPLAY_NUM})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
