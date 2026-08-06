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
| S2 | **Host key sin verificar** en las 5 rutas SSH (`{_,_,_,_->true}`): habilita MITM sobre la terminal completa. `sshlib` ya trae `KnownHosts` para resolverlo. | ✅ pinning TOFU en las 5 rutas (`HostKeys`), verificado end-to-end |
| S3 | **`RemoteForward` incondicional**: todo server al que SSH-eás desde la app recibe un canal a tu display X y al render-daemon, que no tiene auth y abre URLs arbitrarias (incluido `file://`) y escribe archivos sin límite de tamaño. | ✅ allowlist por host (`marvin-display-allowed`, bloque con sentinelas migrable) + daemon endurecido (sólo http/s, nombres y extensiones validados, tope de 25 MB, token opcional). Verificado con `ssh -G` y batería contra el daemon |
| S4 | **El túnel del dictado bindea `0.0.0.0` en el celular** (`createLocalPortForwarder(int,…)` → `new ServerSocket(port)`, verificado en el jar): durante un dictado, cualquiera en la red del teléfono entra al WhisperLiveKit del host. | ✅ bind a IPv4 loopback explícito en `LiveDictation` y `PortTunnel` |
| S5 | **Auth key de Tailscale en claro** en `SharedPreferences`, precargada y visible en un `EditText`, con `allowBackup="true"`. | ✅ `SecretStore` (AES-GCM en Keystore) + migración, campo enmascarado, `allowBackup=false` |

### Medios

| # | Hallazgo | Estado |
|---|---|---|
| S6 | Interpolación ad-hoc en comandos remotos; `renameSession` saneaba `new` pero no `old`. | ✅ `ShellQuote`/`TmuxName` en los 6 call sites |
| S7 | OSC 52 sin tope ni confirmación tras decodificar: escritura silenciosa del portapapeles. | ✅ diálogo arriba de 100 KB, verificado end-to-end con el fixture |
| S8 | Enrolamiento muerto: manda una password a un host no verificado y su mitad server se borró del repo. `ENROLL_PASSWORD` quedó huérfano en `.env` (que usa 8 claves vs 5 documentadas). | ✅ flujo y diálogo borrados; `.env.example` sincronizado |
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
| Diálogos mostrados desde hilos de fondo sobre una Activity muerta → `BadTokenException`. | ✅ guardas `isFinishing/isDestroyed` en los diálogos que se muestran tras I/O |
| Visor de documentos: descarga con pico de ~8× el tamaño del archivo, PDF renderizado entero en `ARGB_8888`, `catch (Exception)` que no atrapa `OutOfMemoryError`. | ✅ tope de 8 MB (usando el `size` que ya viajaba), `catch (Throwable)`, submuestreo de imágenes y `RGB_565` en PDF, archivo temporal propio por apertura |
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
| Doble toque en un host abre dos `MainActivity` que se pelean la misma sesión tmux (`-D` mutuo). | ✅ `launchMode=singleTop` |
| Tras autorizar la clave, solo la pestaña activa se recupera; las demás quedan muertas. | ⏳ |
| Las interfaces de red se pasan a tsnet una sola vez: al cambiar de wifi a datos el nodo embebido no se recupera. | ✅ `refreshInterfaces()` desde el callback de red |

### Concurrencia

| Hallazgo | Estado |
|---|---|
| `transcribing` sin `@Volatile`: el botón de dictado quedaba inutilizable. | ✅ |
| Campos de `LiveDictation` y `WavRecorder` sin `@Volatile`: audio encolado hasta descartarse. | ✅ |
| `committed`+`buffer` publicados por separado → palabras duplicadas al soltar. | ✅ |
| `feed()` podía adelantarse al drenaje de la cola → audio desordenado. | ✅ |
| Callback de red iterando la lista viva de pestañas desde otro hilo. | ✅ |
| `KeyStoreSsh.getOrCreateKeyPair()` sin sincronizar: dos hilos pueden generar y pisar la clave. | ✅ `@Synchronized` + cache (además saca I/O del Keystore del hilo principal) |

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
| **Cero tests, CI y linters** en todo el proyecto. | ✅ 34 tests JVM + 16 pytest + **6 E2E instrumentados** contra fixture desechable + CI bloqueante + job nocturno de E2E |
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

---

## Pendientes: qué falta y en qué fase va

Cerrado hasta acá: **P0** (seguridad), **P1** (correctitud) y **P2** (tests + CI).
Lo que sigue abierto, con su destino:

### P3 — refactor y build *(cerrado)*
| Ítem | Estado |
|---|---|
| ✅ **R8 / `isMinifyEnabled`** en release | Resuelto: 34,4 → 31,1 MB. Las keeps de producción cubren lo que el `.so` busca por nombre vía JNI (`go.**`, `marvints.**`) y trilead, que no trae reglas propias. Validado ejecutando: `make e2e-release` corre la suite contra el APK minificado (6/6) y el humo en el teléfono confirmó lo que la suite no puede tocar — el nodo tsnet aparece **online** en el tailnet con la build ofuscada. Además, `verifyReleaseKeepRules` verifica en cada build de release (y en CI, sin dispositivo) que el mapping conserve lo que se busca por nombre en runtime. |
| ✅ **`marvints.aar` multi-ABI y reproducible** | Resuelto: `tailscale-bridge/build-aar.sh` descubre la toolchain, corre `gofmt`/`vet`/`test` antes de compilar y deja un `.sha256` para detectar drift. El AAR trae arm64 + x86_64, pero el x86_64 entra **sólo en debug** (`abiFilters` por variante): el emulador corre nativo y el release no engorda. |
| ✅ Partir `MainActivity` (918 líneas) | Resuelto: 918 → 328, repartido en `TabsController` (pestañas y sesiones tmux), `KeypadView` (teclas extra y modificadores pegajosos), `DictationController` (dictado), `TerminalClients` (callbacks del motor vendorizado) y `Paleta`. La lógica que no necesita Android salió a `TabPlan` y `TerminalKeys`, con 17 tests JVM nuevos. En la activity quedaron el cableado, el ciclo de vida y los diálogos de identidad del host — que son decisiones de seguridad y por eso sólo la terminal las hace en primer plano. Validado con la suite E2E (6/6). |
| ✅ `marvints.go`: listeners que `Stop()` nunca cierra; `pipe()` sin timeout de dial; `Start` que devuelve OK mientras todavía está levantando | Resuelto, con 7 tests Go corridos con `-race` (incluido uno que verifica que tras `Stop()` el puerto queda **libre**, no sólo el listener cerrado). |

### P4 — infraestructura del host y documentación
| Pendiente | Nota |
|---|---|
| **`display-entrypoint.sh` sin supervisión** | Si Xvnc o websockify mueren, el container queda vivo como zombi y `restart: unless-stopped` nunca actúa: el visor queda mudo sin que nada avise. **Es funcional, no cosmético.** |
| **`setup-host.sh`**: no reinicia las units que reescribe, no verifica `uv`, y nunca actualiza un `~/.tmux.conf` preexistente | El síntoma típico es "actualicé y no cambió nada", o un host donde el render-daemon jamás autoarranca. **También funcional.** |
| **`marvin-stt-live` sin idle-exit** | La app lo arranca igual en modo *ondemand*, así que ~2 GB de VRAM quedan tomados hasta cerrar sesión. |
| `marvin-stt.py`: el watchdog puede matar una transcripción en curso; fuga de temporales; `ensure_cuda_ld` sin sentinela de re-exec | Robustez del daemon. |
| S9/S10: hardening de units, `mktemp` en el prewarm, `ts-link-qr` sin imprimir la key ni pasar el secreto por argv | Ya estaban asignados a P4. |
| Docs (`README`, `android/README`, `DESIGN.md`) y `LICENSE` GPL-3.0 | Los tres describen el gateway con `nsenter` que no existe más; falta el LICENSE pese a vendorizar Termux. |

### Brecha conocida: el APK que valida `make e2e-release` no es el publicado

La instrumentación es caja blanca por construcción — el APK de tests se carga en el proceso
de la app y enlaza contra sus clases — y R8 minifica sin saber que los tests existen (la
configuración que AGP le pasa no tiene una sola línea consciente de tests). De ahí que el
artefacto probado necesite `proguard-rules-e2e.pro`.

Mover esas keeps a producción **no** es la salida: medido, el dex pasa de 1,9 a 4,5 MB, o sea
tirar la mayor parte de lo que R8 da.

Lo que cierra la brecha, pendiente: verificar el release en **caja negra** con UI Automator
(corre en otro proceso, no referencia ninguna clase de la app → cero keeps → APK idéntico al
publicado), con `tmux` en el host como oráculo y logcat como canario — `GoLog: tsnet starting`
detecta que R8 rompió el binding JNI sin necesidad de tailnet. Se pierde `screenText()`, que
prueba que la salida volvió a la app; se recuperaría exponiendo el texto de la terminal por
accesibilidad, que además hoy TalkBack no puede leer.

Mientras tanto, la detección está cubierta por `verifyReleaseKeepRules` (invariantes sobre el
mapping, en cada release y en CI) más el humo manual en el teléfono.

### Cobertura de tests que falta
Los daemons del host tienen suite (`test/host/`) sólo para `marvin-render`. Los arreglos
de `setup-host` y `display-entrypoint` de P4 deberían entrar con tests propios — el
fixture ya existe y puede hospedarlos.
