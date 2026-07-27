#!/usr/bin/env python3
"""Server de dictado EN VIVO (fase 2): WhisperLiveKit en 127.0.0.1:6092.

La app streamea PCM 16k s16le por WebSocket (tunelizado por SSH) y recibe el texto
parcial mientras el usuario habla; el final se inyecta al soltar. Complementa al daemon
batch (marvin-stt.py :6091), que queda como fallback.

Este wrapper solo resuelve las libs CUDA de pip (cublas/cudnn) en LD_LIBRARY_PATH y
ejecuta el server real con nuestra config. exec = el proceso nuevo nace con el env
correcto (setear LD_LIBRARY_PATH en el mismo proceso no sirve: glibc lo lee al arrancar).

Variables: MARVIN_STT_LIVE_PORT (6092), MARVIN_STT_MODEL (large-v3-turbo),
           MARVIN_STT_LANG (es)
"""
import os
import sys

ARGS = [
    "--host", "127.0.0.1",
    "--port", os.environ.get("MARVIN_STT_LIVE_PORT", "6092"),
    "--model", os.environ.get("MARVIN_STT_MODEL", "large-v3-turbo"),
    "--language", os.environ.get("MARVIN_STT_LANG", "es"),
    "--backend", "faster-whisper",
    "--backend-policy", "simulstreaming",
    "--pcm-input",
]


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


cur = os.environ.get("LD_LIBRARY_PATH", "")
missing = [d for d in cuda_dirs() if d not in cur.split(":")]
if missing:
    os.environ["LD_LIBRARY_PATH"] = ":".join(missing + ([cur] if cur else []))

# el server real (mismo venv de uv); sys.argv tras -c queda como argv del main()
os.execv(sys.executable,
         [sys.executable, "-c",
          "from whisperlivekit.basic_server import main; main()"] + ARGS)
