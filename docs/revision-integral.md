# Revisión integral — julio 2026

Relevamiento completo del proyecto (seguridad, funcionalidad, bugs, tests) y estado de
cada hallazgo. **✅ arreglado · ⏳ pendiente · 📋 planificado**

Método: tres barridos sistemáticos por módulo (app Kotlin, host scripts/daemons, Go
bridge) + verificación en vivo contra el host de referencia. Los arreglos van siempre a
archivos del repo, nunca a la configuración de una máquina puntual.

---

## 1. Seguridad

### Críticos

| # | Hallazgo | Estado |
|---|---|---|
| S1 | **noVNC expuesto a la LAN sin autenticación**: `docker-compose.yml` publica `6080` en `0.0.0.0` y `Xvnc` corre con `-SecurityTypes None -ac`. Verificado en vivo con `ss` y `ps`. Sin firewall, cualquiera en el wifi controla el escritorio virtual. | ✅ **cerrado y verificado**: el 6080 sólo escucha en loopback (desde la LAN da rechazado) y el visor anda por túnel SSH · ⏳ falta password VNC y quitar `-ac` (defensa en profundidad) |
| S2 | **Host key sin verificar** en las 5 rutas SSH (`{_,_,_,_->true}`): habilita MITM sobre la terminal completa. `sshlib` ya trae `KnownHosts` para resolverlo. | ⏳ P0.2 |
| S3 | **`RemoteForward` incondicional**: todo server al que SSH-eás desde la app recibe un canal a tu display X y al render-daemon, que no tiene auth y abre URLs arbitrarias (incluido `file://`) y escribe archivos sin límite de tamaño. | ⏳ P0.3/P0.4 |
| S4 | **El túnel del dictado bindea `0.0.0.0` en el celular** (`createLocalPortForwarder(int,…)` → `new ServerSocket(port)`, verificado en el jar): durante un dictado, cualquiera en la red del teléfono entra al WhisperLiveKit del host. | ⏳ (`PortTunnel` ya bindea a loopback; falta migrar `LiveDictation` a usarlo) |
| S5 | **Auth key de Tailscale en claro** en `SharedPreferences`, precargada y visible en un `EditText`, con `allowBackup="true"`. | ⏳ P0.5 |

### Medios

| # | Hallazgo | Estado |
|---|---|---|
| S6 | Interpolación ad-hoc en comandos remotos; `renameSession` saneaba `new` pero no `old`. | ✅ `ShellQuote`/`TmuxName` en los 6 call sites |
| S7 | OSC 52 sin tope ni confirmación tras decodificar: escritura silenciosa del portapapeles. | ⏳ P0.6 |
| S8 | Enrolamiento muerto: manda una password a un host no verificado y su mitad server se borró del repo. `ENROLL_PASSWORD` quedó huérfano en `.env` (que usa 8 claves vs 5 documentadas). | ⏳ P0.8 |
| S9 | Units systemd sin hardening; prewarm con rutas fijas en `/tmp`; `curl \| sh` en el setup. | ⏳ P4 |
| S10 | `ts-link-qr.sh` imprime la auth key en claro y pasa el `client_secret` por argv (visible en `/proc`). | ⏳ P4 |

---

## 2. Bugs de la app

### Crashes

| Hallazgo | Estado |
|---|---|
| `session` era `tabs[activeIndex]` y la primera pestaña se crea tras I/O de red (≤10 s): cualquier tecla en esa ventana, o tras cerrar la última pestaña, tiraba `IndexOutOfBounds`. | ✅ |
| `WavRecorder.stop()` liberaba el `AudioRecord` aunque el join se agotara; el worker seguía leyéndolo → excepción en hilo sin catch. | ✅ |
| `onSizeChanged` sobre un executor ya apagado → `RejectedExecutionException` al redimensionar después de cerrar. | ✅ |
| Timer del splash sin cancelar: rotar apilaba dos `HostsActivity`. | ✅ |
| Diálogos mostrados desde hilos de fondo sobre una Activity muerta → `BadTokenException`. | ⏳ |
| Visor de documentos: descarga con pico de ~8× el tamaño del archivo, PDF renderizado entero en `ARGB_8888` en el hilo principal, `catch (Exception)` que no atrapa `OutOfMemoryError` → el proceso muere con archivos grandes. El `size` ya viaja por Intent pero nunca se lee. | ⏳ |
| `DisplayActivity`: `coerceIn(min > max)` en pantallas de más de 1920 dp → crash al tocar Zoom. | ✅ |

### Fugas de recursos

| Hallazgo | Estado |
|---|---|
| Cada dictado corto filtraba una conexión SSH + un `ServerSocket` + un WebSocket (la cancelación corría sobre campos aún nulos). | ✅ |
| Cerrar una pestaña mientras conectaba dejaba el hilo leyendo un socket vivo para siempre. | ✅ |
| Re-escanear el QR no limpiaba el cache de forwards: "conectado" pero nada funcionaba. Del lado Go, los listeners nunca se cierran en `Stop()`. | ✅ (Kotlin) / ⏳ (Go) |
| Tras un fallo de auth, `finishIfRunning()` es no-op y el `ioExecutor` (hilo no-daemon) nunca se apaga. | ⏳ |

### Comportamiento incorrecto

| Hallazgo | Estado |
|---|---|
| Cerrar una pestaña de fondo te movía a otra terminal a mitad de trabajo. | ✅ |
| Cerrar la última dejaba el chip fantasma y resucitaba la pestaña al reabrir. | ✅ |
| El texto dictado se escribía en la pestaña activa al terminar, no en la que dictaste. | ✅ |
| `renameSession` persistía aunque el host no aplicara el cambio → sesión real huérfana. | ✅ |
| `transcribe()` ignoraba el exit status: el error del cliente se tipeaba como transcripción. | ✅ |
| `exec()` devolvía `""` ante cualquier fallo, indistinguible de vacío. | ✅ |
| Doble toque en un host abre dos `MainActivity` que se pelean la misma sesión tmux (`-D` mutuo). | ⏳ |
| Tras autorizar la clave, solo la pestaña activa se recupera; las demás quedan muertas. | ⏳ |
| Las interfaces de red se pasan a tsnet una sola vez: al cambiar de wifi a datos el nodo embebido no se recupera (rompe el roaming que el diseño promete). | ⏳ |

### Concurrencia

| Hallazgo | Estado |
|---|---|
| `transcribing` sin `@Volatile`: el botón de dictado quedaba inutilizable. | ✅ |
| Campos de `LiveDictation` y `WavRecorder` sin `@Volatile`: audio encolado hasta descartarse. | ✅ |
| `committed`+`buffer` publicados por separado → palabras duplicadas al soltar. | ✅ |
| `feed()` podía adelantarse al drenaje de la cola → audio desordenado. | ✅ |
| Callback de red iterando la lista viva de pestañas desde otro hilo. | ✅ |
| `KeyStoreSsh.getOrCreateKeyPair()` sin sincronizar: dos hilos pueden generar y pisar la clave. | ⏳ |

---

## 3. Host: scripts y daemons

| Hallazgo | Estado |
|---|---|
| `marvin-stt` usa `curl -f`, que descarta el cuerpo del error: el mensaje del daemon se pierde y llega texto de curl. (Mitigado del lado app mirando el exit status.) | ⏳ |
| `run-visible.sh` / `run-local.sh`: bajo `pipefail`, un glob sin match o un `grep` sin resultado abortan el script en silencio; el fallback a `:0` es código muerto. | ⏳ |
| `display-entrypoint.sh`: si Xvnc o websockify mueren, el container queda vivo como zombi y `restart: unless-stopped` nunca actúa. | ⏳ |
| `setup-host.sh`: reescribe las units pero no reinicia lo que está corriendo; si el repo se mueve, las units fallan y quedan latcheadas; `uv` es dependencia dura que no instala ni verifica; deshabilita password auth *antes* de que exista la clave; un `~/.tmux.conf` preexistente nunca recibe las líneas que el sistema necesita. | ⏳ |
| `marvin-stt-live` no tiene idle-exit pero la app lo arranca igual en modo *ondemand*: ~2 GB de VRAM quedan tomados. | ⏳ |
| `check-version.sh`: compara versiones como strings y si no encuentra `plugin.json` avisa "desactualizado" en cada sesión, para siempre. | ⏳ |
| `marvin-show.sh`: la URL se corta en el primer `&` (solo escapa espacios). | ⏳ |
| Watchdog del STT puede matar una transcripción en curso; fugas de temporales; `ensure_cuda_ld` sin sentinela de re-exec. | ⏳ |

---

## 4. Calidad y build

| Hallazgo | Estado |
|---|---|
| **Cero tests, CI y linters** en todo el proyecto. | ✅ arrancado (15 tests unitarios) / ⏳ resto |
| `marvints.aar`: 14 MB commiteados, build manual con rutas absolutas, sin versión ni checksum, solo arm64. | ⏳ P3 |
| README raíz, README de android y DESIGN.md describen una arquitectura eliminada (gateway privilegiado, `nsenter`, mosh, sshj, Compose). | ⏳ P4 |
| Sin `LICENSE` en la raíz pese a vendorizar Termux (GPL-3.0). | ⏳ P4 |
| `MainActivity.kt` con 918 líneas concentra un tercio del código propio. | ⏳ P3 |

---

## Plan

El plan de mejora por fases (P0 seguridad → P1 correctitud → P2 tests/CI → P3 refactor →
P4 docs) vive en el plan de trabajo de la sesión. Regla: **P3 no arranca hasta que P2 esté
verde**, porque partir `MainActivity` sin red de contención es justamente donde se rompen
las cosas que el usuario descubre desde el celular.
