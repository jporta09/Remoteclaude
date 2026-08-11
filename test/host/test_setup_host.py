"""Tests de las piezas con lógica de setup-host.sh (scripts/setup-host-lib.sh).

El resto del script son apt-get y systemctl contra el host real, que no se pueden ejercitar.
Lo que sí se prueba acá es lo que rompía en silencio: bloques que se duplicaban al correr el
setup dos veces, config del usuario pisada, y units reescritas que seguían corriendo con el
código viejo.
"""
import os
import subprocess
import textwrap

LIB = os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "setup-host-lib.sh")
LIB = os.path.abspath(LIB)


def correr(script, cwd, env=None):
    """Ejecuta bash con la librería ya cargada."""
    entorno = dict(os.environ, HOME=str(cwd))
    entorno.update(env or {})
    return subprocess.run(
        ["bash", "-euo", "pipefail", "-c", f'. "{LIB}"\n{script}'],
        cwd=cwd, env=entorno, capture_output=True, text=True,
        check=False,   # los tests asertan sobre returncode
    )


# --- bloque_sentinelas ----------------------------------------------------------------

def test_agrega_el_bloque_a_un_archivo_nuevo(tmp_path):
    r = correr(f'bloque_sentinelas "{tmp_path}/f.conf" "# >>>" "# <<<" <<< "set -g mouse on"',
               tmp_path)
    assert r.returncode == 0, r.stderr
    assert (tmp_path / "f.conf").read_text() == "# >>>\nset -g mouse on\n# <<<\n"


def test_correrlo_dos_veces_deja_un_solo_bloque(tmp_path):
    """Idempotencia: el setup se corre de nuevo en cada actualización."""
    script = f'''
    bloque_sentinelas "{tmp_path}/f.conf" "# >>>" "# <<<" <<< "primera"
    bloque_sentinelas "{tmp_path}/f.conf" "# >>>" "# <<<" <<< "segunda"
    '''
    r = correr(script, tmp_path)
    assert r.returncode == 0, r.stderr
    contenido = (tmp_path / "f.conf").read_text()
    assert contenido.count("# >>>") == 1
    assert "segunda" in contenido
    # La versión vieja NO puede quedar: era el bug del append con grep como guarda.
    assert "primera" not in contenido


def test_respeta_lo_que_el_usuario_ya_tenia(tmp_path):
    f = tmp_path / "tmux.conf"
    f.write_text("# mi config\nset -g status-bg blue\n")
    correr(f'bloque_sentinelas "{f}" "# >>>" "# <<<" <<< "set -g mouse on"', tmp_path)
    contenido = f.read_text()
    assert "set -g status-bg blue" in contenido, "pisó la config del usuario"
    assert contenido.index("mi config") < contenido.index("# >>>"), "el bloque va al final"


def test_lo_que_hay_despues_del_bloque_sobrevive_a_la_reescritura(tmp_path):
    f = tmp_path / "ssh_config"
    f.write_text("Host uno\n  User juan\n")
    correr(f'bloque_sentinelas "{f}" "# >>>" "# <<<" <<< "vieja"', tmp_path)
    f.write_text(f.read_text() + "Host dos\n  User ana\n")
    correr(f'bloque_sentinelas "{f}" "# >>>" "# <<<" <<< "nueva"', tmp_path)
    contenido = f.read_text()
    assert "Host uno" in contenido and "Host dos" in contenido
    assert "vieja" not in contenido and "nueva" in contenido


def test_sentinelas_con_caracteres_especiales(tmp_path):
    """Las sentinelas reales llevan >>> y <<<, que sed interpreta."""
    f = tmp_path / "f.conf"
    begin, end = "# >>> RemoteMarvin >>>", "# <<< RemoteMarvin <<<"
    correr(f'bloque_sentinelas "{f}" "{begin}" "{end}" <<< "uno"', tmp_path)
    r = correr(f'bloque_sentinelas "{f}" "{begin}" "{end}" <<< "dos"', tmp_path)
    assert r.returncode == 0, r.stderr
    assert f.read_text().count(begin) == 1
    assert "uno" not in f.read_text()


# --- escribir_unidad ------------------------------------------------------------------

def systemctl_falso(tmp_path, activo: bool):
    """systemctl de mentira que registra las llamadas y simula el estado del servicio."""
    binn = tmp_path / "bin"
    binn.mkdir(exist_ok=True)
    (binn / "systemctl").write_text(textwrap.dedent(f"""\
        #!/usr/bin/env bash
        echo "$@" >> "{tmp_path}/llamadas.log"
        [[ "$*" == *is-active* ]] && exit {0 if activo else 3}
        exit 0
    """))
    (binn / "systemctl").chmod(0o755)
    return {"PATH": f"{binn}:{os.environ['PATH']}"}


def test_escribe_la_unit_y_recarga(tmp_path):
    env = systemctl_falso(tmp_path, activo=False)
    env["SYSTEMD_USER_DIR"] = str(tmp_path / "units")
    r = correr('escribir_unidad demo.service <<< "[Service]"', tmp_path, env)
    assert r.returncode == 0, r.stderr
    assert (tmp_path / "units" / "demo.service").read_text() == "[Service]\n"
    assert "daemon-reload" in (tmp_path / "llamadas.log").read_text()


def test_reinicia_si_el_servicio_estaba_corriendo(tmp_path):
    """El bug: se reescribía la unit y el daemon seguía con el código viejo."""
    env = systemctl_falso(tmp_path, activo=True)
    env["SYSTEMD_USER_DIR"] = str(tmp_path / "units")
    r = correr('escribir_unidad demo.service <<< "[Service]"', tmp_path, env)
    assert r.returncode == 0, r.stderr
    assert "restart demo.service" in (tmp_path / "llamadas.log").read_text()


def test_no_arranca_un_servicio_que_estaba_apagado(tmp_path):
    """Los daemons son a demanda: el setup no tiene que prenderlos."""
    env = systemctl_falso(tmp_path, activo=False)
    env["SYSTEMD_USER_DIR"] = str(tmp_path / "units")
    correr('escribir_unidad demo.service <<< "[Service]"', tmp_path, env)
    llamadas = (tmp_path / "llamadas.log").read_text()
    assert "restart" not in llamadas
    assert "start demo.service" not in llamadas
