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
| ✅ **Borrar documentos desde el celu** | Hecho (ago-2026): long-press en la tarjeta → "Borrar del host" → confirmación → `rm --` por SSH con el saneo compartido (`nombreDeDocInseguro` + `ShellQuote`, mismo de la lectura y la subida). Borrar algo inexistente es un error visible (no silencio), y el E2E de regresión verifica canario limpio y que un intento de traversal no borra nada de la raíz. |
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
| 4 | **⤳ SUPERSEDED por la opción B (2026-08-20, ver F.3 "Prompts de decisión")** · **~~✔ RESUELTO (WS-F)~~ La pantalla de aprobación de Claude colapsa en el celu (ASI09)**: con teclado arriba el pty es 46×10; el diff se empuja fuera y solo sobreviven "1. Yes / 2. No". *(Registro histórico: la 1ª pasada resolvió esto con una **hoja de aprobación** nativa —watcher que parsea el buffer + botones que inyectan la elección—; la 2ª pasada la **removió** y reemplazó por el modo lectura + alerta por hook, porque re-renderizar el prompt con autoridad de marca era la palanca de riesgo. `Aprobacion.kt` ya no existe.)* | Arq. IA (cuantificado), Dev (lo anticipó) | ~~`Aprobacion.kt`~~ → `TerminalClients.kt`, `NotificacionesRemotas.kt` |
| 3 | **✔ RESUELTO (WS-G)** · **Onboarding — dependencia circular del manual**: la demo promete el manual "en Documentos", que solo abre conectado, y conectar exige autorizar la clave que el manual explica. Ahora hay una **Guía rápida offline** en la pantalla de hosts ("$_ ¿Primera vez?"): 5 pasos (setup del host, autorizar clave, Tailscale, agregar host, /plugin) con comandos copiables, accesible sin conexión ✅ | Usuario final, UX, DevOps | demo `HostsActivity` / manual |
| 3 | **✔ RESUELTO (WS-E)** · **Onboarding — autorización sin comando pegable**: el diálogo muestra la clave y "agregala a authorized_keys" sin `echo … >> …` listo. Ahora los diálogos de auth (`faltaAutorizar` y `mostrarClavePublica`) ofrecen "Copiar comando" (mkdir+echo+chmod en un solo pegado) y "Copiar solo la clave"; manual alineado. Verificado en el emulador contra el fixture (auth rechazada → diálogo nuevo) ✅ | Usuario final, UX, DevOps | diálogo "Falta autorizar" |
| 3 | **✔ RESUELTO (F2)** · **Paso `/plugin install` ausente del README**: agregada la subsección "En Claude Code (una vez)" con `/plugin marketplace add <ruta-del-repo>` + `/plugin install remotemarvin@remotemarvin`, tras el paso de authorized_keys ✅ | DevOps, Arq. IA | `README.md:79` |
| 3 | **✔ RESUELTO (F1)** · **Callejón sin salida de nombres con comilla**: `raro'nombre.txt` se listaba pero no se podía abrir ni borrar, y el error mentía causa de red. Ahora `listDocs` marca `Doc.soportado=false` para nombres inseguros; la tarjeta se muestra **deshabilitada** ("nombre no soportado — renombralo en el host") y al tocarla explica la causa LOCAL, en vez de "No pude bajar". E2E lo verifica ✅ | Dev, QA, SRE | `RemoteControl.kt:133-207` |
| 3 | **✔ RESUELTO (WS-C)** · **`exec()` traga el error a medias**: `sessionsWithLastLine` ahora LANZA vía `execResult` si falla la conexión, y `menuReenganche` muestra "No pude consultar el host: &lt;motivo&gt;" en vez de "No hay sesiones detacheadas" → red caída ≠ vacío. De paso, `execResult` espera `EXIT_STATUS` antes de leer el rc (antes volvía null en comandos rápidos y ocultaba el fallo) ✅ | Dev, QA, SRE | `RemoteControl.kt:79,82,270` |
| 3 | **✔ RESUELTO (WS-B)** · **Pérdida muda de sesión al reiniciarse el host o morir tmux**: reconecta con sesión NUEVA vacía en la misma pestaña, sin avisar (detectable por `session_created`) ✅ | SRE, QA | `SshTerminalSession.kt:108-110` |
| 3 | **✔ RESUELTO (WS-J)** · **Visor noVNC roto para hosts por IP cruda**: `net::ERR_CLEARTEXT_NOT_PERMITTED` de Chrome sin traducir. Ahora `onReceivedError` detecta el cleartext y muestra un overlay propio que explica qué pasó y cómo verlo (activar Tailscale o actualizar el host para tunelizar por SSH), en vez de la página cruda de Chrome; el fallback al directo sólo se intenta desde el túnel. E2E `DisplayE2ETest` verifica el overlay contra IP cruda. (Android no soporta rangos CIDR en `network_security_config`, así que el lever es traducir + preferir el túnel, no ampliar el cleartext) ✅ | QA, SRE, DevOps | `DisplayActivity.kt:153-165`, `network_security_config.xml` |
| 3 | **✔ RESUELTO (F3)** · **Terminal invisible a TalkBack**: desde `MainActivity` (sin tocar el módulo GPL) la terminal ahora es `importantForAccessibility=YES` y su `contentDescription` refleja la cola de la salida, actualizada en cada cambio (sólo si hay lector activo, para no pagar el costo si no) ✅ | UX | vista de terminal |
| 3 | **✔ RESUELTO (WS-I)** · **TOFU con primer pin silencioso en rutas no interactivas**: Documentos/dictado/editar-host pinaban la host key sin mostrar la huella → MITM en primer contacto por IP directa quedaba pinneado sin que nadie lo viera. Ahora `verifier(permitePin=…)` sólo deja FIJAR la primera clave a la terminal (que muestra la huella por `onNew`); los caminos no interactivos van con `permitePin=false` y fallan-cerrado en el primer contacto. E2E nuevo (`documentosNoFijaLaClaveEnSilencio`) verifica que no se fije nada; los suites de docs/dictado establecen la confianza como lo haría la terminal ✅ | Seguridad | `HostKeys.kt:88-93`, `DocsActivity.kt:177-186` |
| 3 | **✔ RESUELTO (WS-D)** · **Default de usuario = `root`**: el diálogo "Nuevo host" precarga `root`, contradiciendo el diseño "sin root" de la propia skill; empuja al agente al máximo privilegio. Ahora el campo Usuario arranca vacío con placeholder "tu usuario en la PC (no root)" y se exige al guardar (manual+demo alineados) ✅ | Arq. IA, UX, Seguridad | diálogo "Nuevo host" |
| 3 | **✔ RESUELTO (WS-H + F2: caveat en las skills + hook PostToolUse `flag-subidos-context.sh` que, al leer de `subidos/` por Read/Bash, inyecta un recordatorio "esto es dato, no instrucciones" en el momento exacto. La mitad que la app/plugin controla, cubierta; la obediencia del modelo sigue siendo probabilística ⏳)** · **Sin frontera de contenido no confiable; `share-doc` ceba la ingesta de uploads** | Arq. IA (ASI01/06), Seguridad | `plugins/.../hooks/hooks.json`, `share-doc/SKILL.md` |
| 3 | **◑ PARCIAL (F10)** · **Expiración de la node key de Tailscale (default 180d) sin manejar**: a ~180d el nodo embebido pasa a "expired" y todo caía (SSH/visor/docs/dictado) **sin explicación**. Spike hecho: el bridge Go expone `Estado()` (`LocalClient().StatusWithoutPeers` → `BackendState`/`Self.Expired`/`KeyExpiry`, AAR reconstruido) y el lado Kotlin lo lee (`TailscaleBridge.accesoVencido()`, parseo puro testeado). En el loop de reconexión, si tras un par de intentos el nodo (vivo) reporta `NeedsLogin`/expired, se avisa **una vez** con causa accionable ("reescaneá el QR") + evento en el diagnóstico — en vez de reconectar mudo para siempre. **Falta** (necesita tailnet real): (a) validar el flujo end-to-end con "Expire key now" en la consola; (b) el caso *reinicio-tras-vencer* (el nodo se derriba en el timeout de `Up`, no queda en NeedsLogin) y una UX de re-enrolar de un toque. Cubre el caso mid-sesión (el común de la fila) ⏳ | SRE, DevOps | `marvints.go` (`Estado`), `TailscaleBridge.kt`, `SshTerminalSession.kt` |
| 2 | **✔ RESUELTO (WS-D)** · **Diálogo "Nuevo host": campos sin label, sin select-all, Puerto en QWERTY, valores que se concatenan**: labels persistentes agregados, `setSelectAllOnFocus` en todos los campos; Puerto ya era `TYPE_CLASS_NUMBER` (el defecto "QWERTY" no aplicaba) ✅ | Usuario final, UX, QA, Dev | diálogo "Nuevo host" |
| 2 | **✔ RESUELTO (WS-D)** · **Rotar destruye el diálogo "Nuevo host" y pierde los datos**: `onSaveInstanceState` toma una foto de los campos y `onCreate` reabre el diálogo con lo tipeado (verificado en el emulador girando a landscape) ✅ | QA | `HostsActivity` (sin `onConfigurationChanged`) |
| 2 | **✔ RESUELTO (F5)** · **`endpoint()` bloquea hasta 15s** → congelaba el hilo de RemoteControl en mala red: ahora, tras una primera espera de 15s sin que el nodo levante, las llamadas siguientes esperan sólo 1s (fail-fast) y caen al directo, en vez de colgar 15s cada una; se resetea al re-escanear el QR ✅ | Dev, SRE | `TailscaleBridge.kt:131-136` |
| 2 | **⤳ SUPERSEDED por la opción B (2026-08-20, ver F.3 "Prompts de decisión")** · **~~✔ RESUELTO (F7)~~ Sin notificación cuando Claude está bloqueado esperando aprobación** (componía ASI09): *(Registro histórico: la 1ª pasada lo resolvió posteando una notif cuando el **parser del buffer** detectaba el prompt con la app en background. La 2ª pasada lo reimplementó sobre el **hook `Notification`/`permission_prompt`** de Claude —host-side, sin parsear pantalla— en `NotificacionesRemotas`; el parser y su notif se removieron con la hoja.)* Único residuo vigente: el techo "app muerta en Doze/LMK" (sin foreground service, la notif no llega). | SRE, Arq. IA | ~~`Aprobacion.kt`~~ → `NotificacionesRemotas.kt`, `marvin-notify.sh` |
| 2 | **✔ RESUELTO (F1)** · **Salto de línea en el nombre corrompe el listado de docs** (filas fantasma): el listado pasó de newline-delimited a **NUL-delimited** (`find -printf '…\0'`, parse por `\u0000`), así un `\n` en el nombre ya no parte el registro. E2E verifica que no aparezcan filas fantasma ✅ | QA, Seguridad | `RemoteControl.kt:140-152` |
| 2 | **✔ RESUELTO (F6)** · **Dictado inyectado directo al prompt sin preview editable**: al soltar el mic ya no se escribe directo. La burbuja (no-modal) muestra la transcripción completa con **Descartar / Insertar** ("insertar-para-editar": la corrección fina se hace en el prompt tras insertar). El texto se **sanea** antes de insertar (todo carácter de control, incl. `\n`, pasa a espacio — cierra el Enter implícito que el gate de Enter no cubría) y se inserta **sin Enter**. El destino se congela al soltar el mic (no se escribe en la pestaña equivocada) y un preview pendiente no traba el micrófono. Unit test de `sanitizarDictado` (`\n`/CR/TAB/BEL → espacio) ✅ | QA, Arq. IA | `DictationController.kt` (mostrarPreview / sanitizarDictado) |
| 2 | **✔ RESUELTO (F3)** · **OSC52 escribe al clipboard sin consentimiento**: ahora toda copia del host muestra un aviso ATRIBUIDO al host ("El host copió N car. al portapapeles", distinto del "Copiado" del usuario) para que una copia inesperada se note, y el umbral de confirmación bajó de 100 KB a 20 KB. E2E del caso chico (copia + contenido) y grande (pide confirmación) ✅ | Seguridad, Arq. IA | `TerminalClients.kt` (umbral 20_000) |
| 2 | **✔ RESUELTO (WS-B)** · **No hay botón "Reconectar SSH" distinto de "Reenganchar"** (el que hay re-attachea tmux) ✅ | UX, Usuario final | barra de pestañas |
| 2 | **✔ RESUELTO (WS-A + F5)** · **PC apagada = "[reconectando…]" infinito sin diagnóstico**: se anunciaba en CADA intento del backoff. Ahora se anuncia UNA vez por episodio (flag que se resetea al reconectar); la barra ya muestra el estado real. E2E cuenta que aparezca una sola vez ✅ | Usuario final, QA, SRE | terminal |
| 2 | **✔ RESUELTO (F8)** · **Observabilidad nula del lado del usuario**: sin logs accesibles, pantalla de diagnóstico ni export; solo `adb logcat`. Ahora hay un **ring buffer en memoria** (`Diagnostico`, tope 200, nada sensible ni contenido de terminal) que registra los hitos de conexión —conectando/conectado/reconectando/caído, auth-fail, clave del host fijada/cambiada, sesión perdida, cortes con motivo— emitidos desde `SshTerminalSession`. Una **pantalla de diagnóstico** (`DiagnosticoActivity`, toque largo en la barra de host) los muestra más reciente arriba, en vivo, con **Compartir** (share sheet) y **Limpiar**. Unit del buffer (orden/tope/export) + E2E (conectar registra el evento y la pantalla abre) ✅ | SRE | `Diagnostico.kt`, `DiagnosticoActivity.kt`, `SshTerminalSession.kt` |
| 2 | **✔ RESUELTO (WS-C)** · **Mensajes que filtran inglés de la librería**: `RemoteControl.motivoLegible()` normaliza los mensajes de trilead ("problem while connecting", "Connection refused", timeout, "No route to host") a texto propio y accionable ✅ | QA, SRE | `RemoteControl` (`e.message`) |
| 1 | **◑ PARCIAL (F3: FLAG_SECURE aplicado a la pantalla del QR de Tailscale — la auth key es un secreto de un solo uso que aparece en el preview de cámara. La terminal/visor/docs se dejaron LIBRES a propósito, por decisión del usuario, para poder sacar capturas de bug)** · **Sin `FLAG_SECURE`**: terminal/visor quedan en el task-switcher, screenshot-ables ✅ | Seguridad | `PortraitCaptureActivity` |
| 1 | **✔ RESUELTO (F9)** · **Nodos de tailnet no-efímeros huérfanos al desinstalar** (+ sin `teardown-host.sh` para units/linger/bloques/sshd.d/contenedores): nuevo `scripts/teardown-host.sh` (+ `make teardown-host`) que deshace el setup — quita las 3 units de usuario (`quitar_unidad`: stop+disable+rm), los helpers de `~/.local/bin`, los bloques con sentinelas de `~/.tmux.conf` y `~/.ssh/config` (`quitar_bloque_sentinelas`, conserva el resto), el drop-in `sshd_config.d/remotemarvin.conf`, el linger, y `docker compose down` (`-v` con `--purgar-datos`). A propósito **NO toca `authorized_keys`** (lección: filtrar por etiqueta se llevaba otra clave) **ni borra los nodos del tailnet** — eso queda como pasos guiados (consola de admin, con los hostnames). Tests: `quitar_bloque_sentinelas`/`quitar_unidad` + teardown de punta a punta en sandbox (no toca lo ajeno) ✅ | Seguridad, DevOps | `scripts/teardown-host.sh`, `setup-host-lib.sh`, `Makefile` |
| 1 | **✔ RESUELTO (F2)** · **`description` de `headed-browser` = 1041 > 1024 chars**: recortado a 1003 (se sacó el paréntesis final `(display on DISPLAY :99, noVNC :6080)`) ✅ | Arq. IA | `headed-browser/SKILL.md` |
| 1 | **✔ RESUELTO (F2)** · **Trigger-evals incompletas**: creados `share-doc/evals/trigger_eval.json` y `remotemarvin/evals/trigger_eval.json` (10 pos + 10 negativos adversarios, mismo schema que headed-browser) ✅ | Arq. IA | `plugins/.../skills/*/evals/` |
| 1 | **✔ RESUELTO (F4)** · **Diálogos AlertDialog blancos rompen el tema oscuro CRT**: overlay oscuro (`ThemeOverlay.Remoteclaude.Dialog` vía `alertDialogTheme`/`materialAlertDialogTheme`) para todos los AlertDialog nativos; verificado en el emulador (fondo petróleo, texto claro, botones verdes) ✅ | UX | diálogos nativos |
| 1 | **✔ RESUELTO (F4)** · **Bajo contraste en textos de estado/vacío; chevron `›` angosto y content-desc estático**: `marvin_muted` subido #5E8B7E→#7FA99B; chevron 34→48dp; su content-desc ahora cambia al togglear ("Más teclas"/"Volver a las teclas principales") ✅ | UX | key row / textos muted |
| 1 | **✔ RESUELTO (F4)** · **Instrucción QR inconsistente**: el diálogo de Tailscale ahora dice `./scripts/ts-link-qr.sh` (alineado con el manual) ✅ | UX, DevOps | diálogo Tailscale vs manual |
| 1 | **✔ RESUELTO (F2)** · **`.env.example` dice "efímera" pero el nodo es no-efímero**; `plugins/remotemarvin/README.md` hardcodeaba la ruta del autor: corregidos a "un solo uso (no efímera)" y `<ruta-del-repo>` ✅ | DevOps | `.env.example:18`, `plugins/.../README.md:22` |
| 1 | **✔ RESUELTO (F2)** · **`setup-host.sh` asume `apt-get`**: fallback a dnf/pacman/zypper y mensaje claro si no reconoce el gestor, en vez de morir ✅ | DevOps | `setup-host.sh:60` |

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

## Segunda pasada de evaluación por perfiles (2026-08-20) — post F1–F10 / v1.13.0

Los 8 perfiles volvieron a correr (playbook §5) con memoria de sus hallazgos y un backlog mayormente
resuelto: cada uno **re-verificó sus propios fixes contra código/comportamiento real** (sin confiar
en el ✔) y **atacó las superficies nuevas**. Resultado: **casi todo lo de la 1ª pasada se sostiene**
(SUS 66 → ~72), **sin críticos nuevos**. El tema transversal **mutó**: la 1ª pasada era *"chrome
desacoplado del estado real"* (cerrado, WS-A); esta es **"chrome nativo que le presta autoridad de
marca a contenido no autenticado"** — la app dejó de ser un pipe crudo y empezó a interpretar los
bytes de Claude (hoja de aprobación, notificación, labels de procedencia).

### F.1 · Re-verificados RESUELTOS y que SOSTIENEN (confirmado en vivo salvo nota)
WS-A (barra roja real, sin demo hasta conectar), F1 (`raro'nombre` deshabilitado), F3 (TalkBack lee
la salida; FLAG_SECURE en el QR), F5 (`endpoint()` fail-fast; `[reconectando…]` una vez), F6 (preview
robusto, `sanitizarDictado`), F7 (wiring correcto, PendingIntent seguro — ver F.2 por su alcance),
F8 (diagnóstico sólido, **no filtra secretos**), F9 (teardown idempotente, **NO toca
`authorized_keys`**), F10-mid-sesión, WS-D (rotación, default no-root), WS-E, chevron F4, skills F2
(description ≤1024, evals). Diagnóstico y `Estado()` de Tailscale confirmados sin fuga de secretos.

### F.2 · Nuevos / reabiertos (severidad corregida en consolidación)

| Sev | Hallazgo | Confirmado por | Anclaje |
|---|---|---|---|
| **2** | **✅ RESUELTO (v1.16.0) · Regresión F4×WS-G/WS-D**: la Guía rápida offline y el diálogo "Nuevo/Editar host" quedaron con **texto oscuro hardcodeado** (`#222/#444/#666/#111`) sobre el overlay de diálogo oscuro → ilegible (no era falta de `values-night`: la app es mono-tema oscuro). **Cerrado** reemplazando esos `Color.parseColor` por tokens del tema (`R.color.marvin_fg`/`marvin_muted`, y `marvin_petrol` de fondo para el bloque de comando), como ya hacían `showVpnDialog()`/`card()`. Verificado en emulador (ambos diálogos legibles). | UX, Dev | `HostsActivity.kt` (showQuickstart/showEditDialog) |
| **2** | **✅ RESUELTO · `release.yml` no verificaba monotonía de `versionCode`** → un release publicado que Android/Obtainium rechaza como update (falla silenciosa). **Cerrado:** nuevo step `versionCode monotónico` (lee el `versionCode` del último release desde su `body` vía REST API/curl, sin depender de `gh`/`jq` en el runner; falla si no es estrictamente mayor). Se **siembra `versionCode: n` en el body** de cada release (eslabón que faltaba); bootstrap sin dato previo → warnea, no falla. | DevOps | `release.yml` (step `id: ver` + body) |
| **2** | **✅ RESUELTO (v1.20.0) · OSC52: escritura del portapapeles por el host → BLOQUEADA (política A)**. La historia: se endureció en 3 pasadas (confirmar >100KB → toast atribuido → preview del contenido, v1.18.0) **sin cuestionar la premisa**. El usuario preguntó "¿para qué sirve que algo copie que no sea con Sel?" y la respuesta fue: utilidad marginal en esta app + superficie de secuestro de portapapeles (Claude inyectable, contenido no confiable). **Fix v1.20.0:** sólo se copia si VOS lo iniciaste (`copiaIniciadaPorVos`: `isSelectionDragMode()` / `isSelectingText()` / gracia tras apagar Sel); el OSC 52 host-iniciado se **bloquea** con un toast que muestra qué se intentó meter. Los botones "Copiar" de la app son locales (no OSC 52) → intactos. Verificado e2e: Sel → copia; printf OSC 52 sin Sel → bloqueado. **Lección → playbook §1.5** ("cuestioná la premisa, no sólo el mecanismo") + defs de seguridad-ofensiva/arquitecto-ia. | Seg, Arq-IA (reencuadrado por el usuario) | `TerminalClients.kt`, `MainActivity.copiaIniciadaPorVos` |
| **2** | **✅ RESUELTO (v1.17.0) · Discoverability nula del Diagnóstico**: sólo long-press en la barra, invisible justo cuando cae la conexión. **Cerrado** con un acceso ⓘ **siempre visible** en la barra de host (además del long-press, que se conserva); su propio onClick abre el diagnóstico sin disparar el `finish()` del contenedor. Verificado en emulador. | usuario-dev, UX | `MainActivity.kt` (`barraDeHost`) |
| **1** | **✅ `nombreInseguro` no filtraba todo `\p{Cntrl}`** (`\t`): fila fantasma + spoof del label "subido por vos". **NO explotable** (ShellQuote + filtro `/` cierran traversal/inyección, con PoC de Seguridad; no derrota el hook path-based). Corrupción/defensa-en-profundidad. **Cerrado con DOS cambios**: (a) `nombreDeDocInseguro` rechaza todo `\p{Cntrl}` (mismo criterio que `sanitizarDictado`) y (b) el parseo lee los 4 campos de metadatos **desde el final** y el nombre es todo lo anterior. (a) SOLA no alcanzaba —reproducido: el corrimiento pasa ANTES del saneo, así que el nombre llegaba ya recortado en el primer TAB, limpio y `soportado=true`—. Regresión: unit `ListadoDocsTest` (falla antes / pasa después) + E2E `listDocs_unTabEnElNombreNoCorreLosCampos` contra el `find` real (51/51). | Seg, Arq-IA (Dev/QA) | `RemoteControl.kt` (parseo + saneo, ahora top-level) |
| **1** | **✅ teardown no revierte `usermod -aG docker $USER`** ni lo nombra → membresía docker (≈root vía socket) residual tras desinstalar. **Cerrado nombrándolo, NO removiéndolo** (puede ser previa: misma lección que `authorized_keys`): el teardown ahora chequea `id -nG`, y si seguís en el grupo lo lista como paso guiado 3) con `sudo gpasswd -d $USER docker`; si no estás, lo dice y no molesta. `--help` y manual alineados ("las tres cosas que no toca"). Regresión: `test_teardown_host.py` × 2 (nombra el comando **y** verifica que NO ejecuta `gpasswd`/`usermod`) — fallan antes, pasan después. | Seg, DevOps | `teardown-host.sh`, `setup-host.sh:52` |
| **1** | **✅ `marvin-share.sh` copiaba con el basename crudo** (`cp -f -- "$f" "$DEST/"` preservaba el nombre de origen): el nombre hostil que el fix de la app cierra del lado consumidor **podía nacer acá** y aterrizar en `~/RemoteMarvinDocs`. Defensa-en-profundidad del lado del PRODUCTOR (no explotable e2e: la frontera de la app ya rechaza estos nombres y Claude tiene shell igual; el valor es no CREAR nombres que corran los campos del listado o spoofeen el label de procedencia). **Cerrado SANEANDO** (no rechazando, para que compartir siga andando): nueva `safe_name()` bash —espejo de `safe_name` de `marvin-render` y de `nombreDeDocInseguro` de la app— neutraliza control chars + comilla simple a `_` (barra ya la saca `basename`; vacío/`.`/`..` → `documento`) y **preserva** espacios, acentos, emoji y puntuación; el `cp` ahora escribe a `"$DEST/$name"` explícito. Reproducido primero contra un `$DEST` sandbox (nombre con `\t` + `'` aterrizaba verbatim). Regresión: `test_plugin_scripts.py` × 2 (hostil `informe\t9\t9\t9\ts'.pdf` → `informe_9_9_9_s_.pdf` seguro; legítimo `informe final ñandú 📄.pdf` intacto) — el hostil falla antes / pasa después (verificado con `git stash`). `make host` 59/59 · shellcheck + ruff limpios. | Seguridad (nota) | `plugins/remotemarvin/skills/share-doc/scripts/marvin-share.sh` |
| **1** | **✅ RESUELTO (v1.17.0) · Ring buffer no sobrevivía a la muerte del proceso** → sin post-mortem de crash/OOM/reboot. **Cerrado** espejando `VigiaUi`: `Diagnostico` persiste los eventos que NO son INFO (avisos/errores) a `filesDir/eventos-conexion.log` (tope 64KB) y los re-carga como "conexión (previo)" al arranque (`cargarPersistidos` antes de `init`, borra el archivo). Verificado e2e en emulador (cortar red → AVISO persistido → force-stop → reabrir muestra el previo) + unit `DiagnosticoTest`. | SRE | `Diagnostico.kt`, `MainActivity.kt:110` |
| **1** | **✅ menores — CERRADOS**: `vncPassword()` mudo → degradación (riesgo de auth **REFUTADO**, no era defecto); quickstart sin URL/one-liner **✅ cerrado** (ver fila 539); **✅ `DictationController.vivo` ahora `@Volatile`** (v1.18.0); **✅ el dictado ya no queda huérfano si cerrás su pestaña** → el intento v1.18.0 (chequear el flag `cerrada` en Insertar) NO servía: `finishIfRunning()` es no-op para SSH y `cerrada` nunca se prendía. **Fix real v1.19.1:** se rastrea la pestaña del dictado en curso (`sesionEnJuego`) y al cerrarla (`TabsController.alCerrarTab` → `DictationController.tabCerrado`) se cancela grabación/STT y se saca la burbuja; el hilo del STT no muestra un preview colgado. Cubre cerrar durante la grabación, durante el round-trip o con el preview visible; **✅ "Limpiar" del diagnóstico ahora pide confirmación** (v1.17.0); quickstart sin URL/one-liner; **✅ `flag-subidos-context.sh` sobre-disparaba** ante cualquier Bash que mencione la ruta → ahora sólo ante comandos de LECTURA (cat/grep/head/…); **✅ `quitar_bloque_sentinelas` borraba a EOF si faltaba `end`** → ahora exige la sentinela de fin y si no está no toca el archivo (evita data-loss en teardown). | varios | `flag-subidos-context.sh`, `setup-host-lib.sh` |

### F.3 · Ajustes a filas existentes
- **Fila 550 (expiry Tailscale, ◑ PARCIAL) — se AGUDIZA:** el caso **reinicio-tras-vencer** (no cubierto) es en realidad el **más probable** a 180d, porque el proceso se muere seguido (sin liveness en background), así que un proceso vivo 180d continuos es casi imposible; el mid-sesión que F10 cubre es el caso raro. Sigue ◑ PARCIAL.
- **Fila 554 (notif de aprobación, F7) — OBSOLETA:** F7 (hoja + notif por parseo de buffer) se **removió** al resolver ASI09 con la opción B (ver la fila "Prompts de decisión… ✅ RESUELTO" más abajo). El residuo era el techo "app muerta en background" sobre `NotificacionesRemotas`; se atacó con un foreground service opt-in (v1.16.0) que se **revirtió** por decisión del usuario tras confirmar que el best-effort de 1.15 alcanza (ver F.3b #2).
- **Fila 539 (quickstart offline) — ✅ CERRADO (v1.16.0):** el paso 1 ahora incluye el `git clone https://github.com/jporta09/Remoteclaude` + `cd` antes del one-liner de setup (que ya estaba). Queda con URL y comandos copiables.
- **Distribución del APK — RESUELTO** vía `release.yml` + Obtainium (v1.13.0 firmado), con el caveat del gate de `versionCode` (F.2).
- **Prompts de decisión en el celu (ASI09) — ✅ RESUELTO con opción B (`7d37f91`), verificado EN VIVO.** El problema de origen es **aprobar sin leer** (con el teclado arriba el pty es 46×10 y el prompt colapsa a "1. Sí/2. No"). Se remplazó la hoja de aprobación (A1, re-render con autoridad de marca) por DOS mecanismos, **split por criticidad**, tras investigar qué señales expone Claude de verdad:
  - **Gesto en vivo, instantáneo (in-app):** el "modo lectura" baja el teclado apenas aparece un selector. Detección re-anclada en la **firma REAL capturada del PTY** (`hayPromptDeDecision`): cursor `❯` sobre opción numerada + footer `to cancel` (se capturaron los bytes reales del selector de permiso y del de trust con un PTY + pyte). Robusta (estructura real de Claude), falla-segura (si cambia, peor caso no baja el teclado), no confunde listas numeradas ni el spinner ("esc to *interrupt*"). Instantánea porque el render sale por el PTY. `PromptDeDecisionTest`. (Refinado en **v1.15.1**: al tocar para volver a escribir, el teclado sube y **no** se vuelve a bajar con el mismo prompt — el flag "ya reaccioné a este prompt" no se resetea en el tap.)
  - **Alerta "te fuiste", precisa host-side:** hook **`Notification`/`permission_prompt`** (confirmado en el binario `2.1.237` + docs; local, **token-free**, ~6 s de debounce = "parece que te fuiste") → `marvin-notify.sh` appendea a `~/.config/marvin/notify.jsonl` → la app abre un canal SSH persistente (`NotificacionesRemotas`, `tail -F`) que ante `permission_prompt` **con la app en background** postea la notificación (reusa la plomería de F7). Best-effort (atado a la conexión viva; el hueco "app muerta" es el mismo de F7 → foreground service/FCM, aparte).
  - **R1 · ampliado a plan-mode y preguntas (plugin 1.7.0):** el hook `Notification/permission_prompt` NO cubre `ExitPlanMode` ni `AskUserQuestion` (ningún `notification_type` los emite — verificado contra la doc de Claude Code). Se agregó un hook **`PreToolUse`** con matcher `ExitPlanMode|AskUserQuestion` (`marvin-notify-decision.sh`) que appendea una línea `type:"permission_prompt"` (el mismo que la app ya reacciona → **sin cambio de app**) con mensaje según el tool ("Claude te espera para aprobar un plan" / "te hizo una pregunta"). Regresión en `test_plugin_scripts.py`.
  - **CAVEAT operativo (descubierto en el debug de R1, 2026-08-22):** los hooks (y por ende TODAS las notificaciones) sólo disparan desde la sesión de Claude que **tiene el plugin cargado**. El usuario usa dos configs (`claude-work` = `~/.claude`, `claude-personal` = `~/.claude-personal`) y remotemarvin está habilitado **sólo en `claude-personal`**. Si la sesión a la que se conecta la app (p.ej. "term 1") corre `claude` con el config default, NO tiene el plugin → no avisa. `notify.jsonl` es compartido, así que la app recibía avisos de CUALQUIER sesión con el plugin. Para que R1 sirva, la sesión del host debe correr `claude-personal` (o habilitar remotemarvin también en el default). Ver memoria `notif-solo-en-config-claude-personal`. **Refinado en v1.28.3 (2026-08-24, pedido del usuario tras recibir un aviso de una sesión ajena):** `NotificacionesRemotas` ahora **filtra por sesión**: sólo notifica si el campo `session` del aviso (el `#S` de tmux que ya emitía el hook) es una **pestaña de la app** en ese host (mismas prefs `tabs_<hostId>` que persiste `TabsController`; el FGS recibe `hostId` por intent). Un Claude del config con plugin corriendo fuera de las pestañas (otro tmux, o sin tmux) ya no suena el teléfono; queda en `Diagnostico` como "aviso ignorado (sesión ajena)".
  - **Por qué así** (investigado): no hay canal instantáneo **y** soportado para el TUI (el `canUseTool` del SDK exigiría no correr el TUI; el bus UDS cross-session está **cerrado por seguridad**; los demás tipos de Notification están debounceados). El 6 s descalifica el hook para el gesto en vivo (por eso la firma del PTY) pero es virtud para la alerta.
  - **Verificado EN VIVO, cadena COMPLETA:** (a) un `claude` real llevado a un `permission_prompt` dispara el hook `Notification` → `marvin-notify.sh` appendea a `notify.jsonl` (~6 s después del selector, como marca el debounce); (b) en el emulador, la alerta en background postea ("Claude te espera" + mensaje + intent); en foreground se suprime (0 records); el modo-lectura baja el teclado con la firma real (46×10→46×25) y NO con un `1) Yes` pelado. `test_plugin_scripts.py` cubre el contrato del JSON del hook. Removido `Aprobacion.kt` + `AprobacionParserTest` + 2 E2E de la hoja/F7; manual reescrito. Encuadre de producto: la app **degrada a SSH+tmux sin Claude** pero **integra de primera con Claude** (hooks locales, token-free) — no es "agnóstica".

### F.3b · Bugs de uso real de v1.14.0 (reportados 2026-08-21)
- **#2 — la notif "Claude te espera" a veces sólo llega al VOLVER, no en background. INTERMITENTE — foreground service HECHO y REVERTIDO (2026-08-21).** Android estrangula el hilo del `tail -F` con la app atrás → la línea del hook puede leerse recién al resumir. Pero el retest en vivo mostró que **NO es constante**: en la 1.15 el `tail` corre siempre (always-on desde la Activity) y postea best-effort, y en el celu real con la app en background **la notif llegó bien**. Se construyó el fix garantizado —`AvisosService` (foreground service `dataSync` que hostea `NotificacionesRemotas`) + toggle opt-in "Avisos en segundo plano" (default OFF)—, **verificado en emulador** (arranca sin `MissingForegroundServiceType`, notif persistente con "Detener", gating, round-trip del toggle) y publicado como **v1.16.0**; **pero el usuario decidió que no hace falta** (la entrega best-effort le sirve y no vale el costo de notif fija + batería). Se **revirtió**: borrado el release/tag v1.16.0 y `main` reseteado a v1.15.3 (force-push). **Estado shipped = 1.15.3.** La premisa vieja "sin FGS la notif es inútil" resultó falsa (best-effort funciona seguido). Gotcha de diseño para si se retoma: la 1.16 gateaba el `tail` always-on detrás del toggle default-OFF, lo que **regresaba** el default (sin notif alguna) vs la 1.15 → hacerlo **aditivo** (best-effort always-on por default; el toggle sólo AGREGA la garantía), no reemplazar.
  - **✅ RESUELTO v1.19.0 — foreground service ADITIVO (retomado).** El usuario reconfirmó en vivo que el best-effort **pierde** avisos tras un idle real: la línea se escribe pero, en Doze, se cae la conexión y el `tail -n0` al reconectar **saltea** la línea del hueco (no llega ni delayed). Para una alerta "vení, Claude te espera" eso lo hace inservible sin fix. Se retomó el FGS pero **aditivo** (corrigiendo el gotcha): con el toggle **OFF** (default) la `MainActivity` sigue corriendo `NotificacionesRemotas` best-effort (comportamiento de 1.15 intacto); con el toggle **ON** lo hostea `AvisosService` (FGS `dataSync`) y la Activity NO lo lanza (sin duplicar el tail). `EstadoApp.enPrimerPlano` comparte el foco. **Verificado e2e en emulador (Android 14):** toggle OFF → llega "Claude te espera" best-effort (la regresión de la 1.16 NO reaparece); toggle ON → FGS foreground (`types=DATA_SYNC`, notif "avisos activos" con Detener) + llega la notif; sin duplicado. La resistencia al Doze real (lo que el FGS ataca) se valida en el celu. `HostSerializacionTest` (avisosBg). También cubre R1 (plan-mode/preguntas) que sin esto llegaba tarde o se perdía.
- **#1 — la app se "traba" al volver desde otra app, en Claude; a veces. DIAGNOSTICADO 2026-08-21: NO es un cuelgue de UI, es la CONEXIÓN.** El `VigiaUi` (v1.15.0) hizo su trabajo: cuando el usuario lo reprodujo en el celu real, **NO** registró ningún `cuelgue-ui` → el hilo principal NUNCA estuvo colgado. Lo que sí mostró el Diagnóstico: **5 reconexiones** en ~18 min (`conectado` repetido), y el freeze terminó justo en la reconexión. **El usuario refinó el diagnóstico con modo avión:** avión → la app SÍ dice "reconectando" (detecta); pero el freeze real **NO** dice "reconectando" → la app **no detecta** la caída. Es una conexión **half-open** (muere sin RST): el kernel del server sigue ACKeando el TCP, así el keepalive —que sólo escribe, no espera respuesta— NO falla → la app cree que sigue conectada → terminal congelada, a veces para siempre. El backoff no aplica (nunca se detecta), así que el fix v1.15.2 (`reintentarConexionYa`, para el caso *detectado*) NO alcanza. **REPRODUCIDO determinísticamente** en el emulador con **SIGSTOP al sshd** de la conexión (el kernel ACKea, el sshd no reenvía = half-open) → congelado sin "reconectando", tal cual. **Fix v1.15.3 (`forzarReconexion`):** al volver tras >3 s en background, forzar el cierre y reconectar (tmux `-A` reengancha sin pérdida); gateado por tiempo (un vistazo corto no fuerza nada); **sin sonda** (una sonda `openSession` deadlockeaba el `Connection`, cazado en el repro antes de shipear). **Verificado before/after** contra el mismo freeze: v1.15.2 sigue congelada, v1.15.3 reconecta y revive. E2E `forzarReconexion_reconecta`. El **foreground service** (opt-in) sigue siendo el fix completo para no caerse en background (arregla #1 y #2 de raíz). El `VigiaUi` se queda (buen negativo: su silencio + los eventos de conexión del Diagnóstico dieron el diagnóstico).

### F.4 · Palancas de mayor ROI que quedan (del cierre del Arq-IA)
1. ~~**Endurecer la hoja de aprobación** (ASI09/ASI05)~~ — **DESCARTADA** (2026-08-20): la hoja se REMUEVE y se reemplaza por la alerta vía hook `Notification`/`permission_prompt` (opción B, ver F.3). No se endurece lo que se saca.
2. ~~**`\p{Cntrl}` en `nombreInseguro`**~~ — **hecho** (F.2), y no era una línea: hizo falta además leer los campos del listado desde el final.
3. ~~**OSC52: preview del contenido**~~ — **hecho v1.18.0** (§F.2). Y **R2 · legibilidad**: el tamaño de fuente (pinch-zoom) ahora se **persiste** en prefs (`TerminalClients`), así que se agranda una vez y queda — antes arrancaba en 15sp por sesión y había que re-pellizcar.
4. ~~De fondo: **foreground service opcional** para sesiones largas~~ — **PROBADO Y DESCARTADO (2026-08-21):** se implementó `AvisosService` (FGS opt-in, v1.16.0) y se revirtió; el usuario prioriza el best-effort de 1.15 sobre el costo del service (ver F.3b #2). El aviso de expiry mid-sesión y la alerta de decisión siguen best-effort por decisión de producto.

---

## G · Tercera pasada — post v1.14.0–v1.20.0 (2026-08-22)

Los 8 perfiles re-verificaron sus ✅ de §F (todos SOSTIENEN) y atacaron las superficies nuevas (opción B,
freeze half-open, diagnóstico persistente + ⓘ, FGS aditivo, OSC52 política A, R1/hook plan-mode, guard de
versionCode, config-scoping) con el lente **§1.5** del playbook ("cuestioná la premisa"). Todo throwaway,
verificado por código + tests empíricos descartables (el AVD quedó serializado; lo runtime-only va marcado
SOSPECHADO). Método en `programa-evaluacion-personas.md §6`.

### G.1 · El tema transversal cerró un ciclo

1ª pasada: "chrome desacoplado del estado real". 2ª: "chrome que presta autoridad a contenido no
autenticado" (la hoja de aprobación). **3ª: la app hizo lo correcto — QUITÓ la hoja en vez de endurecerla
(opción B, §1.5 aplicado antes de que existiera §1.5) — y la superficie de confianza se movió del *render*
al *canal de alerta*.** Ya no hay una superficie de decisión spoofeable (**A1 retirado**); ahora hay un
**canal "Claude te espera" con falsos negativos**: escucha el host equivocado, calla sin el plugin, pierde
el hueco de reconexión, no distingue sesiones. Ninguno es RCE, pero todos erosionan el supuesto que sostiene
el flujo — "Claude trabaja solo y el celu me avisa cuando se traba" — sobre un agente con shell root.
**ASI09 mutó** de "hoja spoofeable" (resuelta) a "canal que induce un modelo mental erróneo".

**Las 2 palancas de mayor ROI (consenso Arq-IA + SRE + Seguridad):** (1) **hacer el canal de alerta honesto**
— re-apuntar al host activo, releer por cursor de timestamp en vez de `tail -n0`, y cerrar el config-scoping
con un **self-diagnostic del canal en el Diagnóstico** (~80% del valor: "un canal que a veces calla es peor
que no tener canal, porque enseña a confiar"). (2) **desacoplar la copia legítima del OSC52** (copia local
desde `TerminalView.getSelectedText()` + `tmux set-clipboard off`) para bloquear el canal incondicional y
eliminar la ventana de gracia — §1.5 a su conclusión.

### G.2 · Backlog nuevo (priorizado por clúster)

**Clúster A — Honestidad del canal de alerta (el 80% del valor)**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **3** | **Config-scoping: canal MUDO sin señal.** Los hooks sólo disparan si la sesión de Claude tiene el plugin cargado (`remotemarvin` sólo en `claude-personal`, no en el default). Sin él: `notify.jsonl` nunca recibe línea, el `tail -F` corre sano, y la notif fija afirma "avisos activos" — promesa incumplida en silencio. **0 refs a `Diagnostico`** en el canal (verificado). Fix (§1.5): **no más docs — hacer visible el silencio**: instrumentar `NotificacionesRemotas` (connect/auth-fail/línea-recibida) y mostrar salud del canal en `DiagnosticoActivity`. | usuario-final, ux, dev, sre, arq, seg (6) | `NotificacionesRemotas.kt`, `AvisosService.kt`, `hooks.json` |
| **2** | **`AvisosService` escucha el host EQUIVOCADO al cambiar de host.** `onStartCommand` con `if (notifs==null)` → de host A(ON) a B(ON) el service no se reinicia; actualiza el TEXTO de la FGS a "en B" pero el `tail` sigue en A. La notif fija miente. | qa, arq | `AvisosService.kt:45`, `MainActivity.kt:129-137` |
| **2** | **`tail -n0` PIERDE (no demora) el aviso del hueco de reconexión.** Toda línea escrita mientras el canal SSH reconecta (Doze/roaming) se saltea para siempre; R1 comparte el hueco. El FGS mantiene el proceso pero no inmuniza contra caídas de red. Fix: cursor por timestamp. | sre, dev, arq, seg | `NotificacionesRemotas.kt:73-79` |
| **1-2** | **Notif renderiza el `message` de Claude (atacante-controlable) con autoridad de la app.** `notify.jsonl` es escribible por cualquier proceso del usuario (Claude inyectado, append directo); la app lo muestra verbatim en notif HIGH. Acotado post-opción-B (tocar no aprueba). Fix (§1.5): string FIJO, dropear el `message` free-form (el `type` es la única señal útil). | seg, arq | `marvin-notify.sh:21`, `NotificacionesRemotas.kt:104` |
| **1** | **Notif ciega a la identidad bajo multi-sesión.** `NOTIF_ID` fijo + `setOnlyAlertOnce` → dos Claude bloqueados = una notif con el último mensaje, sin host/tab. Incluir host+tab. | qa, arq | `NotificacionesRemotas.kt:99-150` |
| **1** | **Re-emit de notif vieja tras rotación de `notify.jsonl`.** `mv` cambia el inode → `tail -F` relee 200 líneas viejas → notif stale del último prompt. Fix: dedup por `ts`. | sre, seg | `marvin-notify.sh:32-33` |
| **1-2** | **R1 plan-mode sin debounce** → ruido: `PreToolUse` dispara SIEMPRE (sin el proxy 6s del hook `Notification`); un buzz por cada plan/pregunta aun al teclado. | usuario-final | `marvin-notify-decision.sh`, `hooks.json` |

**Clúster B — Robustez de la persistencia del Diagnóstico**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2** | **Acantilado 64KB borra TODO el histórico** (empírico: 1111 ev → 1). `if (f.length()>64KB) f.writeText("")` no rota, wipea — justo bajo un storm de reconexión cuando más lo necesitás. Mismo bug en `VigiaUi`. Fix: rotar conservando la mitad. | sre, qa, dev | `Diagnostico.kt:60`, `VigiaUi.kt:93` |
| **1** | **`Diagnostico.persistir` no thread-safe** (empírico: 8000 ev → 1538 líneas). Corre fuera del `synchronized`; length-check+truncate no atómico; log lossy bajo carga (no crashea). | sre, dev | `Diagnostico.kt:53` |
| **1** | **`VigiaUi` duplica los cuelgues** en el post-mortem: escribe el mismo stack a `eventos-conexion.log` (vía `Diagnostico.registrar`) Y a `cuelgues-ui.log` → aparece 2× al reabrir. | dev, qa | `VigiaUi.kt:85-86` |

**Clúster C — FGS: ciclo de vida y robustez**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2** (latente) | **`dataSync` FGS: cap de 6h/día de Android 15 + sin restart tras reboot.** Con `targetSdk=34` exento; al subir a 35+ el sistema lo mata a las 6h (sin `onTimeout`) y no vuelve tras reboot (sin `RECEIVE_BOOT_COMPLETED`). Rompe "sobrevive todo el día". | sre, devops | `AvisosService.kt`, `AndroidManifest.xml` |
| **1** | **"Detener" del FGS no sticky:** recrear la Activity (rotación) con el toggle ON lo revive; el usuario que pidió "pará" lo ve volver. No hay re-arranque in-app sin reconectar. | qa, ux | `MainActivity.kt:129-137` |

**Clúster D — Freeze #1 residual**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2** | **Half-open en PRIMER PLANO queda mudo.** `forzarReconexion` sólo dispara en `onResume` (gate >3s); un half-open estando adelante (NAT idle, AP que blackholea sin RST) congela la terminal sin ningún camino de detección (keepalive write-only, `ConnectivityManager` sólo ante cambios de red, `VigiaUi` sólo cuelgues del main). Única salida: irte a background >3s y volver. | sre | `MainActivity.kt:311`, `SshTerminalSession.kt:198-207` |

**Clúster E — OSC52 política A: bypass + assurance**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2** (bordea 3) | **BYPASS por modo Sel / gracia 3s.** `copiaIniciadaPorVos()` acepta cualquier OSC52 del host mientras `isSelectionDragMode()` (toggle **persistente**, no evento) o dentro de 3s de apagar Sel → un Claude inyectado que spamea OSC52 secuestra el portapapeles sin marca de bloqueo, con toast "Copiado" que engaña. Sel es "la forma recomendada de copiar" → estado común. Fix (§1.5, **no** endurecer): copia de Sel LOCAL (`getSelectedText`) + `tmux set-clipboard off` → **bloquear TODO OSC52 del host, incondicional**. | qa, seg, arq | `MainActivity.kt:713-717`, `KeypadView.kt:146`, `TerminalView.java:658-679` |
| **2** | **Política A sin test + `ClipboardE2ETest` STALE.** Los tests `unOsc52Chico_copiaAlPortapapeles`/`unOsc52Gigante_pideConfirmacion` (verificado, `:55`/`:75`) afirman el comportamiento PRE-política-A (OSC52 chico copia; diálogo de umbral) que v1.20.0 invirtió; no se actualizaron, y política A entró por **push directo a main** (`e3a2b66`) → el e2e (gatea en PR) nunca corrió. El control de seguridad no tiene test verde. | seg, qa, dev | `ClipboardE2ETest.kt`, `e2e.yml` triggers |

**Clúster F — Dictado**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2** | **`tabCerrado` mid-transcribe derrota el guard `transcribiendo`.** Resetea `transcribiendo=false` con el worker STT en vuelo → un 2º `empezar()` lanza worker concurrente; el clobber/descarte queda acotado por el gate de Insertar + no-auto-Enter (Arq: **no** mueve el threat model), pero puede descartar/pisar un dictado válido. | qa, dev | `DictationController.kt:206-211,152` |

**Clúster G — Distribución / DevOps**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2** | **Repo PRIVADO → la distribución por Obtainium exige un PAT no documentado.** README asume repo público (`../../releases` da 404 sin auth); no menciona el PAT ni guía a un fine-grained read-only → probable PAT `repo` (toda la cuenta) en el celu, sin Keystore hardware. Fix (§1.5): releases públicos (elimina la credencial) **o** fine-grained single-repo con expiry + documentarlo. | devops, seg | `README.md:61-66` |
| **1-2** | **Guard de versionCode FAIL-OPEN.** `curl -sf ... || true` → si el curl falla (5xx/rate-limit) o el body no expone `versionCode:`, cae en bootstrap y **saltea** la monotonía (la falla que existe para prevenir). No distingue "primer release" de "curl falló". Fix: fail-**closed** ante error HTTP (retry→hard-fail); saltear sólo con marcador de bootstrap explícito. | devops, qa, seg | `release.yml:80-93` |

**Clúster H — UX / menores**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2-3** | **Toggle "🔔 Avisos en segundo plano" enterrado, default OFF, sin helper ni badge.** El valor central (avisos confiables) queda en el modo no-confiable (best-effort) sin que el usuario lo sepa. FGS aditivo OK (sin regresión). Falta ayuda inline / prompt en el primer connect / badge en la tarjeta. SUS 72→77 (el techo lo pone esto + el config-scoping). | usuario-final, ux | `HostsActivity.kt:425` |
| **1** | **Toast del bloqueo OSC52 no accionable / jerga.** "(no fue con Sel)" no dice cómo copiar (usar Sel) ni que es una medida de **seguridad**; sorprende al workflow host-side (vim/tmux/script OSC52). | usuario-final, ux | `TerminalClients.kt:52` |
| **1** | **Target del ⓘ (y del chevron) < 48dp** (~36×21dp). WCAG 2.5.8. | ux | `MainActivity.kt` (`barraDeHost`) |
| **1** | **Modo lectura: falso-positivo con TUIs no-Claude** (`gum`/`fzf`/CLIs que usan `❯` sobre opciones numeradas) baja el teclado; consecuencia mínima (tap lo sube, v1.15.1 evita re-drop), no cubierto por test. | usuario-final | `TerminalClients.hayPromptDeDecision` |

**Clúster I — Reabiertos / matices de §F**
| Sev | Hallazgo | Perfiles | Ancla |
|---|---|---|---|
| **2/3** | **`exec()` mudo persiste** (D4, sigue de la 2ª pasada): `vncPassword`/`listSessions`/`killSession`/`setDisplayMode`/`sttMode` no distinguen vacío de fallo; `sessionsWithLastLine` hace doble round-trip vía `listSessions()` → filtra sesiones válidas en mala red. | dev | `RemoteControl.kt:100,118,122,294` |
| — | **H5/F5 endpoint fail-fast → matizar a "parcial":** la PRIMERA llamada aún bloquea 15s (`TailscaleBridge.kt:159`); el fail-fast de 1s recién opera tras ese primer timeout. | sre | `TailscaleBridge.kt:159` |

### G.3 · Verificados que SOSTIENEN / retirados (3ª pasada)

- **✅ SOSTIENEN (re-verificados por código):** diálogos oscuros legibles (v1.16, contraste muted 5.2:1), ⓘ del diagnóstico (v1.17), fuente persistida (v1.18), OSC52 bloqueo host-iniciado (v1.20 — **native y Sel SÍ copian**, sólo cae el OSC52 host puro), "Copiado" consistente en las 3 superficies, R1 plan-mode dispara con mensaje diferenciado y **no es inyectable** (`tool_name` sólo elige entre 3 strings fijos), FGS aditivo (sin regresión del default best-effort), `forzarReconexion` (sin sonda, sin deadlock, gate 3s), teardown F9 + `quitar_bloque_sentinelas` (no borra a EOF sin `end`), guard de versionCode (armado — con el residual fail-open de arriba), `PendingIntent` del FGS (`FLAG_IMMUTABLE`, `exported=false`, no disparable por otra app), `flag-subidos-context.sh` (sólo lecturas).
- **🗑️ RETIRADOS / CERRADOS:** **A1** (hoja de aprobación spoofeable) → MOOT: opción B REMOVIÓ la hoja (`7d37f91`), §1.5 aplicado; el modo lectura NO es control de seguridad (sólo baja el teclado). **D3** (TAB en nombre de doc) → cerrado (`nombreDeDocInseguro` rechaza `\p{Cntrl}` + parseo desde el final). **D2** (parser de aprobación) → MOOT (opción B). **teardown docker-group** → RESUELTO (detectado + nombrado + remediado, `teardown-host.sh:103-114`). **Umbral OSC52** → MOOT (política A lo reemplazó por bloqueo).
- **Nota app-vs-Claude:** "el plan se ve chico/dominante en el celu" NO es de la app (es el TUI de Claude Code; verificado que no hay config para compactarlo) — la única palanca app-side es el tamaño de fuente (persistido; pendiente A+/A− discoverable). El layout del bloque de plan/pregunta = `/feedback` a Claude Code.

### G.4 · Estado de implementación (plan `sleepy-yawning-snowglobe`, 5 fases, releases separados)

El plan implementa TODO el backlog §G.2 en 5 fases, cada una un release separado (se prueba en el celu
antes de la siguiente). Progreso:

- **Fase 1 · Canal de alerta honesto — app v1.21.0 (vc 31) + plugin 1.8.0 · SHIPPED y VALIDADO on-device (§G.5).**
  - ✅ **Clúster A** cerrado en código: **1a** cursor por `ts` (recupera el hueco de reconexión, sin
    re-emit tras rotación) + siembra para no re-emitir historial; **1b** salud del canal en `Diagnostico`
    (categoría `avisos`: conectado/línea/caído — hace visible el silencio del config-scoping en la app);
    **1c** FGS reconstruye el tail al cambiar de host (adiós al guard `notifs==null` que escuchaba el host
    viejo); **1d** `notify.jsonl` con **mensaje FIJO** por type (el `message` de Claude ya no se propaga —
    cierra el canal push atacante-controlado) + campo `session` (tmux) para identidad; **1e** notif con
    subtexto host·sesión; **1f** debounce de R1 (6 s); **1g** `marvin-doctor` (+`--probe`) que diagnostica
    el config-scoping — probado en vivo: detecta que remotemarvin está sólo en `claude-personal`, no en el
    default. Además: carrera de rotación de `notify.jsonl` cerrada (flock + tmp único).
  - Verificado: compila (JDK17) + unit tests verdes; hooks y `marvin-doctor` probados en vivo (JSON válido,
    payload atacante descartado, debounce activo); **e2e en emulador VERDE** (`AvisosE2ETest`, 2/2 contra el
    fixture con corte real de SSH: la línea escrita con el canal caído se entrega al reconectar, y no re-emite
    lo ya visto). **Falta:** prueba en el celu con el plugin 1.8.0 instalado.
- **Fase 2 · OSC52 handshake — app v1.22.0 (vc 32) · SHIPPED y VALIDADO on-device (§G.5).**
  - ✅ **Clúster E** cerrado: **2a** el bypass del Sel-toggle persistente se reemplazó por un **handshake de
    un solo uso** — un callback mínimo `onSelDragReleased()` (default en `TerminalViewClient`, disparado en el
    `ACTION_UP` del Sel-drag de `TerminalView`) sella un timestamp, y `copiaIniciadaPorVos` acepta el PRIMER
    OSC 52 dentro de 1.5 s y lo **consume**; `isSelectionDragMode()` ya NO se consulta (era el bypass).
    **2b** `ClipboardE2ETest` reescrito (afirma política A: host bloqueado, Sel-toggle-sin-arrastre bloqueado,
    handshake copia) — reemplaza los 2 tests stale que afirmaban el comportamiento pre-política-A. **2c** e2e
    ahora gatea también en **push a main** (el hueco por el que la política A entró sin correr el e2e). **2d**
    toast de bloqueo reformulado (seguridad + cómo copiar con Sel).
  - Verificado: compila (JDK17); **e2e en emulador VERDE** (`ClipboardE2ETest` 3/3 contra el fixture real).
  - **Fix en uso real (v1.24.1):** al copiar con Sel y cambiar de pestaña saltaba el cartel de "copia
    bloqueada" — tmux (`set-clipboard on`) re-emite el MISMO contenido al re-adjuntar/redibujar, y ese
    OSC 52 llegaba fuera de la ventana del handshake. Fix: si el contenido bloqueado es IDÉNTICO al que ya
    está en el portapapeles, es un re-envío inofensivo → se ignora en silencio (sin debilitar seguridad:
    un atacante no gana nada re-escribiendo lo mismo). La copia siempre funcionó; era falsa alarma.
- **Fase 3 · Robustez de la persistencia del Diagnóstico — app v1.23.0 (vc 33) · SHIPPED y VALIDADO on-device (§G.5).**
  - ✅ **Clúster B** cerrado: **3a** el acantilado de 64 KB (`f.writeText("")` borraba TODO) ahora **rota
    conservando la última mitad**; **3b** `persistir` es thread-safe (lock `archivoLock` que serializa el
    read-modify-write — antes varios hilos de red se pisaban, medido 8000→1538); **3c** `VigiaUi` deja de
    escribir su propio `cuelgues-ui.log` (sink ÚNICO = Diagnóstico, que ya persiste los ERROR): se acabó el
    cuelgue duplicado al reabrir (`cargarPersistidos` queda como migración/limpieza del archivo viejo).
  - Verificado: compila (JDK17) + unit tests verdes (2 nuevos: rotación conserva-mitad, persistencia
    concurrente no colapsa). **Falta:** prueba en el celu (ⓘ del Diagnóstico).
- **Fase 4 · DevOps — 4a y 4b SHIPPED (repo público, CI/release en GitHub-hosted).**
  - ✅ **4a** guard de versionCode **fail-closed** (`release.yml`): distingue error HTTP/red (aborta tras 3
    reintentos) del bootstrap genuino (404 sin releases / 200 sin marcador → skip). Lógica verificada local.
  - ✅ **4b** repo PÚBLICO (elimina el PAT del celu). Antes del flip se detectó un riesgo que el
    secret-scan no cubría: `MARVIN_RUNNER=self-hosted` = el runner es la PC del usuario, y con el repo
    público un PR de un fork ejecutaría código arbitrario en su máquina (RCE). Fix (decidido con el
    usuario): **se borró la variable `MARVIN_RUNNER`** → CI/release corren en GitHub-hosted (aislado),
    E2E queda para `make e2e` local. Recién ahí el flip: secret-scan limpio (gitleaks 255 commits + grep),
    `visibility=public`, secret scanning + push protection habilitados, README + `setup-runner.sh` avisan
    NO usar self-hosted en público. **Bonus:** Actions es gratis en repos públicos → se resolvió también
    el problema de facturación que los había empujado al self-hosted. Obtainium ya baja releases sin PAT
    (HTTP 200 anónimo verificado). CI verde en GitHub-hosted.
- **Fase 5 · Menores — app v1.24.0 (vc 34) · parte SHIPPED (segura), parte DIFERIDA (lifecycle).**
  - ✅ Shipped (bajo riesgo, sin tocar ciclo de vida): **5f** dictado — `tabCerrado` ya no limpia
    `transcribiendo` con el worker en vuelo (cerraba la ventana de un 2º worker concurrente; el worker se
    auto-cancela por `sesionEnJuego`); **5g** `exec()` mudo — los fallos de comandos de control ahora se
    **registran en el Diagnóstico** (no cambia tipos ni callers; encaja con la observabilidad de la tanda);
    **5b** target táctil del ⓘ ≥44dp (era ~21dp); **5a** helper inline bajo el toggle "🔔 Avisos en segundo
    plano" (qué hace / por qué prenderlo).
  - ✅ **5c** (v1.25.0): **"Detener" del FGS sticky** — al apretarlo se persiste un flag (`avisos/detenido`,
    con extra `porUsuario` para distinguirlo del stop programático); `MainActivity.onCreate` re-arma en el
    arranque FRESCO (`savedInstanceState==null`) y respeta el flag en la recreación (rotación), así el FGS ya
    no revive con el toggle ON tras un Detener. Compila + unit verdes.
  - ✅ **5e** half-open en primer plano (F-SRE-1, sev 2) — **RESUELTO v1.26.0** (rediseño tras un primer
    intento revertido). El 1er intento usaba `ping()` como sonda → reintrodujo el **deadlock que el propio
    código ya documentaba** en `forzarReconexion` (trilead toma el lock del `Connection`, `close()` no puede
    tomarlo); el e2e colgó 90 min. **Rediseño sin sondear la Connection:** se sellan `ultimoByteMs` (read
    loop) y `ultimoEnvioMs` (`writeToTransport`); cuando —en primer plano y conectado— mandaste input y no
    volvió NADA (ni el eco) por >7s (`sospechaHalfOpen`), se corre un **probe FUERA DE BANDA** (`sondearAlcanzableEn`:
    socket nuevo que lee el banner SSH end-to-end — Tailscale-aware); si el remoto no responde →
    `forzarReconexion()` (cierre directo, sin deadlock). Sin falsos positivos en conexión sana (el eco vuelve
    <1s). Verificado: **8 unit + e2e blackhole real** (iptables DROP self-restoring en el fixture, corrida
    envuelta en `timeout 600`) — 4/4 verde, detecta en foreground y recupera, sin regresión de los 3 tests de
    corte-limpio. Lección guardada: leer las advertencias del código antes de tocar la reconexión.
  - ✅ **5d** cap 6h/reboot del FGS (v1.27.0): es forward-looking y `Service.onTimeout` NO existe en el SDK
    34, así que no hay código para agregar hoy (estamos EXENTOS con targetSdk 34). Se DOCUMENTÓ el requisito
    en `AvisosService` para cuando se suba a targetSdk 35+ (overridear `onTimeout` → parar limpio + avisar; y
    NO auto-arrancar en boot — el modelo es reabrir la app). §1.5: el cap es real pero el código sería dead
    code hoy; la doc es lo correcto.
  - ✅ **5h** modo lectura falso-positivo (v1.27.0): `hayPromptDeDecision` ahora REQUIERE el footer con el
    vocabulario de control de Claude (`to cancel/confirm/amend/explain`) — antes bastaba "≥2 opciones
    numeradas" y un menú numerado CUALQUIERA (gum/fzf/whiptail con `❯ 1. …`) bajaba el teclado de gusto. Las
    3 capturas REALES de Claude siguen disparando (todas tienen el footer); nuevo unit para el menú no-Claude.
    - **Falso-NEGATIVO detectado en uso real (2026-08-24, v1.28.2)**: los selectores de PLAN MODE no traen
      ese footer — la aprobación del plan dice `shift+tab to approve …` / `ctrl+g to edit in VS Code`, y el
      "Ready to submit your answers?" de las preguntas no trae footer — y el teclado no bajaba. Se capturó
      la firma real (tmux + `claude --permission-mode plan`) y se extendió la alternación a
      `approve|edit|submit`; 2 units nuevos con las capturas. El anti-falso-positivo de G-U5 se mantiene
      (sigue exigiendo cursor `❯ n.` + vocabulario `to <verbo>`).

**§G CERRADO** (2026-08-23): todo el backlog de la 3ª pasada implementado, con CI/e2e/unit verde y las
Fases 1-5 validadas on-device (salvo 5c/5e/5d/5h, shipeados tras la validación del stack de avisos).

### G.5 · Validación on-device (2026-08-23)

El usuario probó en el celular (con `claude-personal`, app v1.24.1 + plugin 1.8.0) y **anduvo todo**:
avisos "Claude te espera" (incluido plan-mode/R1 con debounce), toggle "Avisos en segundo plano" + su
helper, ⓘ del Diagnóstico con el estado del canal, dictado (cerrar pestaña mientras dictás), y OSC 52 (Sel
copia, host bloqueado; el fix del re-envío en cambio de pestaña, v1.24.1). Las Fases 1-4 + la parte segura
de la 5 quedan **confirmadas en uso real**. Lo DIFERIDO de la Fase 5 (5c/5e/5d/5h) se shipeó después
(v1.25.0–v1.27.0, ✅ abajo); del 5e falta sólo la comprobación informal en una zona muerta real.

## H · Dictado sin arranque en frío + contrato de host configurado (v1.29.0, 2026-08-24)

Derivado del benchmark de STT on-device (2026-08-24, S23 real por bucle acústico parlante→mic):
GPU caliente 2,9s con calidad muy superior, pero frío 34–75s; y el modo EN VIVO (parciales +
calidad GPU: primer parcial a 3,5s) sólo aparecía del segundo dictado en adelante, porque el
server se despertaba recién DESPUÉS del dictado que lo encontró caído.

- **Despertar al conectar + invariante del mic** (decisión del usuario: "mic habilitado ⇔ motor
  con el modelo cargado"): `RemoteControl.despertarStt()` — un solo exec SSH que arranca el live
  y espera server-side el LISTEN de :6092 (que recién existe con el modelo cargado — verificado:
  uvicorn hace listen después del lifespan) → "vivo"; sin live, prewarm de silencio al batch →
  "batch"; nada → "sin-stt". `DictationController.motor` (PREPARANDO|VIVO|BATCH|SIN_STT) gobierna
  el botón: "Preparando…" deshabilitado hasta el veredicto (estado del mic ahora PERSISTE en
  KeypadView — antes se perdía al recrear la fila con el QWERTY). Disparo en
  `alCambiarEstadoConexion` con throttle de 60s.
- **Keepalive por conexión sostenida** (el usuario rechazó un archivo de toque por feo; se exploró
  y había mejor): `SttPresencia` mantiene UNA conexión TCP ociosa al :6092 vía `PortTunnel`
  mientras la app está al frente. El idle-exit del wrapper ya cuenta ESTABLISHED como uso, y el
  uvicorn instalado no cierra conexiones mudas (su timer de keep-alive se arma recién tras una
  respuesta — verificado en h11_impl). Sin estado en el host: la app muere → la conexión cae →
  a los 10 min se libera la VRAM sola.
- **Host configurado = requisito bloqueante** (decisión del usuario: los casos "sin setup-host" y
  "setup sin uv" no deben poder existir): `setup-host.sh` ahora FALLA sin uv (antes `SALTAR_STT`
  salteaba en silencio → host a medias) y escribe el marker `~/.config/marvin/setup-ok` al final;
  la app lo verifica en cada conexión (`hostConfigurado`, fallback `-x ~/.local/bin/marvin-stt`
  para setups previos al marker; fail-open ante error del exec) y sin él bloquea la sesión con
  mensaje accionable. Sin GPU no hay bloqueo: el batch ya cae solo a cpu/int8.
- Tests: `MotorSttTest` (parser+gate puros), pytest de setup (marker + uv obligatorio),
  `SetupGateE2ETest` (host pelado no abre sesión; el mic arranca deshabilitado y se habilita con
  el motor listo), `despertarStt` e2e contra el fixture ("batch" / "sin-stt"). Fixture con marker.
- **Validado ON-DEVICE (S23, 2026-08-24)**: ciclo completo en frío — la app despertó el server al
  conectar, mic "Preparando…" deshabilitado → habilitado a los ~65s, PRIMER dictado con parciales
  en vivo, keepalive verificado (180s de app al frente con idle=120s de prueba y el server vivo,
  conexión ESTABLISHED visible en `ss`), y decaimiento (app a background → presencia cortada →
  idle-exit a los ~120s liberando la VRAM). Hallazgo de la 1ª pasada corregido antes del ship: la
  ventana de espera del live era 90s y el frío total real es ~105s (uv ~35s + modelo ~65s) — ahora
  180s + fallback a batch al vencerse + 3 reintentos espaciados en la app (antes un timeout dejaba
  el mic muerto hasta la próxima reconexión). El gate de host-configurado pasó por el fallback
  (`marvin-stt` presente) en un host con setup anterior al marker.

## Pendientes vivos (2026-08-24, post-v1.29.0)

1. ~~**F10 · Expiry de Tailscale**~~ → **VALIDADO mid-sesión (2026-08-24)**, 3/3 criterios en vivo
   contra el tailnet real (expire por API; aviso + ERROR en Diagnóstico + re-enrolado QR). Hallazgos
   y método en `docs/validar-expiry-tailscale.md` (nodos con tag NO expiran por default — manual
   corregido; la expulsión de una sesión viva tarda ~18 min; QR de terminal puede no escanear →
   `ts-link-qr.sh --png`). Los dos restos de F10 (**reinicio-tras-vencer** y el re-enrolar de un
   toque) se codearon y VALIDARON en vivo en v1.30.0 → ver §I. Con esto F10 queda COMPLETA.
2. ~~**5e · Half-open en primer plano**~~ → **VALIDADO en el mundo real (2026-08-24)**: blackhole de
   iptables en el host real (`-I INPUT 1`, ojo: con `-A` el tráfico de la app entra por loopback y
   esquiva la regla), tipeo en la conexión muerta → detección a los ~14s → "reconectando…" →
   reenganche de tmux intacto al restaurarse.
3. ~~**5c · "Detener" sticky ante rotación**~~ → **VALIDADO (2026-08-24)** por adb: Detener paró el
   FGS, la rotación NO lo revivió, y el arranque fresco lo rearmó.
4. ~~**A+/A− de fuente discoverable**~~ → **DESCARTADO (2026-08-25, decisión del usuario tras
   cuestionar la premisa)**: la demo ya enseña el pinch y el dolor real ("planes chicos") es del TUI
   de Claude Code. En su lugar se documentó lo que Claude Code YA trae para leer cómodo en 46
   columnas: **Ctrl+G** abre el plan en el editor en el diálogo de aprobación (≥2.1.243) y **Ctrl+O**
   es el transcript mode con búsqueda (≥2.1.142); las sesiones nuevas de la app exportan
   `EDITOR=nano` por default para que Ctrl+G funcione (v1.30.0), y el manual lo explica.
5. ~~**Auditoría de metodología de los agentes-perfil**~~ → **HECHA (2026-08-25)**, hipótesis
   confirmada con causa estructural: el charter §2.E "instalar desde cero" se declaró inejecutable
   en la pasada 1 y desapareció de las tablas de foco; el MOOT de H3 se sobre-extendió; y "está en
   `.env.example`" contó como documentado (DevOps tenía los pasos delante). Corregido: **playbook
   §1.6 "Caminá la superficie, no la leas"** + defs actualizadas (devops ejecuta con HOME-sandbox,
   usuario-final = primer uso del sistema COMPLETO, ux-devex = paridad entre superficies). La
   próxima pasada repone el charter de onboarding. Residual de producto cerrado en v1.30.0: el
   quickstart in-app y la demo ahora mencionan `.env` + las claves del admin de Tailscale.

## J · Revisión de lineamientos (2026-08-27)

Auditoría pedida por el usuario: ¿cumplimos la regla de superficies y la de "nada sólo-local"?
Resultado: 3 deudas de superficies con v1.30.0, cerradas en este commit — el manual no contaba
el flujo de vencido/⟲ ni el fallback `--png`, y las skills no sabían nada del expiry (plugin →
1.9.0 con la ruta "acceso vencido → ts-link-qr.sh --png + ⟲"). El barrido sólo-local dio limpio
(helpers de `~/.local/bin` = `scripts/` del repo; `marvin-share` en el plugin; EDITOR shipeado
en la app) con una excepción que se corrigió: las **defs de los agentes-perfil** vivían sólo en
`~/.claude-personal/agents/` → ahora la fuente de verdad es `docs/agentes/` (ver su README; las
memorias siguen locales a propósito). Demo al día (fila con Enter, dictado, tour de hosts con
.env). Lineamientos vigentes, ninguno vencido ni sobreescrito.

## I · Reinicio-tras-vencer + re-enrolar de un toque (v1.30.0, 2026-08-25)

Cierra los dos restos de F10 (fila 550):

- **Estado sticky en el bridge Go** (`marvints.go`): al fallar `Up` se fotografía el estado del
  backend ANTES del teardown y, si era `NeedsLogin`/expired, `Estado()` lo sigue reportando con el
  nodo apagado (antes: "Detenido" para siempre → la detección no podía disparar al reabrir la app).
  La foto NO se guarda si el fallo fue por red (Starting) — el sticky nunca miente "vencido". Se
  limpia en `Stop()` y al arrancar un `Start` nuevo. Elegido sobre "mantener el nodo vivo en
  NeedsLogin" porque eso cuesta batería (printAuthURLLoop despierta cada 5s indefinidamente).
  + fix defensivo: `Start` idempotente ya no devuelve `nil` a secas (devuelve el `upErr` real).
  Tests Go nuevos (sticky/limpieza/formato) con `upTimeout` acortable para no esperar el minuto.
- **App**: `EnrolarTailscale.kt` (scanner compartido); callback `onAccesoVencido` de la sesión →
  la barra muta a **"⟲ Reescanear QR"** (rojo, lanza el scanner directo; una reconexión OK apaga el
  episodio); `HostsActivity` gana la rama **"acceso vencido — tocá y reescaneá"** en rojo (flag
  cacheado fuera del main thread: `accesoVencido()` es JNI de hasta 5s, sondear en `updateVpnStatus`
  era un ANR en potencia). El banner de la terminal ahora apunta al ⟲.
- **VALIDADO EN VIVO (S23, 2026-08-25)** con dos hallazgos que cambiaron el diseño:
  1. **El arranque-tras-vencer real no muere por timeout**: tsnet intenta re-registrarse con la
     auth key guardada y el control plane la rechaza en segundos (`invalid key: API key … not
     valid`) — y en ese instante el backend todavía no pasó a `NeedsLogin`, así que la foto del
     estado salía vacía. Fix: `fotoDeFallo`/`errorDeAuthRechazada` — si la foto no da vencido pero
     el error de `Up` es un rechazo de credenciales del control ("invalid key"/"key expired"), se
     sintetiza el sticky igual (un rechazo de credenciales significa exactamente "reescaneá el
     QR"; los errores de red NO matchean y no mienten vencido). Con esto la línea roja aparece a
     los **~9 segundos** de abrir la app.
  2. **`POST /device/:id/expire` sobre un nodo con `keyExpiryDisabled` (el default con tags) da
     200 pero es un no-op**: para simular el vencimiento hay que mandar antes
     `POST /device/:id/key {"keyExpiryDisabled": false}` (doc de validación actualizada).
  Flujo completo validado: línea roja en hosts a los 9s → banner en terminal + **⟲ Reescanear
  QR** en la barra → un toque → scanner → QR de `ts-link-qr.sh --png` → nodo nuevo activo con
  conexión directa y el tmux reatachado solo. Cosmético corregido en vivo: el sufijo "· acceso
  vencido" del título quedaba aplastado en 5 renglones por el botón ⟲ — se quitó (título rojo +
  ⟲ + banner ya comunican el estado).
  Bonus observado: si la auth key guardada TODAVÍA es válida al reabrir, tsnet se re-registra
  solo y la app se cura sin intervención (self-heal) — el vencido sólo aparece cuando de verdad
  hace falta reescanear.
