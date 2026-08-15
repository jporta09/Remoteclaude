#!/usr/bin/env python3
"""Stub de WhisperLiveKit para los E2E: habla el protocolo, sin modelo ni GPU.

El server real escucha en 127.0.0.1:6092 y la app llega por un port-forward de SSH, así que
esto vive DENTRO del fixture y no publica ningún puerto.

Protocolo, tal como lo consume la app (ver `WlkSnapshot` y `LiveDictation`):

  app -> stub   frames binarios con PCM 16k s16le
  stub -> app   {"status":"active_transcription",
                 "lines":[{"text":"…"}],          # lo ya confirmado
                 "buffer_transcription":"…"}      # lo que todavía está en vuelo
  app -> stub   frame binario VACÍO = se acabó el audio
  stub -> app   el snapshot final y luego {"type":"ready_to_stop"}

Las respuestas son fijas a propósito: el test verifica el CAMINO (túnel, WebSocket, orden de
los chunks, cierre), no la calidad de una transcripción.
"""
import asyncio
import http
import json
import os

import websockets

PUERTO = int(os.environ.get("WLK_STUB_PORT", "6092"))
# Bandera para simular "el server en vivo no está" sin matar el proceso: matarlo y
# reponerlo entre tests es una carrera (el que arranca mientras el viejo agoniza no puede
# bindear el puerto y muere), y deja a los tests siguientes sin server.
APAGADO = os.environ.get("WLK_STUB_OFF", "/tmp/wlk-stub-apagado")
PARCIAL = "hola"
FINAL = "hola marvin"


def snapshot(lineas, buffer=""):
    return json.dumps({
        "status": "active_transcription",
        "lines": [{"text": t} for t in lineas],
        "buffer_transcription": buffer,
    })


async def sesion(ws, path=None):
    """Una conexión = un dictado. `path` sólo existe en websockets < 12."""
    recibidos = 0
    async for msg in ws:
        if not isinstance(msg, (bytes, bytearray)):
            continue                      # el cliente real no manda texto
        if len(msg) == 0:                 # fin del audio
            await ws.send(snapshot([FINAL]))
            await ws.send(json.dumps({"type": "ready_to_stop"}))
            return
        recibidos += 1
        # Parcial que aparece recién con audio de verdad: así el test distingue "llegaron
        # los chunks" de "el server saluda solo al conectarse".
        await ws.send(snapshot([], PARCIAL))


async def rechazar_si_apagado(path, request_headers):
    """Con la bandera puesta, el handshake falla: es lo que ve la app si el server no está."""
    if os.path.exists(APAGADO):
        return http.HTTPStatus.SERVICE_UNAVAILABLE, [], b"stub apagado a proposito\n"
    return None


async def main():
    async with websockets.serve(sesion, "127.0.0.1", PUERTO, max_size=None,
                                process_request=rechazar_si_apagado):
        print(f"[wlk-stub] escuchando en 127.0.0.1:{PUERTO}", flush=True)
        await asyncio.Future()            # para siempre


if __name__ == "__main__":
    asyncio.run(main())
