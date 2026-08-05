"""Tests del render-daemon (scripts/marvin-render.py).

Cubren el endurecimiento de la revisión: este daemon queda alcanzable desde los servers
habilitados vía reverse tunnel, así que sus validaciones son frontera de seguridad.

    uv run --with pytest pytest test/host/ -q
"""
import http.client
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
DAEMON = REPO / "scripts" / "marvin-render.py"


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def daemon(tmp_path_factory):
    """Levanta el daemon real con un navegador falso y un directorio de docs propio."""
    port = _free_port()
    docs = tmp_path_factory.mktemp("docs")
    env = dict(
        os.environ,
        MARVIN_RENDER_PORT=str(port),
        MARVIN_BROWSER_CMD="true %U",      # no abrir navegadores de verdad
        REMOTEMARVIN_DOCS=str(docs),
        HOME=str(tmp_path_factory.mktemp("home")),   # aísla el token
    )
    proc = subprocess.Popen([sys.executable, str(DAEMON)], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    for _ in range(50):
        try:
            with socket.create_connection(("127.0.0.1", port), 0.2):
                break
        except OSError:
            time.sleep(0.1)
    else:
        proc.kill()
        pytest.fail("el daemon no levantó")
    yield port, docs, env
    proc.terminate()
    proc.wait(timeout=5)


def req(port, method, path, body=None, headers=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c.request(method, path, body=body, headers=headers or {})
    r = c.getresponse()
    r.read()
    c.close()
    return r.status


# --- /open: sólo http(s) -----------------------------------------------------------

@pytest.mark.parametrize("url", [
    "file:///etc/passwd",           # leer archivos locales del host
    "javascript:alert(1)",
    "data:text/html,<h1>x</h1>",
    "",                             # sin url
])
def test_open_rechaza_esquemas_peligrosos(daemon, url):
    port, _, _ = daemon
    assert req(port, "GET", f"/open?url={url}") == 400


@pytest.mark.parametrize("url", ["http://example.com", "https://example.com/a?b=1"])
def test_open_acepta_http(daemon, url):
    port, _, _ = daemon
    import urllib.parse
    assert req(port, "GET", "/open?url=" + urllib.parse.quote(url, safe="")) == 200


# --- nombres de archivo ------------------------------------------------------------

@pytest.mark.parametrize("name", [
    "../../evil.txt",      # traversal: se RECHAZA, no se sanea callado
    "a/b.txt",
    ".bashrc",             # oculto
    "run.sh",              # extensión no permitida
    "x" * 200 + ".txt",    # demasiado largo
    "",
])
def test_doc_rechaza_nombres_peligrosos(daemon, name):
    port, docs, _ = daemon
    status = req(port, "PUT", "/doc", body=b"x", headers={"X-Filename": name})
    assert status == 400
    assert list(docs.iterdir()) == [] or all(p.name == "informe.txt" for p in docs.iterdir())


def test_doc_acepta_nombre_valido(daemon):
    port, docs, _ = daemon
    assert req(port, "PUT", "/doc", body=b"hola", headers={"X-Filename": "informe.txt"}) == 200
    assert (docs / "informe.txt").read_bytes() == b"hola"


# --- tamaño ------------------------------------------------------------------------

def test_pide_content_length(daemon):
    port, _, _ = daemon
    # sin Content-Length (chunked) -> 411
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c.putrequest("PUT", "/doc")
    c.putheader("X-Filename", "x.txt")
    c.putheader("Transfer-Encoding", "chunked")
    c.endheaders()
    c.send(b"1\r\nx\r\n0\r\n\r\n")
    assert c.getresponse().status == 411
    c.close()


def test_rechaza_cuerpos_gigantes(daemon):
    port, _, _ = daemon
    # se declara un tamaño enorme: debe cortar ANTES de leerlo
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c.putrequest("PUT", "/doc")
    c.putheader("X-Filename", "big.txt")
    c.putheader("Content-Length", str(50 * 1024 * 1024))
    c.endheaders()
    assert c.getresponse().status == 413
    c.close()


# --- token opcional ----------------------------------------------------------------

def test_token_cuando_existe_el_archivo(daemon):
    port, _, env = daemon
    tokdir = Path(env["HOME"]) / ".config" / "marvin"
    tokdir.mkdir(parents=True, exist_ok=True)
    tok = tokdir / "render-token"
    tok.write_text("s3cr3t")
    try:
        assert req(port, "GET", "/open?url=https%3A%2F%2Fx.com") == 403
        assert req(port, "GET", "/open?url=https%3A%2F%2Fx.com",
                   headers={"X-Marvin-Token": "mal"}) == 403
        assert req(port, "GET", "/open?url=https%3A%2F%2Fx.com",
                   headers={"X-Marvin-Token": "s3cr3t"}) == 200
    finally:
        tok.unlink()   # el resto de los tests corren sin token
