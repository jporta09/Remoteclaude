"""Paridad del filtro jq del expiry del nodo del HOST.

Hay un solo filtro canónico (scripts/lib/expiry-host.jq) y dos copias embebidas: marvin-doctor
(bash, comillas simples) y la app (SshTerminalSession.kt, const EXPIRY_HOST_JQ). Hasta la 5ª
pasada eran dos implementaciones distintas del mismo cálculo y divergían (el doctor miraba
`tagged`/`exp` por separado; la app otra cosa). Este test exige que las tres sean IDÉNTICAS y
corre el canónico contra los casos que rompían (nanosegundos, ya vencido, NeedsLogin, sin Self).
"""
import datetime as dt
import json
import os
import re
import shutil
import subprocess

import pytest

AQUI = os.path.dirname(__file__)
RAIZ = os.path.abspath(os.path.join(AQUI, "..", ".."))
CANONICO = os.path.join(RAIZ, "scripts", "lib", "expiry-host.jq")
DOCTOR = os.path.join(RAIZ, "scripts", "marvin-doctor")
KOTLIN = os.path.join(RAIZ, "android", "app", "src", "main", "java", "com", "remoteclaude", "app",
                      "SshTerminalSession.kt")


def filtro_canonico() -> str:
    with open(CANONICO, encoding="utf-8") as f:
        lineas = [l.rstrip("\n") for l in f if l.strip() and not l.startswith("#")]
    assert len(lineas) == 1, "el filtro canónico tiene que ser UNA línea (las copias lo embeben literal)"
    return lineas[0]


def test_el_doctor_embebe_el_filtro_canonico():
    with open(DOCTOR, encoding="utf-8") as f:
        doctor = f.read()
    assert filtro_canonico() in doctor, "marvin-doctor no embebe el filtro canónico literalmente"


def test_la_app_embebe_el_filtro_canonico():
    with open(KOTLIN, encoding="utf-8") as f:
        kt = f.read()
    m = re.search(r'const val EXPIRY_HOST_JQ = "(.*)"\s*$', kt, re.MULTILINE)
    assert m, "no encontré la const EXPIRY_HOST_JQ en SshTerminalSession.kt"
    # Des-escapar el literal Kotlin: \\ -> \ y \" -> "
    embebido = m.group(1).replace('\\\\', '\\').replace('\\"', '"')
    assert embebido == filtro_canonico()


# --- el canónico contra jq real -------------------------------------------------------

def _jq(status: dict) -> str:
    r = subprocess.run(["jq", "-r", filtro_canonico()], input=json.dumps(status),
                       capture_output=True, text=True, check=False)
    assert r.returncode == 0, r.stderr
    return r.stdout.strip()


def _iso(delta: dt.timedelta, nanos: bool = False) -> str:
    t = dt.datetime.now(dt.timezone.utc) + delta
    return t.strftime("%Y-%m-%dT%H:%M:%S") + (".987654321Z" if nanos else "Z")


necesita_jq = pytest.mark.skipif(shutil.which("jq") is None, reason="necesita jq")


@necesita_jq
def test_tokens_del_filtro():
    assert _jq({"BackendState": "Running", "Self": {"Tags": ["tag:x"], "KeyExpiry": _iso(dt.timedelta(days=5))}}) == "TAGGED"
    assert _jq({"BackendState": "Running", "Self": {"KeyExpiry": None}}) == "NOEXP"
    assert _jq({"BackendState": "Running"}) == "SINSELF"
    assert _jq({"BackendState": "NeedsLogin", "Self": {"KeyExpiry": _iso(dt.timedelta(days=5))}}) == "NORUN"
    # Sin BackendState (un JSON parcial, como el shim de prueba) NO es alarma: se mira el expiry igual.
    assert _jq({"Self": {"KeyExpiry": _iso(dt.timedelta(days=30, hours=1))}}) == "30"


@necesita_jq
def test_dias_positivos_negativos_y_nanosegundos():
    assert _jq({"BackendState": "Running", "Self": {"KeyExpiry": _iso(dt.timedelta(days=30, hours=1))}}) == "30"
    assert _jq({"BackendState": "Running", "Self": {"KeyExpiry": _iso(dt.timedelta(days=-4, hours=-1))}}) == "-5"
    # RFC3339Nano: sin el sub() jq 1.7 falla con "date ... does not match format"
    assert _jq({"BackendState": "Running", "Self": {"KeyExpiry": _iso(dt.timedelta(days=30, hours=1), nanos=True)}}) == "30"
