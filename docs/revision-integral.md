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
| `exec()` devolvía `""` ante cualquier fallo, indistinguible de vacío. | ✅ *(el mecanismo entró en P1, pero **sólo lo usaba `renameSession`**: el camino de documentos —el que el hallazgo nombraba— siguió tragándose el error hasta que un E2E lo expuso)* |
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
| **Cero tests, CI y linters** en todo el proyecto. | ✅ 51 tests JVM + 40 pytest + **29 E2E instrumentados** contra fixture desechable + CI bloqueante (hoy sobre runner self-hosted, ver más abajo) + job nocturno de E2E |
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

### P4 — infraestructura del host y documentación *(cerrado)*
| Pendiente | Nota |
|---|---|
| ✅ **`display-entrypoint.sh` sin supervisión** | Si Xvnc o websockify mueren, el container queda vivo como zombi y `restart: unless-stopped` nunca actúa: el visor queda mudo sin que nada avise. **Es funcional, no cosmético.** |
| ✅ **`setup-host.sh`**: no reinicia las units que reescribe, no verifica `uv`, y nunca actualiza un `~/.tmux.conf` preexistente | El síntoma típico es "actualicé y no cambió nada", o un host donde el render-daemon jamás autoarranca. **También funcional.** |
| ✅ **`marvin-stt-live` sin idle-exit** | La app lo arranca igual en modo *ondemand*, así que ~2 GB de VRAM quedan tomados hasta cerrar sesión. |
| ✅ `marvin-stt.py`: el watchdog puede matar una transcripción en curso; fuga de temporales; `ensure_cuda_ld` sin sentinela de re-exec | Robustez del daemon. |
| ✅ S9/S10: hardening de units, `mktemp` en el prewarm, `ts-link-qr` sin imprimir la key ni pasar el secreto por argv | Ya estaban asignados a P4. |
| ✅ Docs (`README`, `android/README`, `DESIGN.md`), `LICENSE` GPL-3.0, `NOTICE.md` y `SECURITY.md` | Los tres describen el gateway con `nsenter` que no existe más; falta el LICENSE pese a vendorizar Termux. |

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
| El APK que valida `make e2e-release` no es byte-idéntico al publicado | Detallado más arriba. Mitigado por `verifyReleaseKeepRules` (cada release y en CI) más el humo manual. |
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

### Próxima iteración: íconos vectoriales propios

Los 8 íconos de la interfaz ya no dependen de la fuente del sistema (`marvin_icons.ttf`, 3 KB,
armada con `scripts/build-icon-font.py`), así que se ven igual en todo teléfono y desaparece
el riesgo de que ⧉ salga como un cuadrito. Lo que queda pendiente para una próxima vuelta es
reemplazarlos por **`VectorDrawable` dibujados con la identidad de Marvin**: control total del
trazo, nítidos a cualquier tamaño y sin depender de ninguna tipografía. Hoy los glifos son de
Noto, o sea correctos y consistentes, pero no propios.

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

Lo que sigue abierto: devolver los **E2E instrumentados a gate de PR** ahora que el runner
tiene KVM, AVD y Docker — que era el motivo original de querer un runner propio.

### Cobertura de tests que falta
Los arreglos de P4 entraron con tests propios: `test/host/` pasó de 16 a 31 (bloques
idempotentes de `setup-host`, escritura de units, señal de actividad del dictado en vivo).
Lo que sigue sin cobertura automática es `display-entrypoint`, que se verificó a mano
matando cada proceso dentro del contenedor. El
fixture ya existe y puede hospedarlos.
