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
| S1 | **noVNC expuesto a la LAN sin autenticación**: `docker-compose.yml` publica `6080` en `0.0.0.0` y `Xvnc` corre con `-SecurityTypes None -ac`. Verificado en vivo con `ss` y `ps`. Sin firewall, cualquiera en el wifi controla el escritorio virtual. | ✅ **cerrado y verificado**: el 6080 sólo escucha en loopback (desde la LAN da rechazado) y el visor anda por túnel SSH · password VNC y cookie X también cerrados (ver "Lo que queda abierto") |
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
| S9 | Units systemd sin hardening; prewarm con rutas fijas en `/tmp`. | ✅ `NoNewPrivileges`/`ProtectSystem` y compañía + `mktemp` (P4) |
| S10 | `ts-link-qr.sh` imprimía la auth key en claro y pasaba secretos por argv. | ✅ secretos por `--config` stdin; la key sólo con `--mostrar-key` explícito (P4) |

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
| Re-escanear el QR no limpiaba el cache de forwards; del lado Go, los listeners no se cerraban en `Stop()`. | ✅ ambos (el Go en P3, con test de puerto libre tras `Stop()`) |
| Tras un fallo de auth, `finishIfRunning()` era no-op y el `ioExecutor` no se apagaba. | ✅ verificado en el código actual: `mRunning` queda en true tras el fallo, así que el retry cierra transporte y executor |

### Comportamiento incorrecto

| Hallazgo | Estado |
|---|---|
| Cerrar una pestaña de fondo te movía a otra terminal a mitad de trabajo. | ✅ |
| Cerrar la última dejaba el chip fantasma y resucitaba la pestaña al reabrir. | ✅ |
| El texto dictado se escribía en la pestaña activa al terminar, no en la que dictaste. | ✅ |
| `renameSession` persistía aunque el host no aplicara el cambio → sesión real huérfana. | ✅ |
| `transcribe()` ignoraba el exit status: el error del cliente se tipeaba como transcripción. | ✅ |
| `exec()` devolvía `""` ante cualquier fallo, indistinguible de vacío. | ✅ *(el mecanismo entró en P1, pero **sólo lo usaba `renameSession`**: el camino de documentos —el que el hallazgo nombraba— siguió tragándose el error hasta que un E2E lo expuso)* |
| Doble toque en un host abre dos `MainActivity` que se pelean la misma sesión tmux (`-D` mutuo). | ✅ `launchMode=singleTop` |
| Tras autorizar la clave, solo la pestaña activa se recuperaba; las demás quedaban muertas. | ✅ `reconectarTodas()` en los dos diálogos (autorizar clave y confiar host key): la decisión es del host, no de una pestaña. Las de fondo reviven perezosas, sin costo hasta tocarlas |
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
| `marvin-stt` usa `curl -f`, que descarta el cuerpo del error. | ✅ `--fail-with-body` en el camino del dictado; y el `mode status` ya no muere por `set -e` si el daemon cae entre el chequeo y el curl |
| `run-visible.sh` / `run-local.sh`: bajo `pipefail`, un glob sin match o un `grep` sin resultado abortan en silencio; el fallback a `:0` era código muerto. | ✅ `\|\| true` en ambos + normalización del socket X (el chequeo de existencia siempre pasaba) |
| `display-entrypoint.sh`: si Xvnc o websockify mueren, el container queda como zombi. | ✅ supervisión con `wait -n` + mapa de servicios (P4) |
| `setup-host.sh`: no reiniciaba units, `~/.tmux.conf` preexistente sin las líneas necesarias, etc. | ✅ `setup-host-lib.sh` (bloques con sentinelas idempotentes, `escribir_unidad` reinicia lo activo), 13 tests (P4) |
| `marvin-stt-live` sin idle-exit: ~2 GB de VRAM tomados para siempre en *ondemand*. | ✅ reescrito con supervisor + idle-exit (P4) |
| `check-version.sh`: comparaba versiones como strings (`1.10 < 1.9`) y sin `plugin.json` avisaba "desactualizado" para siempre. | ✅ `sort -V` + silencio bajo el hook; **5 tests de regresión nuevos** |
| `marvin-show.sh`: la URL se cortaba en el primer `&`. | ✅ `--data-urlencode`; **y el test de regresión destapó otro**: un archivo con espacio en el nombre moría en `curl (3)` — ahora se normaliza a la whitelist del daemon. 4 tests nuevos |
| Watchdog del STT podía matar una transcripción en curso; fugas de temporales; `ensure_cuda_ld` sin sentinela. | ✅ (P4) |

---

## 4. Calidad y build

| Hallazgo | Estado |
|---|---|
| **Cero tests, CI y linters** en todo el proyecto. | ✅ 51 tests JVM + 40 pytest + **29 E2E instrumentados** contra fixture desechable + CI bloqueante (hoy sobre runner self-hosted, ver más abajo) + job nocturno de E2E |
| `marvints.aar`: build manual con rutas absolutas, sin checksum, solo arm64. | ✅ `build-aar.sh` reproducible + `.sha256` + multi-ABI (P3) |
| README raíz, README de android y DESIGN.md describían una arquitectura eliminada. | ✅ reescritos (P4) |
| Sin `LICENSE` en la raíz pese a vendorizar Termux (GPL-3.0). | ✅ GPL-3.0 + NOTICE.md + SECURITY.md (P4) |
| `MainActivity.kt` con 918 líneas concentraba un tercio del código propio. | ✅ 918 → 328, repartido en 5 componentes + 17 tests JVM (P3) |

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

### P4 — infraestructura del host y documentación *(cerrado)*
| Pendiente | Nota |
|---|---|
| ✅ **`display-entrypoint.sh` sin supervisión** | Si Xvnc o websockify mueren, el container queda vivo como zombi y `restart: unless-stopped` nunca actúa: el visor queda mudo sin que nada avise. **Es funcional, no cosmético.** |
| ✅ **`setup-host.sh`**: no reinicia las units que reescribe, no verifica `uv`, y nunca actualiza un `~/.tmux.conf` preexistente | El síntoma típico es "actualicé y no cambió nada", o un host donde el render-daemon jamás autoarranca. **También funcional.** |
| ✅ **`marvin-stt-live` sin idle-exit** | La app lo arranca igual en modo *ondemand*, así que ~2 GB de VRAM quedan tomados hasta cerrar sesión. |
| ✅ `marvin-stt.py`: el watchdog puede matar una transcripción en curso; fuga de temporales; `ensure_cuda_ld` sin sentinela de re-exec | Robustez del daemon. |
| ✅ S9/S10: hardening de units, `mktemp` en el prewarm, `ts-link-qr` sin imprimir la key ni pasar el secreto por argv | Ya estaban asignados a P4. |
| ✅ Docs (`README`, `android/README`, `DESIGN.md`), `LICENSE` GPL-3.0, `NOTICE.md` y `SECURITY.md` | Los tres describen el gateway con `nsenter` que no existe más; falta el LICENSE pese a vendorizar Termux. |

### La brecha del release cerrada: validación en caja negra

`make e2e-caja-negra` valida el APK **tal como se publica**. El módulo `:blackbox` no
depende de `:app` ni referencia una sola de sus clases, así que R8 no tiene que dejarle
ninguna costura: se maneja la app por la interfaz con UI Automator y el oráculo es el host.

Lo que hacía falta separar en el build: la ABI del emulador y las keeps de test estaban
pegadas bajo la misma bandera, y esa coincidencia **era** la brecha — no había forma de
pedir "el APK publicado, pero instalable en el emulador". Ahora `-PmarvinEmuAbi` agrega
`x86_64` sin tocar una regla de R8, y el script **comprueba** el resultado en vez de
afirmarlo: construye las dos variantes y compara el sha256 de los `.dex` (`classes.dex` da
idéntico; lo único que cambia es que se suma `lib/x86_64/libgojni.so`).

Tres tests: que el APK publicado abra SSH de verdad y cree la sesión en el host, que lo
tipeado llegue (`input text` → `tmux capture-pane`), y que el binding JNI de gomobile siga
en pie. Este último funciona **sin tailnet**: con una auth key inválida el puente igual se
invoca, y lo que se afirma es la CLASE de error — uno de Tailscale significa que se ejecutó
código Go; un `NoClassDefFoundError`/`UnsatisfiedLinkError` significa que R8 se llevó puesto
el binding.

**Se verificó que puede fallar**, que es lo único que distingue un gate de un adorno: con el
keep de trilead removido a propósito, los tres tests se ponen rojos (y el APK igual se
produce, porque el portero estático corre después de empaquetar).

Se apoya en el diálogo "Falta autorizar este dispositivo" para leer la clave pública de la
pantalla: es la única vía que tiene un test de afuera de saberla.

**La intermitencia que tenía, diagnosticada.** Las primeras 8 corridas dieron 6 verdes y 2
rojas sin explicación: la app quedaba en pantalla sin mostrar lo esperado. Después no
reprodujo nunca —11 verdes seguidas—, lo que descartó que fuera determinístico y apuntó al
entorno: los dos fallos habían caído justo después del experimento de mutación, con la
máquina cargada. Se puso a prueba esa hipótesis saturando 10 de 12 núcleos, y falló en la
**primera** corrida, esta vez con un mensaje preciso: `kexTimeout (8000 ms) expired` en
`com.trilead.ssh2.Connection.connect` — o sea el canal de control **del propio test** al
fixture, no la app.

Los plazos del andamio estaban pensados para una máquina ociosa, y el runner comparte
máquina con el escritorio y con los builds: la carga es la condición normal, no la
excepción. Se subieron los timeouts (8 s → 30 s, con reintento), `tocar` pasó de 15 a 30 s,
y `abrirApp` ahora espera la pantalla de hosts en vez de "cualquier ventana de la app" —
esperar la ventana daba por buena la del splash, y el fallo aparecía después, en un `tocar`
que no encontraba nada. Resultado: **3 de 3 en verde bajo la misma carga que lo tiraba**, y
por eso ya bloquea PRs.

Vale la pena anotar el modo de fallo, porque se repite en este proyecto: el andamio del
test se rendía antes que el producto, y el síntoma parecía del producto.

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

### Lo que queda abierto (a propósito)

| Pendiente | Por qué sigue abierto |
|---|---|
| ✅ Password de VNC | Hecho: `Xvnc` corre con `VncAuth`, el password se genera en cada arranque y se publica 0600 para que la app lo lea por SSH. Verificado hablando RFB por el mismo WebSocket que usa la app: el server ya sólo ofrece VncAuth, el password publicado autentica y uno incorrecto es rechazado (4 tests en `test/host/test_vnc_auth.py`). |
| ✅ Sacar `-ac` del `Xvnc` | Hecho: el `:99` exige MIT-MAGIC-COOKIE-1 y la cookie se publica en `~/.config/marvin/Xauthority`. La usan el render-daemon, `run-visible.sh` y los servers habilitados (`marvin-allow-display` se la copia). Verificado: sin cookie el display rechaza, con cookie un cliente del host se conecta **y dibuja**, y el visor del celu sigue mostrando el escritorio (comprobado pintando el display de rojo y viendo el color llegar al teléfono). |
| **Distribución del APK** (decisión del usuario, ago-2026; el "cómo" queda TBD) | El usuario NO debe compilar el APK: hay que disponibilizarlo ya construido. Es el reframe del hallazgo de la ola 2 (DevOps): un fresh clone no trae APK y el README apunta a `android/app/build/outputs/apk/release/`, una ruta de build que `.gitignore` ignora. El arreglo correcto no es documentar la compilación (SDK+JDK17+keystore) sino **publicar el release firmado** y que README/quickstart apunten ahí. El canal (GitHub Releases, link directo, F-Droid, etc.) se decide después. |
| El APK que valida `make e2e-release` no es byte-idéntico al publicado | Detallado más arriba. Mitigado por `verifyReleaseKeepRules` (cada release y en CI) más el humo manual. |
| ✅ **Subir documentos desde el celu** | Hecho (ago-2026): botón ＋ en Documentos → selector de Android (SAF, multi-selección, sin permisos de storage) → los archivos viajan por el stdin de un `cat` remoto (el patrón de `transcribe()`) a `~/RemoteMarvinDocs/subidos/`, separados de lo de Claude y en su propia sección de la lista. Nombres normalizados con la regla de `marvin-show.sh` (`DocsPlan.normalizarNombre`), tope 25 MB. E2E: subida de texto y binario verificada con sha256 contra el fixture, y nombre hostil rechazado sin tocar el shell. |
| ✅ **Ordenar la lista de documentos** | Hecho (ago-2026): botón ⇅ con menú de 5 criterios (nombre, tamaño, tipo, creación, modificación) + invertir dirección, persistido en prefs (`docs_orden`/`docs_orden_asc`) y visible en la línea de estado. `find` ahora trae también `%W@` (btime); si el filesystem no lo registra, el criterio "creación" avisa y degrada a modificación, y los docs sin btime cierran la lista en ambas direcciones. Lógica pura en `DocsPlan.ordenar` con 11 unit tests. (Nota corregida: la lista nunca salía "en el orden del find" — la ordenaba la app por mtime desc en `RemoteControl.listDocs`.) |
| ✅ **Skills del plugin al día con Documentos v2** (1.3.0) | Hecho (ago-2026): `share-doc` y el router `remotemarvin` ahora saben de `~/RemoteMarvinDocs/subidos/` (los archivos que el usuario sube desde el celular — "te subí X" dispara mirar ahí), del tope de 8 MB del visor al compartir, del manual publicado y la demo, y describen la UI real (íconos de marca, no emoji). `plugin.json` 1.2.4 → 1.3.0 para que el hook `check-version` avise en las otras instalaciones. Además la instalación del plugin quedó documentada donde faltaba: burbuja propia "Las skills de Claude" en la demo de hosts (id versionado a `hosts2`) y paso con los dos comandos `/plugin` en la puesta en marcha del manual — el gap salió a la luz cuando el propio usuario se topó con "Marketplace not found". |
| ✅ **Borrar documentos desde el celu** | Hecho (ago-2026): long-press en la tarjeta → "Borrar del host" → confirmación → `rm --` por SSH con el saneo compartido (`nombreInseguro` + `ShellQuote`, mismo de la lectura y la subida). Borrar algo inexistente es un error visible (no silencio), y el E2E de regresión verifica canario limpio y que un intento de traversal no borra nada de la raíz. |
| ✅ El manual de uso (`scripts/gen-manual.py`) describía la arquitectura vieja | Reescrito. Además del gateway con `nsenter` y `HOST_USER`, prometía el auto-enrolamiento por contraseña (eliminado en P0.8), apuntaba a un `scripts/marvin-share.sh` que se mudó al plugin, y **no mencionaba el dictado por voz**. |

### Tipografías: las cuatro del manual de marca, todas redistribuibles

El manual de identidad define cuatro roles: **títulos** (ISOCPEUR), **subtítulos** (Mononoki),
**cuerpo** (Ubuntu Sans) y **detalles y comentarios** (Brandon Grotesque). Dos de esas cuatro
son comerciales, y el proyecto es GPL-3.0, que exige poder redistribuir todo lo que
distribuye:

| Rol | Manual | En el producto | Por qué |
|---|---|---|---|
| Títulos | ISOCPEUR | **osifont** | ISOCPEUR es de Autodesk, `all rights reserved`, y **viajaba dentro del APK**. osifont sigue la misma norma ISO 3098. |
| Subtítulos | Mononoki | Mononoki | OFL, sin cambios. |
| Cuerpo | Ubuntu Sans | Ubuntu Sans | Ubuntu Font Licence, sin cambios. |
| Detalles | Brandon Grotesque | **Jost\*** | Brandon es comercial de HvD Fonts. Nunca estuvo en el repo (vive en `fonts/`, gitignoreado). Jost es del mismo linaje Futura/Erbar y calza en proporciones (0.657 vs 0.660). |

El rol de "detalles" además **no estaba implementado**: en el PDF lo cubría la mono —que el
manual reserva para subtítulos, o sea dos roles pisándose— y en la app lo dibujaba la fuente
del sistema.

### El navegador headed no dependía de una versión fija

`marvin-render.py` invocaba `uv run --with playwright`, **sin pin**. Cada release nueva de
Playwright pide un chromium distinto que no está en `~/.cache/ms-playwright`, así que el visor
dejaba de abrir el navegador **solo y en silencio** (el caché llegaba a chromium-1228, de
junio, y la versión resuelta pedía el 1234). Ahora la versión está fija y documentada: al
subirla hay que correr `playwright install chromium`.

Se descubrió porque `render()` mandaba la salida del browser a `/dev/null` y se cambió por un
log — sin eso el síntoma era "no pasa nada".

### Íconos vectoriales propios (cerrado, segunda vuelta con el usuario)

La primera versión pasó por revisión de diseño del usuario y salieron tres correcciones,
todas resueltas midiendo el asset real (`marvin_isologo_bar.png`) en vez de interpretar:

- **Las flechas (shift, reenganchar) ahora citan la composición exacta de la A del logo**:
  el ▲ macizo con LA BARRA diagonal al lado — pendiente 0.47, grosor 0.24·h, sobresaliendo
  un 40% de la altura por encima del ápice, con su aire de ~1/3 del ancho. Se llegó por
  superposición iterativa contra la letra ampliada ×8 (tres rondas; el escaneo inicial
  tenía la ventana mal y cortaba media barra — todas las medidas previas nacieron viciadas).
- **Dos tonos en reposo**: estructura en gris claro y acento VERDE CTR horneado en el XML
  (▲ de las flechas, dobles barras de visor/docs/micrófono). En estados (shift activo,
  grabando) se tinta monocromo al color del estado, como las variantes de un color del
  manual. `Iconos.drawable(color=null)` = dos tonos; con color = monocromo.
- **El ámbar quedó descartado con el manual en la mano**: el usuario prefería probarlo, y
  la fila "Colores" de usos incorrectos muestra literalmente los triángulos en ámbar como
  ejemplo tachado — el ámbar es "señalética complementaria", nunca va dentro de las
  figuras de marca. Se validó con A/B real en el emulador (ambas variantes instaladas y
  capturadas) antes de decidir.

### Íconos vectoriales propios (primera versión)

Los 8 íconos de la interfaz son ahora `VectorDrawable` dibujados con la gramática del manual
de identidad, releído a fondo antes de trazar una línea: grilla modular (24, trazo uniforme
de 2), sólo rectas y diagonales, las esquinas cortadas de la cápsula del isotipo como forma
contenedora, y las firmas de la línea `[▲\\▼]` repartidas por el set — el ▲ macizo es la
cabeza del shift y la punta del reenganchar; la doble barra vive dentro del visor, el
documento y el micrófono; la pantalla del visor ES la cápsula. Se iteraron primero como SVG
renderizados sobre la paleta (dos vueltas: el micrófono v1 no respiraba a 28px) y recién
después se portaron a XML.

Historia completa del recorrido: emoji del sistema (distintos por fabricante, ⧉ inexistente
en algunos equipos) → fuente propia de 8 glifos (`marvin_icons.ttf`) → vectores. La fuente y
su generador (`build-icon-font.py`) se eliminaron. En los botones con palabra ("🎤 Dictar")
el vector va embebido como ImageSpan centrado — con compound drawables el ícono quedaba
pegado al borde del botón y el texto centrado, cada uno por su lado; y el ALIGN_CENTER de la
plataforma pide API 29, así que el centrado vertical es propio (minSdk 26).

### Cobertura de tests: documentos y dictado (nuevo)

La suite instrumentada pasó de 6 a 17. Los 11 nuevos cubren las dos funciones que más se
tocaron y no tenían ninguno:

- **Documentos**: la lista llega con tamaños reales, el contenido se lee, un nombre con
  comilla no ejecuta nada (canario en `/tmp` que debe seguir sin existir), los nombres
  inseguros se rechazan de entrada, y **un host caído se distingue de "no hay documentos"**.
- **Dictado por lote**: el audio viaja y vuelve el texto, el host recibe un WAV válido de
  verdad, y **un error del host no se tipea como si fuera la transcripción**.

El primero de esos tests **nació fallando** y así destapó que B2 seguía abierto en el camino
de documentos. Vale como recordatorio de para qué son estos tests: la lectura del código decía
que estaba arreglado.

El **dictado EN VIVO** también quedó cubierto: el fixture ahora trae un stub de WhisperLiveKit
(`test/e2e/fixture/wlk-stub.py`) que habla el protocolo relevado. Seis tests sobre el camino con
más partes móviles de la app: túnel SSH → WebSocket → chunks en orden → parciales → cierre.
Cubren además los dos casos que sólo se veían usando la app: que **los chunks anteriores a que
abra el WebSocket no se pierdan** (si no, falta el arranque de cada dictado) y que **si el
server no está, `start()` avise en vez de colgarse**, que es el contrato del fallback al modo
por lote.

Ese último se simula con una **bandera** que el stub mira al aceptar, no matando el proceso:
matarlo y reponerlo entre tests es una carrera —el que arranca mientras el viejo agoniza no
puede bindear el puerto— y dejaba sin server a los tests siguientes, que fallaban por culpa de
otro con el motivo lejos de la causa. El stub corre **como el usuario**, igual que en
producción (systemd `--user`), y loguea al stdout del contenedor.

### Rotación y reconexión (nuevo)

La suite llega a 29. Los 6 últimos cubren los dos casos que el plan preveía y no existían:

- **Rotación y arranque en frío**: las dos caras del bug de `3795a8d`, que dejaba la app en la
  pestaña equivocada. Se siembran TRES pestañas con la del medio activa a propósito: con dos,
  "restaurar la última" pasaría de casualidad.
- **Reconexión**: el corte es real (`e2e-drop-ssh` mata el sshd del usuario), y se verifica que
  la sesión tmux sobreviva con su contenido, que lo tipeado *después* siga yendo a la misma
  sesión, y que el reintento **no cree sesiones de más** — que sería perder de vista el trabajo
  anterior.

Escribiéndolos apareció un comportamiento que no estaba documentado: **las sesiones se conectan
de forma perezosa**, al engancharse la vista de terminal. Restaurar tres pestañas crea UNA sola
sesión en el host —la activa—; las otras existen recién cuando las tocás. No es un bug (ahorra
conexiones), pero invalidaba la primera versión de los tests, que contaba sesiones. Ahora miran
cuál quedó **enganchada**, que además detecta el bug original más directamente: su síntoma era
justamente quedar enganchado a la pestaña equivocada.

### El CI estuvo ciego 8 pushes, y el arreglo destapó tres cosas más

Los jobs no fallaban: **no arrancaban**. Ocho corridas seguidas terminadas en 3 segundos con
"the job was not started because recent account payments have failed or your spending limit
needs to be increased" — facturación de GitHub Actions, cero relación con el código. El
detalle importante es que en la lista se ven como ✗ rojas, indistinguibles de un test roto,
así que el repo estuvo ocho pushes sin ninguna verificación pareciendo tenerla.

La salida es la que el plan ya preveía: **runner self-hosted en esta misma máquina**
(`scripts/setup-runner.sh`, servicio de usuario, sin sudo; el `svc.sh` oficial pide una unit
de sistema). Verifica el sha256 del paquete contra el release antes de extraerlo, y el
workflow no queda clavado: los tres jobs leen la variable de repo `MARVIN_RUNNER`, así que
volver a los runners de GitHub cuando se arregle la facturación es borrar una variable desde
la web. Estar clavado a un lado fue justamente lo que dejó el repo sin gate.

**Modelo de amenaza**, escrito en el encabezado del script: el runner ejecuta el workflow de
cada push **con tu usuario** y ve `~/.ssh`, el keystore de firma y `.env`. Es aceptable sólo
porque el repo es privado. El script **se niega a instalar** si deja de serlo, porque ahí
cualquier PR desde un fork sería ejecución remota de código en la máquina.

Correrlo destapó tres problemas reales, ninguno inventado por el runner:

| Qué apareció | Por qué importa |
|---|---|
| `make lint` y `make unit` andaban **según el shell** desde el que los llamaras | El java por defecto de la máquina es un 21 sin `jlink`, que AGP necesita, y el Makefile no fijaba `JAVA_HOME`. Un target de verificación que depende del ambiente es exactamente lo contrario de lo que se le pide. `e2e.sh` ya lo tenía resuelto para sí mismo; ahora la búsqueda vive en `scripts/jdk17.sh` y la usan los dos. |
| El stub del fixture tenía shebang y **no era ejecutable** | Lo detectó `ruff` (EXE001) recién al correr el lint sobre `test/e2e/fixture/`, que ni el Makefile ni el CI incluían: los stubs no los revisaba nadie. |
| Tres **daemons de Gradle** vivos sin `ANDROID_HOME` | Gradle reusa un daemon compatible, y adentro de ese proceso `System.getenv()` devuelve lo que había cuando arrancó. Un build del CI reusaba uno levantado por una compilación local mía y moría con "SDK location not found". Es un problema propio del self-hosted: en `ubuntu-latest` la máquina es descartable y nunca hay un daemon ajeno. Se saca al daemon de la ecuación escribiendo `local.properties`, que AGP mira **antes** que la variable. |

Y una cosa que hay que saber si algún día se toca esto: **lo que se le pone al entorno del
proceso del runner no llega a los steps**. Está medido, no supuesto — el guard del propio
workflow falló con "ANDROID_HOME no está definido" mientras `/proc/<pid>/environ` del
servicio la mostraba puesta. La plantilla oficial de la unidad tampoco carga ningún `.env`.
Por eso la ruta del SDK viaja por `MARVIN_ANDROID_SDK`, el mismo mecanismo que ya funciona
para `MARVIN_RUNNER`, y el workflow sigue sin rutas de ninguna máquina adentro.

Primera corrida verde de punta a punta: 2 min 11 s, los tres jobs.

Un cuarto hallazgo, y el más incómodo, apareció recién al preguntármelo: **`make all` no
cubría el bridge de Go**. Yo había concluido que Go no estaba instalado —`command -v go` no
lo encuentra— y con esa premisa falsa di por hecho que sus chequeos sólo podían correr en
CI. Está instalado en `~/toolchain/go`, fuera del `PATH`, y `build-aar.sh` ya lo descubría
ahí desde siempre. O sea que el target existía a medias en otro script y el Makefile no lo
usaba: `gofmt`, `vet` y los tests con `-race` eran los únicos sin forma de verificarse antes
de pushear. Ahora hay `make go`, la búsqueda vive en `scripts/go-bin.sh` (misma forma que
`jdk17.sh`) y `all` la incluye, así que "todo lo que no necesita dispositivo" por fin es
todo.

### Los E2E como gate de PR, y lo que costó

Los 29 tests instrumentados ya corren en cada PR (`.github/workflows/e2e.yml`), verde en el
runner en ~5 min. Va en un workflow aparte del gate rápido, se **saltea** —no falla— si no
hay runner propio, y limpia con `if: always()` porque el runner es una máquina de verdad
donde el estado sobrevive al job.

Ponerlo a andar sacó tres cosas, y ninguna era el emulador:

**KVM venía de una ACL, no de un grupo.** `/dev/kvm` tenía `user:jporta:rw-`, que la pone
logind para la **sesión activa**. El runner corre con linger, o sea pensado para andar sin
nadie logueado. Sin KVM el emulador no falla: arranca por software y la suite se va a
timeout — síntoma lejísimos de la causa. El arreglo (estar en el grupo `kvm`) va en
`setup-runner.sh`, no aplicado a mano, y **aplica recién al reiniciar**: los grupos del
gestor de servicios de usuario se fijan al nacer y, con linger, nace con el boot. Se
confirmó solo horas después: tras un reinicio la ACL quedó en `lightdm` —nadie había
entrado al escritorio— y el gate corrió igual, por el grupo.

**La limpieza dejaba emuladores zombis.** `adb emu kill` habla por adb, así que no sirve
justo cuando hace falta: si el emulador no llegó a bootear, no hay con quién hablar.
Quedaron dos vivos y envenenaron la corrida siguiente, que dio **6 tests rojos con errores
de conexión al fixture** — un síntoma que no se parece en nada a su causa. Ahora se termina
por PID si sobrevive, con el patrón entre corchetes para que `pgrep` no matchee la propia
línea de comandos (sin eso el script se mata a sí mismo).

**Sin sesión gráfica no hay GPU.** Comprobado con `env -u DISPLAY`: `-gpu host` no bootea
**ni se degrada solo** (`libX11-xcb` → `Failed to get EGL display`). El camino acelerado del
emulador pasa por X11, así que el job usa `swiftshader_indirect`; un X headless pediría root
y Xvfb daría GL por software igual, sin ganancia.

Vale anotar cómo se llegó: la primera explicación del fallo —"el runner no hereda DISPLAY"—
se escribió con más confianza de la que los datos daban, y los mensajes de error no
coincidían con esa firma. La causa igual de plausible era el zombi. La decisión quedó igual;
el motivo hubo que corregirlo.

### El "chat roto" del celular: no era la app, y el arreglo es un default

El sintoma reportado desde el telefono —pantallas mezcladas al navegar dialogos de
opciones, que ninguna tecla arregla— resulto no ser corrupcion: era el **copy mode de
tmux mostrando historial congelado**. La TUI clasica de Claude Code no usa pantalla
alternativa, asi que cada repintado empuja cuadros viejos al historial; con `mouse on`,
el gesto de leer con el dedo entra a copy mode y te deja mirando esa pila (el indicador
`[100/1888]` de las capturas del usuario era la posicion en el historial). En copy mode
las flechas mueven el cursor de copia, no el dialogo — por eso "no se arreglaba".

Se descartaron con medicion las otras hipotesis: el motor vendorizado renderiza 26/26
renglones identicos a pyte con el mismo flujo, y el A/B de synchronized output no cambia
nada (tmux ni siquiera lo propaga hacia afuera).

El arreglo es el modo fullscreen de Claude Code (pantalla alternativa): el dedo scrollea
la transcripcion VIVA (claude captura el mouse y maneja la rueda), los dialogos navegan
en vivo, el historial no junta basura, y la seleccion larga MEJORA (arrastrar al borde
scrollea la vista de claude mientras selecciona; ademas claude copia al buffer de tmux y
por OSC 52 — que la app ya implementaba). Verificado en el emulador con gestos reales.

Como es un ajuste del settings.json de cada usuario del host, la app lo convierte en
default de SUS sesiones inyectando `CLAUDE_CODE_NO_FLICKER=1` (la variable equivalente
documentada) al crear la sesion tmux — mismo mecanismo que MARVIN_DISPLAY. Las
terminales de PC no cambian, y `/tui default` lo revierte por sesion. El E2E de conexion
ahora verifica ambas variables en el env global de tmux.

Riesgos conocidos del modo (relevados de issues y doc oficial): flicker con tmux < 3.7
(este host tiene 3.4; cosmetico, se va con el upgrade), scroll a saltos (ajustable con
/scroll-speed), y en PC la seleccion nativa del terminal pasa a necesitar Shift+arrastre.

### Resuelto: el manual al día con el uso real (ago-2026)

`gen-manual.py` quedó actualizado con todo lo que la app hacía y el manual no contaba:
la sección "Claude Code en el celular" (fullscreen por defecto, scroll en vivo, Ctrl+O,
PgUp/PgDn, `/tui default`), la subsección de copia Sel vs nativa (el texto de abajo,
portado), el interruptor "⚡ Dictado siempre encendido" y los colores del micrófono,
pinch-zoom, renombrar pestañas (long-press), el diálogo de cierre matar/dejar viva,
la fila oculta del `›` (Home/End/PgUp/PgDn), el consentimiento OSC 52 >100 KB, que
confiar en una clave nueva reconecta todas las pestañas, y el tope de 8 MB del visor
de documentos. La tabla de la barra usa ahora los íconos vectoriales reales
(`scripts/manual-assets/ic_*.png`, variante negativo, generados de los VectorDrawable).

Lo que motivó la subsección de copia, salido de una pregunta del usuario:

- **Cuándo usar cada copia**: el long-press nativo selecciona sobre el búfer local de la
  app, y con tmux en el medio cada fila llega redibujada por separado — el motor nunca ve
  la marca de "línea envuelta", así que un comando de 2+ líneas se copia cortado (un `\n`
  duro por línea visual, más el relleno de espacios). Sirve para fragmentos de una línea o
  para copiar literalmente lo que se ve.
- **El Sel delega la selección en el host**: el dedo se reporta como botón de mouse, tmux
  (o Claude Code en fullscreen) selecciona sobre SUS líneas lógicas y al soltar copia la
  línea entera, unida y sin relleno — lista para pegar y ejecutar. Es el camino
  recomendado para comandos y cualquier texto que se vaya a reutilizar.
- Mencionar que por SSH la copia viaja por OSC 52 al portapapeles del teléfono (con el
  diálogo de consentimiento para copias grandes), y que en claude fullscreen el mismo
  arrastre dispara además la copia propia de claude al buffer de tmux.

### Cobertura de tests que falta
Los arreglos de P4 entraron con tests propios: `test/host/` pasó de 16 a 31 (bloques
idempotentes de `setup-host`, escritura de units, señal de actividad del dictado en vivo).
Lo que sigue sin cobertura automática es `display-entrypoint`, que se verificó a mano
matando cada proceso dentro del contenedor. El
fixture ya existe y puede hospedarlos.

### Demo de primer uso (coach marks) — ago-2026

La app no tenía onboarding: la vinculación por QR estaba escondida detrás de la línea de
VPN y la fila Shift/Sel/Dictar ni se veía (el teclado del sistema la tapa al entrar).
Ahora cada pantalla dispara su mini-demo la primera vez que se entra: burbujas con
chaflán (la cápsula del isotipo, sin esquinas redondeadas) que resaltan el lugar con un
agujero en el scrim y lo explican. Bloquea y narra: tocar avanza, "Saltar demo" corta.
Hosts (4 pasos), terminal (12 — con el teclado suprimido para que la fila Shift se vea, y
una burbuja que explica que va y viene con el teclado), docs (2), visor (1 + 3 del panel;
la explicación del Zoom vive en la burbuja de Escritorio porque el botón está INVISIBLE
en modo Pantalla y su paso se saltearía siempre). Replay: long-press en "_hosts".

Implementación: `Tour.kt` (gating por prefs `tour_*`, kill-switch `tour_off` que siembran
los E2E, lógica pura testeable) + `TourOverlay.kt` (scrim + agujero PorterDuff CLEAR +
burbuja; blancos como lambdas re-evaluados porque `refrescarBarra()` reconstruye todo).
Los diálogos SSH quedan en su propia ventana ENCIMA del overlay: se resuelven y la demo
sigue. La caja negra descarta la demo oportunista con `saltarDemoSiAparece()` (el estado
persiste entre corridas, no se puede exigir que esté).

**Lección que costó 1h40 de suite colgada**: un `OnGlobalLayoutListener` que setea
`layoutParams` (o invalida) incondicionalmente se retroalimenta — cada set dispara otro
layout, el listener vuelve a correr, y el main thread queda girando traversals con la
cola siempre detrás de la barrera de sync. Ni ANR ni crash: cuelgue silencioso, y
`onActivity` de los tests bloquea sin que el timeout del test pueda dispararse. El fix es
hacer el listener idempotente (comparar antes de setear) y, en los tests que dependen de
`onActivity`, un `Timeout` de JUnit que corre el test en otro hilo: mejor rojo que un
gate eterno.


## Evaluación por perfiles (ago-2026) — hallazgos consolidados

Programa de 8 perfiles en 4 olas (usuario final, UX/DevEx, dev, QA, DevOps senior, SRE,
seguridad ofensiva, arquitecto de IA), corridos como agentes Claude sobre la app real
(emulador + fixture), cada uno con su método formal y con el traspaso de handoffs entre
olas. Método y protocolo: `docs/programa-evaluacion-personas.md`.

**Veredicto de conjunto:** RemoteMarvin es un transporte de altísima fidelidad hacia una
superficie de control que no modela — reproduce fielmente los bytes de Claude (y por eso
NO hereda el bug de decisiones duplicadas del Claude Code remoto), pero la interacción más
crítica de su propio modelo de amenaza (un humano aprobando la acción de un agente con
shell de root, desde un teléfono) quedó sin jerarquizar. **Sin críticos de seguridad**
(la app está endurecida; el caso `pocketshell` no se repite). Los flancos son el
**andamiaje de aprobación agéntica** y el **onboarding**; las **skills están bien
diseñadas**. El tema transversal que confirmaron 6 olas independientes: *la superficie de
confianza está desacoplada de la decisión/estado de confianza*.

### A · Defectos a corregir (por severidad; ✅=confirmado en la app/código, ⏳=sospechado)

| Sev | Hallazgo | Confirmado por | Anclaje |
|---|---|---|---|
| 4 | **✔ RESUELTO (WS-A)** · **Chrome "conectado" desacoplado del estado real**: barra/tour/pestaña se pintan del extra del Intent, no de `authenticateWithPublicKey`; quedan "conectado" con auth rechazada, host caído o modo avión, y disparan el tour "Host conectado 1/12" sobre una conexión muerta ✅ | Dev+QA+SRE+UX (el tema transversal) | `MainActivity.kt:354-371`, `SshTerminalSession.kt:83` |
| 4 | **La pantalla de aprobación de Claude colapsa en el celu (ASI09)**: con teclado arriba el pty es 46×10; el diff se empuja fuera y solo sobreviven visibles/tappables "1. Yes / 2. No"; líneas envueltas pierden `+/-` → aprobable a ciegas ✅ | Arq. IA (cuantificado), Dev (lo anticipó) | terminal / `SshTerminalSession.kt:108-111` |
| 3 | **Onboarding — dependencia circular del manual**: la demo promete el manual "en Documentos", que solo abre conectado, y conectar exige autorizar la clave que el manual explica ✅ | Usuario final, UX, DevOps | demo `HostsActivity` / manual |
| 3 | **Onboarding — autorización sin comando pegable**: el diálogo muestra la clave y "agregala a authorized_keys" sin `echo … >> …` listo ni cómo llevar la clave del celu a la PC (DevOps confirmó: el diálogo de la app es el lugar; preparar authorized_keys de antemano NO es factible) ✅ | Usuario final, UX, DevOps | diálogo "Falta autorizar" |
| 3 | **Paso `/plugin install` ausente del README** (solo en el manual) → dev que sigue el README se queda sin skills = el agente no sabe sus capacidades ✅ | DevOps, Arq. IA | `README.md:79` |
| 3 | **Callejón sin salida de nombres con comilla**: `raro'nombre.txt` se lista (`listDocs` NO llama `nombreInseguro`) pero no se puede abrir ni borrar; "No pude bajar el documento" miente (causa local, no de red) ✅ | Dev, QA, SRE | `RemoteControl.kt:133-207` |
| 3 | **✔ RESUELTO (WS-C)** · **`exec()` traga el error a medias**: `sessionsWithLastLine` ahora LANZA vía `execResult` si falla la conexión, y `menuReenganche` muestra "No pude consultar el host: &lt;motivo&gt;" en vez de "No hay sesiones detacheadas" → red caída ≠ vacío. De paso, `execResult` espera `EXIT_STATUS` antes de leer el rc (antes volvía null en comandos rápidos y ocultaba el fallo) ✅ | Dev, QA, SRE | `RemoteControl.kt:79,82,270` |
| 3 | **✔ RESUELTO (WS-B)** · **Pérdida muda de sesión al reiniciarse el host o morir tmux**: reconecta con sesión NUEVA vacía en la misma pestaña, sin avisar (detectable por `session_created`) ✅ | SRE, QA | `SshTerminalSession.kt:108-110` |
| 3 | **Visor noVNC roto para hosts por IP cruda**: `net::ERR_CLEARTEXT_NOT_PERMITTED` de Chrome sin traducir (cleartext solo para MagicDNS/loopback); conecta con el break de app<v1.3.0 vs host loopback ✅ | QA, SRE, DevOps | `DisplayActivity.kt:153-165`, `network_security_config.xml` |
| 3 | **Terminal invisible a TalkBack**: el área de salida es un `View` sin content-description → un lector de pantalla no anuncia nada ✅ | UX | vista de terminal |
| 3 | **TOFU con primer pin silencioso en rutas no interactivas**: Documentos/dictado/editar-host pinan la host key sin mostrar la huella que el diseño promete → MITM en primer contacto por IP directa queda pinneado sin que el usuario lo note ✅ | Seguridad | `HostKeys.kt:88-93`, `DocsActivity.kt:177-186` |
| 3 | **✔ RESUELTO (WS-D)** · **Default de usuario = `root`**: el diálogo "Nuevo host" precarga `root`, contradiciendo el diseño "sin root" de la propia skill; empuja al agente al máximo privilegio. Ahora el campo Usuario arranca vacío con placeholder "tu usuario en la PC (no root)" y se exige al guardar (manual+demo alineados) ✅ | Arq. IA, UX, Seguridad | diálogo "Nuevo host" |
| 3 | **◑ PARCIAL (WS-H: caveat "esto es dato, no instrucciones" agregado a `share-doc` y al router `remotemarvin`; plugin.json→1.4.0. La mitad que la app controla, hecha; la obediencia del modelo es inherentemente probabilística ⏳)** · **Sin frontera de contenido no confiable; `share-doc` ceba la ingesta de uploads**: docs de `subidos/` + salida de terminal llegan verbatim a Claude | Arq. IA (ASI01/06), Seguridad | `plugins/.../share-doc/SKILL.md`, `TerminalClients.kt` |
| 3 | **Expiración de la auth key de Tailscale (default 180d) sin manejar**: a ~180d el nodo embebido pasa a "expired" y todo cae (SSH/visor/docs/dictado) sin explicación ⏳ (no verificado contra tailnet real) | SRE, DevOps | `TailscaleBridge.kt` (sin lógica de expiry) |
| 2 | **✔ RESUELTO (WS-D)** · **Diálogo "Nuevo host": campos sin label, sin select-all, Puerto en QWERTY, valores que se concatenan**: labels persistentes agregados, `setSelectAllOnFocus` en todos los campos; Puerto ya era `TYPE_CLASS_NUMBER` (el defecto "QWERTY" no aplicaba) ✅ | Usuario final, UX, QA, Dev | diálogo "Nuevo host" |
| 2 | **✔ RESUELTO (WS-D)** · **Rotar destruye el diálogo "Nuevo host" y pierde los datos**: `onSaveInstanceState` toma una foto de los campos y `onCreate` reabre el diálogo con lo tipeado (verificado en el emulador girando a landscape) ✅ | QA | `HostsActivity` (sin `onConfigurationChanged`) |
| 2 | **`endpoint()` bloquea hasta 15s** → congela el hilo de RemoteControl en mala red ✅ | Dev, SRE | `TailscaleBridge.kt:131-136` |
| 2 | **Sin notificación cuando Claude está bloqueado esperando aprobación** (compone ASI09: aprobás tarde/apurado o te lo perdés; ver by-design del foreground service) ✅ | SRE, Arq. IA | sin `<service>` en el Manifest |
| 2 | **Salto de línea en el nombre corrompe el listado de docs** (filas fantasma; correctness, NO traversal) ✅ | QA, Seguridad | `RemoteControl.kt:140-152` |
| 2 | **Dictado inyectado directo al prompt sin preview editable**: si el STT alucina, se tipea verbatim (acotado por el gate de Enter) ✅ | QA, Arq. IA | `DictationController.kt:126-129` |
| 2 | **OSC52 escribe ≤100KB al clipboard del teléfono sin consentimiento**: canal de exfil para un Claude inyectado ✅ | Seguridad, Arq. IA | `TerminalClients.kt:145` (umbral 100_000) |
| 2 | **✔ RESUELTO (WS-B)** · **No hay botón "Reconectar SSH" distinto de "Reenganchar"** (el que hay re-attachea tmux) ✅ | UX, Usuario final | barra de pestañas |
| 2 | **◑ PARCIAL (WS-A: la barra ya dice "reconectando…"/"sin conexión"; falta colapsar el texto repetido)** · **PC apagada = "[reconectando…]" infinito sin diagnóstico** ✅ | Usuario final, QA, SRE | terminal |
| 2 | **Observabilidad nula del lado del usuario**: sin logs accesibles, pantalla de diagnóstico ni export; solo `adb logcat` ✅ | SRE | app (sin logging de usuario) |
| 2 | **✔ RESUELTO (WS-C)** · **Mensajes que filtran inglés de la librería**: `RemoteControl.motivoLegible()` normaliza los mensajes de trilead ("problem while connecting", "Connection refused", timeout, "No route to host") a texto propio y accionable ✅ | QA, SRE | `RemoteControl` (`e.message`) |
| 1 | **Sin `FLAG_SECURE`**: terminal/visor quedan en el task-switcher, screenshot-ables ✅ | Seguridad | ninguna Activity lo setea |
| 1 | **Nodos de tailnet no-efímeros huérfanos al desinstalar** (+ sin `teardown-host.sh` para units/linger/bloques/sshd.d/contenedores) ✅ | Seguridad, DevOps | `ts-link-qr.sh:39`, `Makefile` |
| 1 | **`description` de `headed-browser` = 1041 > 1024 chars** (riesgo de truncado por el loader) ✅ | Arq. IA | `headed-browser/SKILL.md` |
| 1 | **Trigger-evals incompletas**: solo `headed-browser` tiene evals; `share-doc` y `remotemarvin` no ✅ | Arq. IA | `plugins/.../skills/*/evals/` |
| 1 | **Diálogos AlertDialog blancos rompen el tema oscuro CRT** ✅ | UX | diálogos nativos |
| 1 | **Bajo contraste en textos de estado/vacío**; chevron `›` con target angosto (~34dp) y content-desc que no cambia al togglear ✅ | UX | key row / textos muted |
| 1 | **Instrucción QR inconsistente**: app dice `docker compose exec gateway ts-link-qr`, manual dice `./scripts/ts-link-qr.sh` ✅ | UX, DevOps | diálogo Tailscale vs manual |
| 1 | **`.env.example` dice "efímera" pero el nodo es no-efímero**; `plugins/remotemarvin/README.md` hardcodea la ruta del autor en `/plugin` ✅ | DevOps | `.env.example:18`, `plugins/.../README.md:22` |
| 1 | **`setup-host.sh` asume `apt-get`** → muere en distro no-Debian ✅ | DevOps | `setup-host.sh:60` |

### B · Decisiones by-design / trade-offs (no son bugs; se documentan para poder revisitarlos)

- **La app es para desarrolladores** — confirmado por el programa (el "usuario no técnico" fue un stress-test deliberado). La jerga y el setup con git/docker/terminal son el público, no un defecto. Lo que sí lastima *a un dev en su primer día* es lo que quedó en Defectos.
- **Sin liveness en background** (no hay foreground service ni wakelock) — trade-off medido por SRE: batería a salvo (no replica el ~80% de drenaje de Termux) a cambio de que la sesión muera con el proceso. La app reconecta en ~4s con scrollback intacto al reabrir. *(El corolario "avisar cuando Claude está bloqueado" sí es un defecto, arriba.)*
- **ssh+tmux sobre mosh** — deliberado y defendible con datos (SRE): prioriza durabilidad de sesión (sobrevive a muerte de proceso, reboot de host y roaming). Mosh daría mejor tipeo en enlace malo pero igual necesitás tmux para persistir.
- **Keystore sin `setUserAuthenticationRequired`** — trade-off consciente por UX de reconexión; residual documentado: un teléfono robado y **desbloqueado** puede USAR (no extraer) la clave SSH y la authkey de Tailscale. Mitigación opcional: biometría para la primera conexión de la sesión.
- **Cleartext HTTP para el noVNC dentro de la tailnet** — por diseño (viaja tunelizado por SSH; el allowlist está acotado a MagicDNS + loopback). El defecto asociado es el *fallback por IP cruda* (arriba), no el cleartext tunelizado.
- **Tope de 8 MB del visor de documentos** — por seguridad de memoria (mitiga el OOM que Dev sospechaba); residual: bloquea imágenes/PDF legítimos más grandes, que se miran por la terminal.
- **`setup-host.sh` escribe `PasswordAuthentication no` global al sshd** — endurecimiento defendible; el defecto es que queda *sticky* sin teardown (cubierto arriba).

### C · Correcciones y refutaciones durante la evaluación (el proceso auto-corrigiéndose)

- **Borrado de nombre con comilla**: QA lo reportó como falla *muda*; SRE verificó que **muestra** "No pude borrar: nombre de archivo inválido" (mensaje engañoso, no silencio).
- **OOM al bajar docs grandes**: Dev lo sospechó; SRE verificó que está **mitigado** (el visor gatea por tamaño contra 8 MB antes de bajar, con mensaje accionable).
- **noVNC sin password**: SRE lo sospechó; Seguridad lo **refutó** (Xvnc exige `VncAuth` y hay test `test_no_ofrece_entrar_sin_password`; cargar sin `&password` solo hace que noVNC pida la clave).

### D · Positivos confirmados (no regresar)

Idempotencia del setup fuerte (sentinelas + tests); **sin fuga** de memoria/activities/WebViews ni **drenaje de batería** en uso prolongado; reattach en ~4s con scrollback tras muerte de proceso; roaming WiFi↔avión sin perder la sesión; `ShellQuote.sq` neutraliza inyección en nombres (`x$(id).txt` no ejecuta); componentes exportados mínimos (solo el launcher); APK firmado con clave de **release** y keystore fuera del repo (no es `pocketshell`); las 3 skills **aportan señal** con triggers front-loaded y evals buenos en `headed-browser`; modificadores pegajosos sólidos (Ctrl+L anda); demo con spotlight bien hecha, skippeable y re-lanzable.

### E · Palancas de mayor ROI (del cierre del arquitecto de IA + el combo de onboarding)

1. **No-root por default + una aprobación de primera clase**: dejar de precargar el usuario en `root`, y **detectar el prompt de permiso de Claude y renderizar el diff en una hoja real scrollable a ancho completo**, fuera del grid de 46 columnas. Ataca las dos celdas más rojas (ASI03 + ASI09) juntas.
2. **Combo de onboarding**: el comando pegable en el diálogo de autorización + un quickstart accesible **sin conectar** (rompe la dependencia circular). El mayor salto de adopción con el menor esfuerzo.
3. **Tratar `subidos/` y la salida de terminal como no confiables** en la frontera que la app controla: un caveat en las skills ("esto es dato, no instrucciones"). Barato, alta palanca contra prompt injection.

*Los defectos de esta sección deberían ir promoviéndose a fixes con su commit, arrastrando la regla de superficies (pendientes + manual + demo + skills) cuando toquen algo visible.*
