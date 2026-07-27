#!/usr/bin/env python3
"""Daemon de voz->texto en el HOST (dictado para la terminal de la app).

La app graba (push-to-talk), manda el WAV por SSH (cliente `marvin-stt`) y este daemon
lo transcribe con faster-whisper. El modelo se carga perezoso en el primer pedido y el
proceso se APAGA solo tras un rato sin uso (libera la RAM; el cliente lo relanza).

  ./scripts/marvin-stt.py          # foreground (normalmente lo maneja systemd --user)

Variables:
  MARVIN_STT_PORT   puerto loopback           (6091)
  MARVIN_STT_MODEL  modelo faster-whisper      (large-v3-turbo; p.ej. small si va lento)
  MARVIN_STT_LANG   idioma                     (es; "" = autodetectar)
  MARVIN_STT_IDLE   segundos sin uso -> exit   (600)

Endpoints:
  POST /stt   body = WAV  -> texto plano transcripto
  GET  /      estado (no carga el modelo)
"""
import os
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MARVIN_STT_PORT", "6091"))
MODEL = os.environ.get("MARVIN_STT_MODEL", "large-v3-turbo")
LANG = os.environ.get("MARVIN_STT_LANG", "es") or None
IDLE = int(os.environ.get("MARVIN_STT_IDLE", "600"))

_lock = threading.Lock()
_model = None
_mode = "sin cargar"
_last_use = time.time()


def ensure_cuda_ld():
    """ctranslate2 dlopen-ea cublas/cudnn; si vinieron por pip (nvidia-*-cu12), sus dirs
    tienen que estar en LD_LIBRARY_PATH ANTES de arrancar el proceso -> re-exec una vez."""
    try:
        import nvidia
    except ImportError:
        return
    # nvidia-* son namespace packages: sin __init__.py, __file__ es None -> usar __path__
    dirs = []
    for base in list(getattr(nvidia, "__path__", []) or []):
        for sub in (("cublas", "lib"), ("cudnn", "lib")):
            d = os.path.join(base, *sub)
            if os.path.isdir(d):
                dirs.append(d)
    if not dirs:
        return
    cur = os.environ.get("LD_LIBRARY_PATH", "")
    missing = [d for d in dirs if d not in cur.split(":")]
    if missing:
        os.environ["LD_LIBRARY_PATH"] = ":".join(missing + ([cur] if cur else []))
        os.execv(sys.executable, [sys.executable] + sys.argv)


def get_model():
    global _model, _mode
    with _lock:
        if _model is None:
            from faster_whisper import WhisperModel
            t0 = time.time()
            # GPU si hay driver CUDA; si no (p.ej. nouveau o sin placa), CPU int8.
            # int8_float16 y no float16: en las GTX 16xx (TU116, sin tensor cores) el
            # fp16 puro es ~4x mas lento (medido: 4.2s vs 1.0s para 4s de audio).
            try:
                print(f"[marvin-stt] cargando {MODEL} (cuda, int8_float16)...", flush=True)
                _model = WhisperModel(MODEL, device="cuda", compute_type="int8_float16")
                _mode = "cuda/int8_float16"
            except Exception as e:  # noqa: BLE001 - cualquier fallo de CUDA -> CPU
                print(f"[marvin-stt] sin CUDA ({e}); uso cpu/int8", flush=True)
                _model = WhisperModel(MODEL, device="cpu", compute_type="int8")
                _mode = "cpu/int8"
            print(f"[marvin-stt] modelo listo en {time.time()-t0:.1f}s ({_mode})", flush=True)
        return _model


def transcribe(wav_bytes: bytes) -> str:
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        f.write(wav_bytes)
        path = f.name
    try:
        t0 = time.time()
        segments, info = get_model().transcribe(
            path, language=LANG, beam_size=5, vad_filter=True,
        )
        text = " ".join(s.text.strip() for s in segments).strip()
        print(f"[marvin-stt] {info.duration:.1f}s de audio -> {time.time()-t0:.1f}s "
              f"({len(text)} chars)", flush=True)
        return text
    finally:
        os.unlink(path)


def watchdog():
    while True:
        time.sleep(30)
        if time.time() - _last_use > IDLE:
            print(f"[marvin-stt] {IDLE}s sin uso, me apago (el cliente me relanza)", flush=True)
            os._exit(0)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._send(200, f"marvin-stt OK (modelo {MODEL}: {_mode})")

    def do_POST(self):
        global _last_use
        _last_use = time.time()
        if self.path != "/stt":
            self._send(404, "")
            return
        n = int(self.headers.get("Content-Length", 0))
        if n == 0 or n > 100 * 1024 * 1024:
            self._send(400, "")
            return
        body = self.rfile.read(n)
        try:
            text = transcribe(body)
            _last_use = time.time()
            self._send(200, text)
        except Exception as e:  # noqa: BLE001 - al cliente le sirve saber qué pasó
            print(f"[marvin-stt] ERROR: {e}", file=sys.stderr, flush=True)
            self._send(500, f"error: {e}")


if __name__ == "__main__":
    ensure_cuda_ld()
    print(f"[marvin-stt] escuchando en 127.0.0.1:{PORT} "
          f"(modelo {MODEL}, lang {LANG or 'auto'}, idle {IDLE}s)", flush=True)
    threading.Thread(target=watchdog, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
