"""El visor exige autenticación (VncAuth), y el password que publica es el que vale.

Necesita el contenedor `display` corriendo, así que se saltea solo cuando no está — en CI
no hay docker. Para correrlo:  docker compose up -d display && make host

Habla el protocolo RFB por el mismo WebSocket que usa la app (websockify en :6080), no un
atajo: es la única forma de comprobar que lo que la app va a hacer efectivamente entra, y
que sin el password no.
"""
import os
import socket
import struct

import pytest

PUERTO = int(os.environ.get("NOVNC_PORT", "6080"))
PASS_FILE = os.path.expanduser("~/.config/marvin/vnc-pass")


def _hay_visor() -> bool:
    try:
        with socket.create_connection(("127.0.0.1", PUERTO), timeout=2):
            return True
    except OSError:
        return False


pytestmark = pytest.mark.skipif(
    not _hay_visor(), reason=f"el visor no está escuchando en :{PUERTO} (docker compose up -d display)"
)


def _clave_vnc(password: bytes) -> bytes:
    """VncAuth cifra el reto con DES usando la clave con los BITS de cada byte invertidos."""
    k = password[:8].ljust(8, b"\0")
    return bytes(int(f"{b:08b}"[::-1], 2) for b in k)


def _handshake(password: bytes):
    """Devuelve (tipos_de_seguridad_ofrecidos, resultado_de_autenticacion)."""
    websocket = pytest.importorskip("websocket", reason="hace falta websocket-client")
    des = pytest.importorskip("Crypto.Cipher.DES", reason="hace falta pycryptodome")

    ws = websocket.create_connection(
        f"ws://127.0.0.1:{PUERTO}/websockify", subprotocols=["binary"], timeout=10
    )
    buf = bytearray()

    def rd(n):
        # Guardar el frame ENTERO: si se descartan los bytes sobrantes, la lectura siguiente
        # se queda esperando un frame que ya llegó.
        while len(buf) < n:
            buf.extend(ws.recv())
        out = bytes(buf[:n])
        del buf[:n]
        return out

    try:
        rd(12)                                   # versión del servidor
        ws.send_binary(b"RFB 003.008\n")
        tipos = list(rd(rd(1)[0]))
        if 2 not in tipos:                       # sin VncAuth no hay nada que autenticar
            return tipos, None
        ws.send_binary(bytes([2]))
        reto = rd(16)
        ws.send_binary(des.new(_clave_vnc(password), des.MODE_ECB).encrypt(reto))
        return tipos, struct.unpack(">I", rd(4))[0]
    finally:
        ws.close()


def test_no_ofrece_entrar_sin_password():
    """El tipo 1 es 'None': con eso, cualquiera que llegue al puerto ve el escritorio."""
    tipos, _ = _handshake(b"loquesea")
    assert 1 not in tipos, f"el servidor todavía acepta conexiones sin autenticar: {tipos}"
    assert 2 in tipos, f"esperaba VncAuth (2), ofrece {tipos}"


def test_el_password_publicado_autentica():
    """Lo que el contenedor deja en ~/.config/marvin/vnc-pass tiene que ser el que vale:
    es lo que la app lee por SSH y le pasa al visor."""
    assert os.path.exists(PASS_FILE), f"el contenedor no publicó {PASS_FILE}"
    with open(PASS_FILE) as f:
        pw = f.read().strip()
    _, resultado = _handshake(pw.encode())
    assert resultado == 0, f"el password publicado NO autentica (código {resultado})"


def test_un_password_incorrecto_es_rechazado():
    _, resultado = _handshake(b"XXXXXXXX")
    assert resultado != 0, "entró con un password incorrecto"


def test_el_archivo_del_password_es_privado():
    """0600 y del usuario: si no, cualquier usuario local lo lee y la autenticación no sirve."""
    modo = os.stat(PASS_FILE).st_mode & 0o777
    assert modo == 0o600, f"permisos {oct(modo)}, esperaba 0o600"
    assert os.stat(PASS_FILE).st_uid == os.getuid(), "el archivo no es del usuario"
