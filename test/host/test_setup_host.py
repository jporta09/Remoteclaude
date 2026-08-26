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


# --- verificación del sshd (modo --sin-sudo) ------------------------------------------

def ssh_falso(tmp_path, respuesta: str):
    """ssh de mentira que responde como lo haría un servidor con esos métodos."""
    binn = tmp_path / "bin"
    binn.mkdir(exist_ok=True)
    (binn / "ssh").write_text(f"#!/usr/bin/env bash\n>&2 echo '{respuesta}'\nexit 255\n")
    (binn / "ssh").chmod(0o755)
    return {"PATH": f"{binn}:{os.environ['PATH']}"}


def test_detecta_un_sshd_que_acepta_contraseñas(tmp_path):
    """El riesgo del modo --sin-sudo: se saltea el endurecimiento de sshd, así que si el
    host acepta passwords hay que decirlo, no dejarlo en un 'verificá a mano'."""
    env = ssh_falso(tmp_path, "nadie@127.0.0.1: Permission denied (publickey,password).")
    r = correr('sshd_acepta_password && echo ACEPTA || echo "rc=$?"', tmp_path, env)
    assert "ACEPTA" in r.stdout, r.stdout + r.stderr


def test_keyboard_interactive_tambien_cuenta_como_password(tmp_path):
    """Es la puerta de atrás clásica: PasswordAuthentication no, pero PAM sigue pidiendo
    contraseña por keyboard-interactive."""
    env = ssh_falso(tmp_path, "Permission denied (publickey,keyboard-interactive).")
    r = correr('sshd_acepta_password && echo ACEPTA || echo "rc=$?"', tmp_path, env)
    assert "ACEPTA" in r.stdout, r.stdout + r.stderr


def test_un_sshd_solo_clave_no_dispara_la_alerta(tmp_path):
    env = ssh_falso(tmp_path, "nadie@127.0.0.1: Permission denied (publickey).")
    r = correr('sshd_acepta_password && echo ACEPTA || echo "rc=$?"', tmp_path, env)
    assert "rc=1" in r.stdout, r.stdout + r.stderr


def test_si_no_se_puede_verificar_se_distingue_de_estar_seguro(tmp_path):
    """Sin respuesta del servidor NO se puede concluir 'solo-clave': tiene que ser un
    tercer estado, o el mensaje mentiría."""
    env = ssh_falso(tmp_path, "ssh: connect to host 127.0.0.1 port 22: Connection refused")
    r = correr('sshd_acepta_password && echo ACEPTA || echo "rc=$?"', tmp_path, env)
    assert "rc=2" in r.stdout, r.stdout + r.stderr


def test_no_confunde_publickey_con_password_por_substring(tmp_path):
    """'password' no puede matchear dentro de otra palabra."""
    env = ssh_falso(tmp_path, "Permission denied (publickey,gssapi-with-mic).")
    r = correr('sshd_acepta_password && echo ACEPTA || echo "rc=$?"', tmp_path, env)
    assert "rc=1" in r.stdout, r.stdout + r.stderr


# --- escribir_marker_setup ------------------------------------------------------------

def test_el_marker_de_setup_se_escribe_con_fecha(tmp_path):
    r = correr(f'escribir_marker_setup "{tmp_path}"', tmp_path)
    assert r.returncode == 0, r.stderr
    marker = tmp_path / ".config" / "marvin" / "setup-ok"
    assert marker.exists(), "el setup exitoso debe dejar el marker que la app verifica"
    assert marker.read_text().startswith("setup-host ")


def test_setup_host_exige_uv(tmp_path):
    """Sin uv el setup FALLA (exit 1): un host 'configurado a medias' (sin dictado) era
    exactamente el estado que el gate de conexión de la app quiere impedir."""
    script = os.path.join(os.path.dirname(LIB), "setup-host.sh")
    with open(script) as f:
        contenido = f.read()
    assert 'exit 1' in contenido.split('falta uv')[1][:400], \
        "el bloque de uv faltante tiene que salir con error, no saltear el STT"
    assert "SALTAR_STT" not in contenido, "no debe quedar el modo 'saltear STT en silencio'"
