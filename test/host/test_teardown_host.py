"""Tests de teardown-host.sh y de sus piezas en setup-host-lib.sh.

teardown es el par de setup: lo que setup dejó (units de usuario, helpers en ~/.local/bin,
bloques con sentinelas, contenedores) tiene que quedar deshecho, SIN llevarse por delante lo
que no es de RemoteMarvin (el resto de ~/.ssh/config, ~/.tmux.conf, o authorized_keys).
"""
import os
import subprocess

AQUI = os.path.dirname(__file__)
LIB = os.path.abspath(os.path.join(AQUI, "..", "..", "scripts", "setup-host-lib.sh"))
TEARDOWN = os.path.abspath(os.path.join(AQUI, "..", "..", "scripts", "teardown-host.sh"))


def con_lib(script, cwd, env=None):
    """Corre un fragmento con la librería ya cargada."""
    entorno = dict(os.environ, HOME=str(cwd))
    entorno.update(env or {})
    return subprocess.run(
        ["bash", "-euo", "pipefail", "-c", f'. "{LIB}"\n{script}'],
        cwd=cwd, env=entorno, capture_output=True, text=True, check=False,
    )


# --- quitar_bloque_sentinelas ---------------------------------------------------------

def test_quita_el_bloque_y_conserva_lo_de_arriba_y_abajo(tmp_path):
    f = tmp_path / "ssh_config"
    f.write_text("Host uno\n  User juan\n# >>> M >>>\nvieja\n# <<< M <<<\nHost dos\n  User ana\n")
    r = con_lib(f'quitar_bloque_sentinelas "{f}" "# >>> M >>>" "# <<< M <<<"', tmp_path)
    assert r.returncode == 0, r.stderr
    txt = f.read_text()
    assert "Host uno" in txt and "Host dos" in txt
    assert "vieja" not in txt and "# >>> M >>>" not in txt


def test_quitar_bloque_es_noop_si_no_esta(tmp_path):
    f = tmp_path / "f.conf"
    f.write_text("nada que ver\n")
    r = con_lib(f'quitar_bloque_sentinelas "{f}" "# >>> M >>>" "# <<< M <<<"', tmp_path)
    assert r.returncode == 0, r.stderr
    assert f.read_text() == "nada que ver\n"


def test_quitar_bloque_no_falla_si_el_archivo_no_existe(tmp_path):
    r = con_lib(f'quitar_bloque_sentinelas "{tmp_path}/no-existe" "# >>>" "# <<<"', tmp_path)
    assert r.returncode == 0, r.stderr


# --- quitar_unidad --------------------------------------------------------------------

def systemctl_falso(tmp_path):
    binn = tmp_path / "bin"
    binn.mkdir(exist_ok=True)
    (binn / "systemctl").write_text(
        f'#!/usr/bin/env bash\necho "$@" >> "{tmp_path}/llamadas.log"\nexit 0\n')
    (binn / "systemctl").chmod(0o755)
    return {"PATH": f"{binn}:{os.environ['PATH']}"}


def test_quitar_unidad_deshabilita_y_borra_el_archivo(tmp_path):
    env = systemctl_falso(tmp_path)
    units = tmp_path / "units"
    units.mkdir()
    (units / "demo.service").write_text("[Service]\n")
    env["SYSTEMD_USER_DIR"] = str(units)
    r = con_lib('quitar_unidad demo.service', tmp_path, env)
    assert r.returncode == 0, r.stderr
    assert not (units / "demo.service").exists()
    llamadas = (tmp_path / "llamadas.log").read_text()
    assert "disable --now demo.service" in llamadas
    assert "daemon-reload" in llamadas


def test_quitar_unidad_no_falla_si_la_unit_no_existe(tmp_path):
    env = systemctl_falso(tmp_path)
    env["SYSTEMD_USER_DIR"] = str(tmp_path / "units")
    r = con_lib('quitar_unidad fantasma.service', tmp_path, env)
    assert r.returncode == 0, r.stderr


# --- teardown-host.sh de punta a punta (sandbox) --------------------------------------

def _bin_falso(binn, nombre, log):
    binn.mkdir(exist_ok=True, parents=True)
    (binn / nombre).write_text(
        f'#!/usr/bin/env bash\necho "{nombre} $@" >> "{log}"\nexit 0\n')
    (binn / nombre).chmod(0o755)


def test_teardown_desinstala_lo_local_sin_tocar_lo_ajeno(tmp_path):
    home = tmp_path / "home"
    (home / ".local" / "bin").mkdir(parents=True)
    (home / ".ssh").mkdir()
    (home / ".config" / "systemd" / "user").mkdir(parents=True)
    log = tmp_path / "cmd.log"
    binn = tmp_path / "bin"
    for cmd in ("systemctl", "docker", "loginctl"):
        _bin_falso(binn, cmd, log)

    # Lo que dejó el setup:
    for h in ("marvin-stt", "marvin-display-allowed", "marvin-allow-display"):
        (home / ".local" / "bin" / h).write_text("#!/bin/sh\n")
    for u in ("marvin-render.service", "marvin-stt.service", "marvin-stt-live.service"):
        (home / ".config" / "systemd" / "user" / u).write_text("[Service]\n")
    (home / ".tmux.conf").write_text(
        "# mi tmux\nset -g status-bg blue\n# >>> RemoteMarvin >>>\nset -g mouse on\n# <<< RemoteMarvin <<<\n")
    (home / ".ssh" / "config").write_text(
        "Host trabajo\n  User yo\n# >>> RemoteMarvin >>>\nMatch exec foo\n# <<< RemoteMarvin <<<\n")
    # authorized_keys NO debe tocarse.
    (home / ".ssh" / "authorized_keys").write_text("ssh-ed25519 AAAA...app\nssh-ed25519 BBBB...otra\n")

    env = dict(
        os.environ, HOME=str(home),
        PATH=f"{binn}:{os.environ['PATH']}",
        SYSTEMD_USER_DIR=str(home / ".config" / "systemd" / "user"),
    )
    r = subprocess.run(["bash", TEARDOWN, "--sin-sudo"], env=env,
                       capture_output=True, text=True, check=False)
    assert r.returncode == 0, r.stderr

    # Units y helpers: fuera.
    for h in ("marvin-stt", "marvin-display-allowed", "marvin-allow-display"):
        assert not (home / ".local" / "bin" / h).exists(), h
    for u in ("marvin-render.service", "marvin-stt.service", "marvin-stt-live.service"):
        assert not (home / ".config" / "systemd" / "user" / u).exists(), u

    # Bloques fuera, pero el resto de cada archivo intacto.
    tmux = (home / ".tmux.conf").read_text()
    assert "set -g status-bg blue" in tmux and "RemoteMarvin" not in tmux
    ssh = (home / ".ssh" / "config").read_text()
    assert "Host trabajo" in ssh and "RemoteMarvin" not in ssh

    # authorized_keys: intacto, con las dos claves.
    ak = (home / ".ssh" / "authorized_keys").read_text()
    assert "AAAA...app" in ak and "BBBB...otra" in ak

    # docker bajado y linger deshabilitado.
    cmds = log.read_text()
    assert "docker compose down" in cmds
    assert "loginctl disable-linger" in cmds


# --- grupo docker residual ------------------------------------------------------------
# setup-host.sh hace `usermod -aG docker $USER` cuando INSTALA Docker, y eso es root efectivo
# (quien llega al socket puede todo). El teardown no debe sacarlo solo —el usuario podía estar
# en el grupo desde antes, misma lección que authorized_keys— pero tampoco puede callarlo.

def _teardown_con_grupos(tmp_path, grupos):
    """Corre el teardown en un sandbox con un `id` falso que reporta esos grupos."""
    home = tmp_path / "home"
    (home / ".local" / "bin").mkdir(parents=True)
    (home / ".config" / "systemd" / "user").mkdir(parents=True)
    log = tmp_path / "cmd.log"
    binn = tmp_path / "bin"
    for cmd in ("systemctl", "docker", "loginctl", "gpasswd", "usermod"):
        _bin_falso(binn, cmd, log)
    (binn / "id").write_text(
        f'#!/usr/bin/env bash\necho "id $@" >> "{log}"\necho "{grupos}"\n')
    (binn / "id").chmod(0o755)

    env = dict(
        os.environ, HOME=str(home),
        PATH=f"{binn}:{os.environ['PATH']}",
        SYSTEMD_USER_DIR=str(home / ".config" / "systemd" / "user"),
    )
    r = subprocess.run(["bash", TEARDOWN, "--sin-sudo"], env=env,
                       capture_output=True, text=True, check=False)
    assert r.returncode == 0, r.stderr
    return r, log.read_text()


def test_teardown_nombra_el_grupo_docker_pero_no_te_saca(tmp_path):
    r, cmds = _teardown_con_grupos(tmp_path, "tester docker sudo")
    salida = r.stdout
    # Lo NOMBRA, con el comando exacto para salir a mano.
    assert "docker" in salida and "gpasswd -d" in salida
    assert "sudo gpasswd -d" in salida
    # Pero NO lo ejecuta: la membresía se toca a mano o no se toca.
    assert "gpasswd" not in cmds
    assert "usermod" not in cmds


def test_teardown_no_molesta_si_no_estas_en_el_grupo_docker(tmp_path):
    r, cmds = _teardown_con_grupos(tmp_path, "tester sudo")
    assert "gpasswd" not in r.stdout
    assert "no está en él" in r.stdout
    assert "gpasswd" not in cmds and "usermod" not in cmds
