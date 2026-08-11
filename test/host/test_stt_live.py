"""Tests del idle-exit del dictado en vivo (scripts/marvin-stt-live.py).

Lo que se prueba es la señal de actividad: si `conexiones_establecidas` se equivoca, o el
daemon se apaga en medio de un dictado, o no se apaga nunca y se queda con ~2 GB de VRAM.
"""
import importlib.util
import os
import socket
import threading

RUTA = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "marvin-stt-live.py")
)
_spec = importlib.util.spec_from_file_location("marvin_stt_live", RUTA)
live = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(live)


def test_importar_el_modulo_no_levanta_el_server():
    """El wrapper tiene que ser importable sin efectos: si no, este test colgaría la suite."""
    assert hasattr(live, "conexiones_establecidas")


def test_sin_clientes_no_cuenta_conexiones():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        s.listen(1)
        puerto = s.getsockname()[1]
        assert live.conexiones_establecidas(puerto) == 0


def test_un_cliente_conectado_se_ve():
    """Es la señal que evita apagar el daemon en medio de un dictado."""
    servidor = socket.socket()
    servidor.bind(("127.0.0.1", 0))
    servidor.listen(1)
    puerto = servidor.getsockname()[1]
    aceptada = []
    hilo = threading.Thread(target=lambda: aceptada.append(servidor.accept()), daemon=True)
    hilo.start()

    cliente = socket.create_connection(("127.0.0.1", puerto), timeout=5)
    hilo.join(timeout=5)
    try:
        assert live.conexiones_establecidas(puerto) >= 1
    finally:
        cliente.close()
        if aceptada:
            aceptada[0][0].close()
        servidor.close()


def test_al_cerrarse_el_cliente_deja_de_contar():
    servidor = socket.socket()
    servidor.bind(("127.0.0.1", 0))
    servidor.listen(1)
    puerto = servidor.getsockname()[1]
    hilo = threading.Thread(target=servidor.accept, daemon=True)
    hilo.start()
    cliente = socket.create_connection(("127.0.0.1", puerto), timeout=5)
    hilo.join(timeout=5)
    cliente.close()
    servidor.close()
    # Puede quedar en TIME_WAIT, que NO es ESTABLISHED: no debe contarse como uso.
    assert live.conexiones_establecidas(puerto) == 0


def test_un_puerto_que_nadie_usa_da_cero():
    assert live.conexiones_establecidas(65000) == 0


def test_modo_always_se_lee_del_archivo(tmp_path, monkeypatch):
    """Con `marvin-stt mode always` no se apaga, igual que el daemon batch."""
    modo = tmp_path / "stt-mode"
    monkeypatch.setattr(live, "MODE_FILE", str(modo))
    assert live.power_mode() == "ondemand", "sin archivo, el default es a demanda"
    modo.write_text("always\n")
    assert live.power_mode() == "always"
    modo.write_text("ondemand\n")
    assert live.power_mode() == "ondemand"


def test_detecta_el_puerto_en_escucha():
    """Marca el fin del arranque: hasta que el server no acepta el puerto, el reloj de
    inactividad no corre. Sin esto, la primera vez (descarga del modelo, ~1,6 GB) el daemon
    se apagaba en medio de su propia descarga."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        puerto = s.getsockname()[1]
        assert not live.esta_escuchando(puerto), "todavía no hizo listen()"
        s.listen(1)
        assert live.esta_escuchando(puerto)
