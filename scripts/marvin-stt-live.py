#!/usr/bin/env python3
"""Server de dictado EN VIVO (fase 2): WhisperLiveKit en 127.0.0.1:6092.

La app streamea PCM 16k s16le por WebSocket (tunelizado por SSH) y recibe el texto
parcial mientras el usuario habla; el final se inyecta al soltar. Complementa al daemon
batch (marvin-stt.py :6091), que queda como fallback.

Este wrapper resuelve las libs CUDA de pip (cublas/cudnn) en LD_LIBRARY_PATH, lanza el
server real y lo APAGA tras un rato sin uso. El idle-exit importa: el modelo se queda con
~2 GB de VRAM y la app arranca este server fire-and-forget en el primer dictado, así que
sin esto quedaba tomada hasta cerrar sesión aunque no volvieras a dictar.

En modo `marvin-stt mode always` no se apaga, igual que el daemon batch.

Variables: MARVIN_STT_LIVE_PORT (6092), MARVIN_STT_MODEL (large-v3-turbo),
           MARVIN_STT_LANG (es), MARVIN_STT_LIVE_IDLE (600)
"""
import os
import signal
import subprocess
import sys
import time

PORT = int(os.environ.get("MARVIN_STT_LIVE_PORT", "6092"))
IDLE = int(os.environ.get("MARVIN_STT_LIVE_IDLE", "600"))
MODE_FILE = os.path.expanduser("~/.config/marvin/stt-mode")

ARGS = [
    "--host", "127.0.0.1",
    "--port", str(PORT),
    "--model", os.environ.get("MARVIN_STT_MODEL", "large-v3-turbo"),
    "--language", os.environ.get("MARVIN_STT_LANG", "es"),
    "--backend", "faster-whisper",
    "--backend-policy", "simulstreaming",
    "--pcm-input",
]


def power_mode() -> str:
    """Igual que en marvin-stt.py: lo escribe `marvin-stt mode always|ondemand`."""
    try:
        with open(MODE_FILE) as f:
            return "always" if f.read().strip() == "always" else "ondemand"
    except OSError:
        return "ondemand"


def cuda_dirs():
    try:
        import nvidia
    except ImportError:
        return []
    dirs = []
    for base in list(getattr(nvidia, "__path__", []) or []):
        for sub in (("cublas", "lib"), ("cudnn", "lib")):
            d = os.path.join(base, *sub)
            if os.path.isdir(d):
                dirs.append(d)
    return dirs


ESTABLECIDA = "01"
ESCUCHANDO = "0A"


def _sockets(port: int, estado_buscado: str) -> int:
    """Sockets del puerto en un estado dado, leyendo /proc.

    Es la señal más honesta que tenemos: el wrapper no ve el tráfico del WebSocket (lo
    maneja el server real, en otro proceso). Sin dependencias — `ss` puede no estar en un
    host pelado.
    """
    hexport = f"{port:04X}"
    n = 0
    for archivo in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            with open(archivo) as f:
                next(f, None)   # cabecera
                for linea in f:
                    campos = linea.split()
                    if len(campos) < 4:
                        continue
                    local, estado = campos[1], campos[3]
                    if local.upper().endswith(":" + hexport) and estado == estado_buscado:
                        n += 1
        except OSError:
            continue
    return n


def conexiones_establecidas(port: int) -> int:
    """Clientes conectados: mientras alguien dicta, hay una conexión establecida."""
    return _sockets(port, ESTABLECIDA)


def esta_escuchando(port: int) -> bool:
    """¿El server real ya aceptó el puerto? Marca el fin del arranque."""
    return _sockets(port, ESCUCHANDO) > 0


def entorno_con_cuda() -> dict:
    env = dict(os.environ)
    actual = env.get("LD_LIBRARY_PATH", "")
    faltan = [d for d in cuda_dirs() if d not in actual.split(":")]
    if faltan:
        env["LD_LIBRARY_PATH"] = ":".join(faltan + ([actual] if actual else []))
    return env


def main() -> int:
    # Antes esto era un execv. Se lanza como hijo para poder vigilarlo: después de execv no
    # queda nadie que mida el uso ni que lo apague. El env va armado en el spawn, que es lo
    # que execv resolvía (glibc lee LD_LIBRARY_PATH al arrancar el proceso, no después).
    proc = subprocess.Popen(
        [sys.executable, "-c", "from whisperlivekit.basic_server import main; main()"] + ARGS,
        env=entorno_con_cuda(),
    )

    def reenviar(signum, _frame):
        proc.send_signal(signum)
    for s in (signal.SIGTERM, signal.SIGINT):
        signal.signal(s, reenviar)

    print(f"[marvin-stt-live] arrancando en 127.0.0.1:{PORT} (idle {IDLE}s)", flush=True)
    ultimo_uso = time.time()
    listo = False
    while True:
        try:
            return proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            pass
        # El reloj de inactividad no corre hasta que el server acepta el puerto. La primera
        # vez hay que DESCARGAR el modelo (~1,6 GB): con una conexión lenta eso pasa los 10
        # minutos del default y el daemon se mataba a sí mismo en medio de su propia
        # descarga, para volver a empezar de cero al dictado siguiente.
        if not listo:
            if not esta_escuchando(PORT):
                ultimo_uso = time.time()
                continue
            listo = True
            print("[marvin-stt-live] listo (modelo cargado)", flush=True)
        if conexiones_establecidas(PORT) > 0:
            ultimo_uso = time.time()
            continue
        if power_mode() == "always":
            continue
        if time.time() - ultimo_uso > IDLE:
            print(f"[marvin-stt-live] {IDLE}s sin uso, me apago y libero la VRAM "
                  f"(la app me relanza al dictar)", flush=True)
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
            # Salida limpia a propósito: con Restart=on-failure, systemd NO lo revive.
            return 0


if __name__ == "__main__":
    sys.exit(main())
