"""Regresión de los scripts del plugin que se arreglaron sin dejar test.

Los bugs de la revisión integral (sección 3) se corrigieron en su momento, pero salvo
setup-host y el STT ninguno quedó cubierto: un refactor podía revivirlos en silencio.
Cada test acá reproduce el bug original y falla con el código viejo.
"""

import http.server
import json
import os
import shutil
import subprocess
import threading
from pathlib import Path

import pytest

RAIZ = Path(__file__).resolve().parents[2]
SHOW = RAIZ / "plugins/remotemarvin/skills/headed-browser/scripts/marvin-show.sh"
CHECK = RAIZ / "plugins/remotemarvin/scripts/check-version.sh"


# ---------------------------------------------------------------- marvin-show


class _Captura(http.server.BaseHTTPRequestHandler):
    """Render-daemon de mentira: acepta todo y anota qué le pidieron."""

    pedidos: list[str] = []

    def do_GET(self):  # noqa: N802 (nombre que exige BaseHTTPRequestHandler)
        self.pedidos.append(self.path)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_PUT(self):  # noqa: N802
        self.pedidos.append(self.path)
        largo = int(self.headers.get("Content-Length", 0))
        self.rfile.read(largo)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *_):  # silencio en la salida del test
        pass


@pytest.fixture()
def daemon_falso():
    _Captura.pedidos = []
    srv = http.server.HTTPServer(("127.0.0.1", 0), _Captura)
    hilo = threading.Thread(target=srv.serve_forever, daemon=True)
    hilo.start()
    yield srv.server_address[1], _Captura.pedidos
    srv.shutdown()


def _correr_show(puerto, tmp_path, *args):
    env = dict(os.environ, MARVIN_RENDER_PORT=str(puerto), HOME=str(tmp_path))
    return subprocess.run(
        ["bash", str(SHOW), *args], env=env, capture_output=True, text=True, timeout=30
    )


def test_show_no_corta_la_url_en_el_ampersand(daemon_falso, tmp_path):
    """El bug original: sólo se escapaban espacios, así que ?a=1&b=2 llegaba hasta el

    primer `&` y el resto lo interpretaba el shell/query como otra cosa. El fix es
    --data-urlencode; esto lo clava: la URL tiene que llegar ENTERA al daemon.
    """
    puerto, pedidos = daemon_falso
    url = "https://example.com/doc?a=1&b=dos tres&c=3"
    r = _correr_show(puerto, tmp_path, url)
    assert r.returncode == 0, r.stderr
    abiertos = [p for p in pedidos if p.startswith("/open")]
    assert len(abiertos) == 1
    # urlencodeada completa: & como %26, espacio como %20/+, nada truncado
    assert "b%3Ddos" in abiertos[0].replace("+", "%20").replace("%3d", "%3D")
    assert "c%3D3" in abiertos[0].replace("%3d", "%3D")


def test_show_sube_un_archivo_con_espacio_en_el_nombre(daemon_falso, tmp_path):
    """Bug encontrado POR este test: el basename iba sin normalizar a la URL, y un nombre

    con espacio moría en "curl: (3) bad URL" — y aunque pasara, el daemon lo rechaza por
    whitelist. El cliente normaliza al contrato del server: espacio → guión bajo.
    """
    puerto, pedidos = daemon_falso
    doc = tmp_path / "informe final.html"
    doc.write_text("<h1>hola</h1>")
    r = _correr_show(puerto, tmp_path, str(doc))
    assert r.returncode == 0, r.stderr
    assert "/file/informe_final.html" in pedidos


def test_show_nombre_con_acentos_normaliza_y_sube(daemon_falso, tmp_path):
    """Los caracteres fuera de la whitelist se descartan, no abortan: "ñandú ñoño.html"

    queda "and_oo.html" y el archivo viaja igual. (Premisa corregida: mi primera versión
    asumía que esto era innormalizable, y no — sobrevive de sobra.)
    """
    puerto, pedidos = daemon_falso
    doc = tmp_path / "ñandú ñoño.html"
    doc.write_text("x")
    r = _correr_show(puerto, tmp_path, str(doc))
    assert r.returncode == 0, r.stderr
    assert "/file/and_oo.html" in pedidos


def test_show_nombre_innormalizable_avisa_claro(daemon_falso, tmp_path):
    """Si tras normalizar no queda un nombre que arranque alfanumérico (acá: ".html"),

    se avisa con el nombre original en vez del críptico "curl: (3) bad URL" de antes.
    """
    puerto, _ = daemon_falso
    doc = tmp_path / "ñ.html"
    doc.write_text("x")
    r = _correr_show(puerto, tmp_path, str(doc))
    assert r.returncode == 2
    assert "normalizar" in r.stderr


def test_show_sin_daemon_falla_con_mensaje(tmp_path):
    env = dict(os.environ, MARVIN_RENDER_PORT="1", HOME=str(tmp_path))  # nadie escucha
    r = subprocess.run(
        ["bash", str(SHOW), "https://example.com"],
        env=env, capture_output=True, text=True, timeout=30,
    )
    assert r.returncode == 1
    assert "No llego al render" in r.stderr


# ---------------------------------------------------------------- check-version


def _git(*args, cwd):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


@pytest.fixture()
def repos(tmp_path):
    """Un origin bare y un clone con el plugin adentro, versión 1.9.0."""
    origen = tmp_path / "origen.git"
    subprocess.run(["git", "init", "--bare", "-q", str(origen)], check=True)

    trabajo = tmp_path / "trabajo"
    subprocess.run(["git", "clone", "-q", str(origen), str(trabajo)], check=True)
    _git("config", "user.email", "t@t", cwd=trabajo)
    _git("config", "user.name", "t", cwd=trabajo)

    pj = trabajo / "plugins/remotemarvin/.claude-plugin/plugin.json"
    pj.parent.mkdir(parents=True)
    pj.write_text(json.dumps({"name": "remotemarvin", "version": "1.9.0"}))
    destino = trabajo / "plugins/remotemarvin/scripts/check-version.sh"
    destino.parent.mkdir(parents=True)
    shutil.copy(CHECK, destino)
    _git("add", "-A", cwd=trabajo)
    _git("commit", "-q", "-m", "base", cwd=trabajo)
    _git("push", "-q", "origin", "HEAD", cwd=trabajo)
    return trabajo, pj


def _correr_check(repo):
    # stdout NO es TTY acá, o sea el modo hook: tiene que callar salvo desactualizado.
    return subprocess.run(
        ["bash", str(repo / "plugins/remotemarvin/scripts/check-version.sh")],
        capture_output=True, text=True, timeout=30,
    )


def test_al_dia_calla_bajo_el_hook(repos):
    trabajo, _ = repos
    r = _correr_check(trabajo)
    assert r.returncode == 0
    assert r.stdout == ""


def test_remoto_mas_nuevo_avisa_y_ordena_por_version_no_por_string(repos):
    """El bug original: comparar como strings daba "1.10" < "1.9" y avisaba al revés.

    Acá el remoto pasa a 1.10.0 (menor como string, mayor como versión): tiene que avisar.
    """
    trabajo, pj = repos
    pj.write_text(json.dumps({"name": "remotemarvin", "version": "1.10.0"}))
    _git("commit", "-aqm", "v1.10", cwd=trabajo)
    _git("push", "-q", "origin", "HEAD", cwd=trabajo)
    _git("reset", "-q", "--hard", "HEAD~1", cwd=trabajo)  # local queda en 1.9.0

    r = _correr_check(trabajo)
    assert r.returncode == 0
    assert "DESACTUALIZADO" in r.stdout
    assert "1.10.0" in r.stdout


def test_local_adelantado_no_molesta(repos):
    """Tener la copia local más nueva que origin es lo normal en desarrollo: silencio."""
    trabajo, pj = repos
    pj.write_text(json.dumps({"name": "remotemarvin", "version": "1.11.0"}))
    _git("commit", "-aqm", "wip", cwd=trabajo)  # sin push: local > remoto

    r = _correr_check(trabajo)
    assert r.returncode == 0
    assert "DESACTUALIZADO" not in r.stdout


def test_sin_plugin_json_calla_para_siempre(repos):
    """El bug original: sin plugin.json avisaba "desactualizado" en CADA sesión."""
    trabajo, pj = repos
    pj.unlink()
    _git("commit", "-aqm", "sin pj", cwd=trabajo)

    r = _correr_check(trabajo)
    assert r.returncode == 0
    assert r.stdout == ""
