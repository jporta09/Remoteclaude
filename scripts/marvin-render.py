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
import re
import shlex
import subprocess
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MARVIN_RENDER_PORT", "6090"))
DISPLAY_NUM = os.environ.get("MARVIN_DISPLAY_NUM", "99")
DROP = "/tmp/marvin-render"
DOCS = os.environ.get("REMOTEMARVIN_DOCS", os.path.expanduser("~/RemoteMarvinDocs"))
# 0700: en una máquina multiusuario, /tmp es de todos; sin esto otro usuario podía
# pre-crear el directorio y quedarse leyendo lo que renderizamos.
os.makedirs(DROP, mode=0o700, exist_ok=True)
os.chmod(DROP, 0o700)
os.makedirs(DOCS, exist_ok=True)

MAX_BODY = 25 * 1024 * 1024          # tope duro por pedido
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
ALLOWED_EXT = {
    ".html", ".htm", ".md", ".txt", ".pdf", ".png", ".jpg", ".jpeg",
    ".webp", ".gif", ".csv", ".tsv", ".json", ".log", ".yaml", ".yml", ".xml",
}
# Token opcional: si el archivo existe, se exige el header. Se activa solo cuando el
# usuario lo genera (setup-host), así no rompe instalaciones existentes.
TOKEN_FILE = os.path.expanduser("~/.config/marvin/render-token")


def expected_token():
    try:
        with open(TOKEN_FILE) as f:
            return f.read().strip() or None
    except OSError:
        return None


def safe_name(raw: str):
    """Nombre de archivo aceptable, o None.

    Se RECHAZA (en vez de sanear callado) cualquier cosa con separadores de ruta: que
    llegue "../../x.txt" significa cliente roto o malicioso, y quedarnos con el basename
    lo escondería. También se exige forma sana y extensión conocida (nada de .sh/.desktop).
    """
    raw = (raw or "").strip()
    if not raw or "/" in raw or "\\" in raw:
        return None
    name = os.path.basename(raw)
    if not SAFE_NAME.match(name):
        return None
    if os.path.splitext(name)[1].lower() not in ALLOWED_EXT:
        return None
    return name

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

    def log_message(self, *a):  # silencioso (los rechazos sí se loguean)
        pass

    def _reject(self, code, why):
        print(f"[marvin-render] rechazado {self.command} {self.path}: {why}",
              file=sys.stderr, flush=True)
        body = f"error: {why}\n".encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth(self):
        want = expected_token()
        if want is None:
            return True
        if self.headers.get("X-Marvin-Token", "") == want:
            return True
        self._reject(403, "token inválido o ausente")
        return False

    def _read_body(self):
        """Cuerpo con tope. Devuelve None (y responde) si no cumple."""
        raw = self.headers.get("Content-Length")
        if raw is None:
            self._reject(411, "falta Content-Length")
            return None
        try:
            n = int(raw)
        except ValueError:
            self._reject(400, "Content-Length inválido")
            return None
        if n <= 0 or n > MAX_BODY:
            self._reject(413, f"cuerpo de {n} bytes (máximo {MAX_BODY})")
            return None
        return self.rfile.read(n)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/open":
            if not self._auth():
                return
            q = urllib.parse.parse_qs(u.query)
            url = (q.get("url") or [""])[0]
            # SOLO http/https: con file:// (o javascript:) un server remoto podía hacer
            # que TU navegador abriera cualquier archivo local y mostrarlo en el noVNC.
            scheme = urllib.parse.urlparse(url).scheme.lower()
            if not url or scheme not in ("http", "https"):
                self._reject(400, f"esquema no permitido: {scheme or 'vacío'}")
                return
            render(url)
            self._ok(f"render: {url}")
        else:
            self._ok("marvin-render OK")

    def do_PUT(self):
        if not self._auth():
            return
        path = urllib.parse.urlparse(self.path).path
        # /doc -> sólo depositar en RemoteMarvinDocs (visor 📄), sin abrir browser.
        if path == "/doc" or path.startswith("/doc/"):
            name = safe_name(self.headers.get("X-Filename", "") or path[len("/doc/"):])
            if name is None:
                self._reject(400, "nombre de archivo no permitido")
                return
            body = self._read_body()
            if body is None:
                return
            dest = os.path.join(DOCS, name)
            with open(dest, "wb") as f:
                f.write(body)
            self._ok(f"doc: {dest}")
            return
        # /file/<name> -> guardar y renderizar headed (pasar un HTML).
        name = safe_name(path.rsplit("/", 1)[-1])
        if name is None:
            self._reject(400, "nombre de archivo no permitido")
            return
        body = self._read_body()
        if body is None:
            return
        dest = os.path.join(DROP, name)
        with open(dest, "wb") as f:
            f.write(body)
        render("file://" + dest)
        self._ok(f"render: file://{dest}")


if __name__ == "__main__":
    print(f"[marvin-render] escuchando en 127.0.0.1:{PORT} (display :{DISPLAY_NUM})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
