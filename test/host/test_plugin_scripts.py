"""Regresión de los scripts del plugin que se arreglaron sin dejar test.

Los bugs de la revisión integral (sección 3) se corrigieron en su momento, pero salvo
setup-host y el STT ninguno quedó cubierto: un refactor podía revivirlos en silencio.
Cada test acá reproduce el bug original y falla con el código viejo.
"""

import http.server
import json
import os
import re
import shutil
import subprocess
import threading
from pathlib import Path
from typing import ClassVar

import pytest

RAIZ = Path(__file__).resolve().parents[2]
SHOW = RAIZ / "plugins/remotemarvin/skills/headed-browser/scripts/marvin-show.sh"
SHARE = RAIZ / "plugins/remotemarvin/skills/share-doc/scripts/marvin-share.sh"
CHECK = RAIZ / "plugins/remotemarvin/scripts/check-version.sh"
NOTIFY = RAIZ / "plugins/remotemarvin/scripts/marvin-notify.sh"
NOTIFY_DECISION = RAIZ / "plugins/remotemarvin/scripts/marvin-notify-decision.sh"
FLAG_SUBIDOS = RAIZ / "plugins/remotemarvin/scripts/flag-subidos-context.sh"

CONTROL = re.compile(r"[\x00-\x1f\x7f]")


def _inseguro_para_la_app(nombre: str) -> bool:
    """Espejo de nombreDeDocInseguro (RemoteControl.kt): control chars, comilla simple,
    barra o vacío. El consumidor lo marca no-soportado; el productor no debe crearlo.
    """
    return not nombre or "'" in nombre or "/" in nombre or bool(CONTROL.search(nombre))


# ---------------------------------------------------------------- marvin-show


class _Captura(http.server.BaseHTTPRequestHandler):
    """Render-daemon de mentira: acepta todo y anota qué le pidieron."""

    # ClassVar a propósito: el fixture la resetea por test; compartirla entre
    # instancias del handler es justamente el mecanismo de captura.
    pedidos: ClassVar[list[str]] = []

    def do_GET(self):  # nombre que exige BaseHTTPRequestHandler
        self.pedidos.append(self.path)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_PUT(self):
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
        ["bash", str(SHOW), *args], env=env, capture_output=True, text=True, timeout=30,
        check=False,
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
        env=env, capture_output=True, text=True, timeout=30, check=False,
    )
    assert r.returncode == 1
    assert "No llego al render" in r.stderr


# ---------------------------------------------------------------- marvin-share


def _correr_share(tmp_path, dest, *args):
    # MARVIN_RENDER_PORT=1: nadie escucha -> use_sink=0 -> copia local (la ruta del bug).
    env = dict(
        os.environ, MARVIN_RENDER_PORT="1", HOME=str(tmp_path),
        REMOTEMARVIN_DOCS=str(dest),
    )
    return subprocess.run(
        ["bash", str(SHARE), *args], env=env, capture_output=True, text=True, timeout=30,
        check=False,
    )


def test_share_nombre_hostil_aterriza_saneado(tmp_path):
    """Bug §F.2: `cp -- "$f" "$DEST/"` copiaba con el basename crudo, así que un nombre con

    TAB / comilla / control chars —el mismo que corría los campos de listDocs— nacía en
    ~/RemoteMarvinDocs. Defensa en profundidad del productor: lo que aterriza tiene que ser
    un nombre que el visor considere seguro (sin control chars, sin comilla).
    """
    src = tmp_path / "src"
    src.mkdir()
    dest = tmp_path / "dest"
    # informe<TAB>9<TAB>9<TAB>9<TAB>s'.pdf : TAB (corre columnas) + comilla simple.
    hostil = src / "informe\t9\t9\t9\ts'.pdf"
    hostil.write_text("x")

    r = _correr_share(tmp_path, dest, str(hostil))
    assert r.returncode == 0, r.stderr
    aterrizados = [p.name for p in dest.iterdir()]
    assert len(aterrizados) == 1, aterrizados
    n = aterrizados[0]
    assert not _inseguro_para_la_app(n), f"nombre inseguro creado: {n!r}"
    assert n == "informe_9_9_9_s_.pdf", n


def test_share_nombre_legitimo_se_preserva(tmp_path):
    """No es un rechazo a lo bruto: un nombre legítimo con espacios, acentos y emoji

    sobrevive intacto (el visor de la app los soporta). Sólo se neutraliza lo peligroso.
    """
    src = tmp_path / "src"
    src.mkdir()
    dest = tmp_path / "dest"
    bueno = src / "informe final ñandú 📄.pdf"
    bueno.write_text("y")

    r = _correr_share(tmp_path, dest, str(bueno))
    assert r.returncode == 0, r.stderr
    aterrizados = [p.name for p in dest.iterdir()]
    assert aterrizados == ["informe final ñandú 📄.pdf"], aterrizados


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
        capture_output=True, text=True, timeout=30, check=False,
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


# ---------------------------------------------------------------- marvin-notify
# El hook Notification/permission_prompt appendea una línea JSON que la app lee por un
# canal `tail -F`. Debe ser JSON válido con el shape que el parser de la app espera
# ({"type","message","ts"}), y nunca fallar (el hook no puede romperle el flujo a Claude).


def _correr_notify(payload: str, cfg: Path) -> subprocess.CompletedProcess:
    env = {**os.environ, "XDG_CONFIG_HOME": str(cfg)}
    return subprocess.run(
        ["bash", str(NOTIFY)],
        input=payload,
        capture_output=True,
        text=True,
        env=env,
        check=False,
    )


def test_notify_escribe_json_con_el_shape_que_espera_la_app(tmp_path):
    r = _correr_notify(
        '{"notification_type":"permission_prompt",'
        '"message":"Claude needs your permission to use Bash(rm -rf build)"}',
        tmp_path,
    )
    assert r.returncode == 0
    linea = (tmp_path / "marvin" / "notify.jsonl").read_text().strip()
    obj = json.loads(linea)  # JSON válido
    assert obj["type"] == "permission_prompt"
    # SEGURIDAD (Fase 2, §G): el mensaje es FIJO; el `message` de Claude NO se propaga (era texto
    # atacante-controlado renderizado con autoridad de la app en una notif HIGH). Ver marvin-notify.sh.
    assert obj["message"] == "Claude está esperando una decisión"
    assert "rm -rf build" not in linea  # el payload del host no aparece en ningún lado
    assert isinstance(obj["ts"], int)


def test_notify_message_hostil_no_se_propaga(tmp_path):
    # Un mensaje hostil del host (comillas, backslashes, contenido inyectado) ni rompe el JSON ni se
    # propaga: se descarta y queda el mensaje fijo.
    r = _correr_notify(
        '{"notification_type":"permission_prompt","message":"raro \\" y \\\\ y \\n <inyección>"}',
        tmp_path,
    )
    assert r.returncode == 0
    linea = (tmp_path / "marvin" / "notify.jsonl").read_text().strip()
    obj = json.loads(linea)
    assert obj["type"] == "permission_prompt"
    assert obj["message"] == "Claude está esperando una decisión"
    assert "inyección" not in linea


def test_notify_stdin_basura_no_falla_y_escribe_linea_segura(tmp_path):
    r = _correr_notify("esto no es json", tmp_path)
    assert r.returncode == 0  # nunca rompe el flujo de Claude
    obj = json.loads((tmp_path / "marvin" / "notify.jsonl").read_text().strip())
    assert obj["type"] == "permission_prompt"


def test_notify_capea_en_200_lineas(tmp_path):
    for i in range(210):
        _correr_notify(f'{{"notification_type":"permission_prompt","message":"n{i}"}}', tmp_path)
    lineas = (tmp_path / "marvin" / "notify.jsonl").read_text().splitlines()
    assert len(lineas) <= 200


# ------------------------------------------------------ marvin-notify-decision
# El hook PreToolUse (matcher ExitPlanMode|AskUserQuestion) cubre lo que el hook Notification NO:
# Claude esperando que apruebes un plan o respondas una pregunta. Emite el MISMO type
# "permission_prompt" que la app ya reacciona, con un mensaje según el tool, y nunca falla.


def _correr_decision(payload: str, cfg: Path) -> subprocess.CompletedProcess:
    env = {**os.environ, "XDG_CONFIG_HOME": str(cfg)}
    return subprocess.run(
        ["bash", str(NOTIFY_DECISION)],
        input=payload,
        capture_output=True,
        text=True,
        env=env,
        check=False,
    )


def test_decision_exitplanmode_avisa_por_el_plan(tmp_path):
    r = _correr_decision('{"tool_name":"ExitPlanMode","tool_input":{}}', tmp_path)
    assert r.returncode == 0
    obj = json.loads((tmp_path / "marvin" / "notify.jsonl").read_text().strip())
    assert obj["type"] == "permission_prompt"  # la app lo reacciona sin cambios
    assert "plan" in obj["message"].lower()
    assert isinstance(obj["ts"], int)


def test_decision_askuserquestion_avisa_por_la_pregunta(tmp_path):
    r = _correr_decision('{"tool_name":"AskUserQuestion","tool_input":{}}', tmp_path)
    assert r.returncode == 0
    obj = json.loads((tmp_path / "marvin" / "notify.jsonl").read_text().strip())
    assert obj["type"] == "permission_prompt"
    assert "pregunta" in obj["message"].lower()


def test_decision_stdin_basura_no_falla(tmp_path):
    r = _correr_decision("no es json", tmp_path)
    assert r.returncode == 0  # nunca rompe el flujo de Claude
    obj = json.loads((tmp_path / "marvin" / "notify.jsonl").read_text().strip())
    assert obj["type"] == "permission_prompt"


# ------------------------------------------------------ flag-subidos-context
# PostToolUse (Read|Bash): recuerda que lo leído de ~/RemoteMarvinDocs/subidos/ es DATO no confiable.
# Debe disparar cuando se LEE el contenido, NO ante cualquier mención de la ruta (antes sobre-disparaba
# con ls/marvin-share/etc.).


def _correr_flag(payload: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["bash", str(FLAG_SUBIDOS)],
        input=payload,
        capture_output=True,
        text=True,
        check=False,
    )


def _dispara(r: subprocess.CompletedProcess) -> bool:
    return "additionalContext" in r.stdout


def test_flag_read_de_subidos_dispara(tmp_path):
    r = _correr_flag('{"tool_name":"Read","tool_input":{"file_path":"/home/x/RemoteMarvinDocs/subidos/foto.png"}}')
    assert r.returncode == 0
    assert _dispara(r)


def test_flag_bash_que_lee_el_contenido_dispara(tmp_path):
    r = _correr_flag('{"tool_name":"Bash","tool_input":{"command":"cat ~/RemoteMarvinDocs/subidos/notas.txt"}}')
    assert r.returncode == 0
    assert _dispara(r)


def test_flag_bash_ls_no_sobre_dispara(tmp_path):
    # ls sólo lista nombres, no lee contenido: no debe disparar el recordatorio.
    r = _correr_flag('{"tool_name":"Bash","tool_input":{"command":"ls -la ~/RemoteMarvinDocs/subidos/"}}')
    assert r.returncode == 0
    assert not _dispara(r)


def test_flag_bash_que_no_toca_subidos_no_dispara(tmp_path):
    r = _correr_flag('{"tool_name":"Bash","tool_input":{"command":"cat /etc/hostname"}}')
    assert r.returncode == 0
    assert not _dispara(r)


# A5-4 (5ª pasada): el recordatorio se perdía en 6 de 10 lecturas reales — la ruta sin barra
# final tras un `cd`, y los extractores que no estaban en la lista (OCR incluido, que era el
# vector original de "imagen con texto"). Cada uno de estos LEE el contenido y tiene que disparar.
@pytest.mark.parametrize("cmd", [
    "cd ~/RemoteMarvinDocs/subidos && cat notas.txt",
    "pdftotext ~/RemoteMarvinDocs/subidos/informe.pdf -",
    "tesseract ~/RemoteMarvinDocs/subidos/captura.png - -l spa",
    "python3 -c \"print(open('/home/x/RemoteMarvinDocs/subidos/a.txt').read())\"",
    "unzip -p ~/RemoteMarvinDocs/subidos/paquete.zip",
    r"find ~/RemoteMarvinDocs/subidos -name '*.txt' -exec cat {} \;",
])
def test_flag_lecturas_que_antes_se_escapaban_disparan(cmd):
    r = _correr_flag(json.dumps({"tool_name": "Bash", "tool_input": {"command": cmd}}))
    assert r.returncode == 0, r.stderr
    assert _dispara(r), cmd


@pytest.mark.parametrize("cmd", [
    "rm ~/RemoteMarvinDocs/subidos/viejo.png",
    "mv ~/RemoteMarvinDocs/subidos/a.txt ~/docs/",
    "marvin-share ~/RemoteMarvinDocs/subidos/salida.pdf",
    "du -sh ~/RemoteMarvinDocs/subidos",
])
def test_flag_operaciones_que_no_leen_siguen_sin_disparar(cmd):
    r = _correr_flag(json.dumps({"tool_name": "Bash", "tool_input": {"command": cmd}}))
    assert r.returncode == 0, r.stderr
    assert not _dispara(r), cmd
