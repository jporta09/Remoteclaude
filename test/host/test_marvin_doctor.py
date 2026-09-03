"""Tests de scripts/marvin-doctor: el bloque del expiry del nodo Tailscale del HOST.

El doctor corre en un sandbox (HOME vacío, PATH con un `docker` de mentira que devuelve el JSON
de `tailscale status --json` de cada caso) y jq REAL. Lo que rompía (5ª pasada, SRE-5-2 /
DEVOPS-5p-3): con jq fallando o con RFC3339Nano decía "✅ vence en ? día(s) (con margen)" — un
falso OK — y con el nodo YA vencido (días negativos) no lo decía. Y el filtro tiene que ser el
mismo que embebe la app (test_expiry_filter_parity.py).
"""
import datetime as dt
import os
import shutil
import subprocess

import pytest

AQUI = os.path.dirname(__file__)
DOCTOR = os.path.abspath(os.path.join(AQUI, "..", "..", "scripts", "marvin-doctor"))

pytestmark = pytest.mark.skipif(shutil.which("jq") is None, reason="necesita jq")


def _iso(delta: dt.timedelta, nanos: bool = False) -> str:
    t = dt.datetime.now(dt.timezone.utc) + delta
    s = t.strftime("%Y-%m-%dT%H:%M:%S")
    return s + (".123456789Z" if nanos else "Z")


def correr_doctor(tmp_path, docker_stdout: str = "", docker_rc: int = 0, jq_roto: bool = False):
    """Corre el doctor con un docker falso que imprime `docker_stdout` ante `exec ... status --json`."""
    home = tmp_path / "home"
    home.mkdir()
    binn = tmp_path / "bin"
    binn.mkdir()
    (binn / "docker").write_text(
        "#!/usr/bin/env bash\n"
        f"printf '%s' '{docker_stdout}'\n"
        f"exit {docker_rc}\n"
    )
    (binn / "docker").chmod(0o755)
    if jq_roto:
        (binn / "jq").write_text("#!/usr/bin/env bash\necho 'jq: error' >&2\nexit 5\n")
        (binn / "jq").chmod(0o755)
    env = dict(os.environ, HOME=str(home), PATH=f"{binn}:{os.environ['PATH']}")
    env.pop("CLAUDE_CONFIG_DIR", None)
    r = subprocess.run(["bash", DOCTOR], env=env, capture_output=True, text=True, check=False)
    return r.stdout


def _status(**kw) -> str:
    """JSON mínimo de `tailscale status --json` con Self opcional."""
    import json
    return json.dumps(kw)


def test_nodo_con_tag_no_vence(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="Running",
                                          Self={"Tags": ["tag:remotemarvin"], "KeyExpiry": _iso(dt.timedelta(days=90))}))
    assert "no vence" in out


def test_keyexpiry_null_no_vence(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="Running", Self={"KeyExpiry": None}))
    assert "no vence" in out


def test_vence_pronto_avisa_en_amarillo(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="Running",
                                          Self={"KeyExpiry": _iso(dt.timedelta(days=10, hours=1))}))
    assert "🟡" in out and "vence en 10 día(s)" in out


def test_con_margen_dice_los_dias_reales(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="Running",
                                          Self={"KeyExpiry": _iso(dt.timedelta(days=30, hours=1))}))
    assert "✅" in out and "vence en 30 día(s) (con margen)" in out


def test_rfc3339nano_se_parsea_y_no_da_falso_ok(tmp_path):
    """jq 1.7 rechaza los nanosegundos en fromdateiso8601: antes caía a '? día(s) (con margen)'."""
    out = correr_doctor(tmp_path, _status(BackendState="Running",
                                          Self={"KeyExpiry": _iso(dt.timedelta(days=30, hours=1), nanos=True)}))
    assert "vence en 30 día(s) (con margen)" in out
    assert "?" not in out.split("Nodo Tailscale del host:")[1]


def test_nodo_ya_vencido_lo_dice_en_rojo(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="Running",
                                          Self={"KeyExpiry": _iso(dt.timedelta(days=-4, hours=1))}))
    # -3,96 días -> floor = -4 -> "hace 4"
    assert "❌" in out and "YA VENCIÓ hace 4 día(s)" in out


def test_needslogin_es_vencido_o_revocado(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="NeedsLogin",
                                          Self={"KeyExpiry": _iso(dt.timedelta(days=30))}))
    assert "❌" in out and "NO está Running" in out and "NeedsLogin" in out


def test_sin_self_no_es_no_vence(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="Running"))
    assert "❌" in out and "no trae Self" in out
    assert "no vence" not in out


def test_jq_roto_nunca_dice_con_margen(tmp_path):
    out = correr_doctor(tmp_path, _status(BackendState="Running",
                                          Self={"KeyExpiry": _iso(dt.timedelta(days=30))}), jq_roto=True)
    bloque = out.split("Nodo Tailscale del host:")[1]
    assert "con margen" not in bloque and "no vence" not in bloque
    assert "⚪" in bloque or "❌" in bloque


def test_docker_falla_lo_dice_sin_inventar(tmp_path):
    out = correr_doctor(tmp_path, "", docker_rc=1)
    bloque = out.split("Nodo Tailscale del host:")[1]
    assert "no pude leer el contenedor" in bloque
    assert "con margen" not in bloque and "no vence" not in bloque
