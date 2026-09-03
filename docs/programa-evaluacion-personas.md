# Programa de evaluación por perfiles — RemoteMarvin

RemoteMarvin creció hasta ser una app con muchas superficies (terminal SSH+tmux, Tailscale
embebido, visor noVNC, documentos bidireccionales, dictado por voz, plugin de skills, demo
de primer uso) y hasta ahora la evaluó una sola persona: su autor. Este documento es un
**programa de evaluación por perfiles**: qué profesional exploraría la app, con qué método
formal, y qué tareas concretas correría. El objetivo es descubrir lo que una sola mirada no
ve — y hacerlo de forma que ocho perfiles distintos produzcan hallazgos comparables y
priorizables en un único backlog.

Está escrito para ejecutarse de dos maneras (ver el apéndice de ejecución): como agentes
Claude, uno por perfil, o con personas reales. No hay que correrlo entero: cada charter es
autónomo.

---

## 1. Marco común

Para que ocho perfiles heterogéneos no produzcan ocho formatos de reporte incompatibles,
todos comparten tres cosas.

### 1.1 La unidad de trabajo: el charter (SBTM)

Del *Session-Based Test Management* de Bach. Cada exploración es una **sesión** con:

- **Charter** — misión de 1 a 3 líneas, con la forma *"Explorar &lt;área&gt; con &lt;recurso/técnica&gt;
  para descubrir &lt;tipo de información&gt;"*. Acotado pero no cerrado: deja lugar a la
  exploración.
- **Time-box** — 60 a 90 minutos (90 es el óptimo reportado). Si el área es más grande, se
  parte en varias sesiones.
- **Notas estructuradas** durante la ejecución: qué se probó, qué se encontró, en qué se fue
  el tiempo (diseño/ejecución vs. investigar un bug vs. setup).
- **Debrief PROOF** al cerrar, con quien coordina el programa:
  - **P**ast — ¿qué pasó en la sesión?
  - **R**esults — ¿qué se logró?
  - **O**bstacles — ¿qué estorbó al buen testing?
  - **O**utlook — ¿qué falta hacer?
  - **F**eelings — ¿cómo se siente el explorador respecto de todo esto? (la intuición es
    dato: "esto se siente frágil" merece una sesión de seguimiento).

El charter es el artefacto más reutilizable del programa: la misma plantilla sirve para el
QA que caza bugs, el pentester que sondea componentes con `adb`, el DevOps que sigue el
README en una VM limpia y el UX que hace un walkthrough como una persona. Todos reportan en
el mismo formato.

### 1.2 La escala de severidad unificada

Todo hallazgo —sea de usabilidad, un bug, una vulnerabilidad o un gap operativo— se puntúa
con la **escala de Nielsen 0-4** cruzada con `probabilidad × impacto` del risk-based testing:

| Sev | Nombre | Criterio |
|----|--------|----------|
| 0 | No es problema | Se registra y se descarta. |
| 1 | Cosmético | Arreglar solo si sobra tiempo. |
| 2 | Menor | Prioridad baja. |
| 3 | Mayor | Prioridad alta; importante arreglar. |
| 4 | Catástrofe | Imperativo antes de exponer la app a otros. |

La severidad sale de **frecuencia** (¿cuántos usuarios lo pegan?) × **impacto** (¿fácil o
imposible de superar?) × **persistencia** (¿una vez o molesta siempre?). Cuando hay varios
evaluadores del mismo perfil, puntúan por separado y se promedia.

### 1.3 El cierre

Los hallazgos confirmados entran en la sección **"Lo que queda abierto"** de
`docs/revision-integral.md`, que es el backlog vivo del proyecto — por la regla de
superficies ya vigente (todo cambio se refleja en pendientes, manual, demo y skills). Este
programa alimenta la primera de esas cuatro.

### 1.4 Un punto que tres perfiles miran distinto

Antes de las secciones conviene marcar el hallazgo transversal que ordena el programa. La
**pantalla de aprobación de acciones del agente en el celular** (cuando Claude Code pide
permiso para editar, correr un comando, etc., y el usuario decide desde el teléfono) es el
mismo objeto para tres perfiles:

- **UX** la ve como *visibilidad del estado del sistema* + *prevención de errores*: ¿el
  usuario entiende qué está por aprobar?
- **Seguridad** la ve como **ASI09 — Human-Agent Trust Exploitation** + *autonomía excesiva*:
  ¿se puede engañar al humano para que apruebe algo dañino desde una pantalla chica?
- **Arquitecto de IA** la ve como el eslabón donde el control humano se degrada: pantalla
  chica, aprobación por tap, contexto reducido — justo cuando el agente tiene shell (≡ sudo).

Es el candidato natural a severidad 4 y aparece, con lente propia, en tres de las ocho
secciones. No es redundancia: es triangulación.

### 1.5 Cuestioná la premisa, no sólo el mecanismo

Antes de proponer cómo **endurecer** una capacidad, función o superficie, preguntate si
**debería existir**: ¿alguien la pide o la usa de verdad? ¿la utilidad justifica la superficie de
ataque, la complejidad o el costo? A veces el fix correcto es **eliminarla, bloquearla o no
construirla**, no hacerla "segura". El error a evitar es **optimizar *dentro* de un marco sin
cuestionar el marco** — refinar la seguridad de un mecanismo pasada tras pasada mientras nadie
pregunta si el mecanismo tiene que estar.

**Caso real (OSC 52):** se endureció en TRES pasadas —confirmar arriba de 100 KB → atribuir el
toast ("El host copió…") → mostrar un preview del contenido— sin que ningún perfil preguntara si el
host debía poder **escribir el portapapeles del teléfono**, para empezar. Cuando por fin se
preguntó (lo trajo el usuario, no la evaluación), la respuesta correcta fue **bloquearlo**: utilidad
marginal en esta app + superficie de secuestro de portapapeles (Claude inyectable, contenido no
confiable). Tres pasadas de "hacerlo seguro" cuando la respuesta era "no dejarlo".

Aplica a todos los perfiles, pero **sobre todo a Seguridad y Arquitecto de IA**, que son los que
proponen fixes a mecanismos: cada vez que vayas a decir "endurecé X", chequeá antes si X debería
existir.

### 1.6 Caminá la superficie, no la leas

Evaluar una superficie **leyendo el código o la doc con el sistema ya configurado NO es evaluarla**:
es auditar la implementación, no la experiencia. Para onboarding, setup y cualquier flujo de
"primera vez", el método es **ejecutarlo desde cero** (o lo más cerca posible: HOME-sandbox con
fakes de systemctl/docker — técnica ya probada por DevOps —, contenedor descartable) y anotar cada
punto donde un usuario real se trabaría.

Dos reglas concretas:

1. **Todo paso que mande a una consola web de un tercero** (admin de Tailscale, PAT de GitHub,
   editar una ACL JSON) **es superficie de onboarding de primera clase**: hay que verificar que el
   artefacto que el usuario LEE (manual, guía rápida in-app, demo) lo explique paso a paso.
   **Delegar a `.env.example` (o a cualquier archivo del repo) NO cuenta como documentado** — el
   usuario que está enrolando el celu no está leyendo comentarios de un dotfile.
2. **Paridad entre superficies**: un fix de documentación tiene que aterrizar en TODAS las
   superficies que enseñan lo mismo (manual ↔ guía rápida in-app ↔ demo ↔ README). Un manual
   corregido con una demo vieja es una contradicción que confunde más que el hueco original.

**Caso real (TS_AUTHKEY / OAuth client, 2026-08-24):** en TRES pasadas ningún perfil señaló que el
manual no explicaba que hay que entrar a login.tailscale.com dos veces (generar TS_AUTHKEY y crear
el OAuth client) — el paso a paso vivía sólo en `.env.example`, y DevOps lo tenía delante (su
"camino real" lo lista) pero lo dio por documentado justamente por estar ahí. La causa estructural:
el charter "instalar desde cero" de §2.E se declaró inejecutable en la pasada 1 (no había VM) y
desapareció de las tablas de foco de las pasadas 2 y 3; el MOOT de H3 ("asume entorno dev ya es
correcto") se sobre-extendió de "sabe usar una terminal" a "el setup del host no se evalúa". Lo
trajo el usuario, no la evaluación — el mismo patrón que OSC 52 (§1.5). **Acotación del MOOT de
H3**: un dev sabe correr `docker compose up`; NO adivina en qué submenú del admin de Tailscale se
generan las dos claves. La próxima pasada debe reponer el charter de onboarding-desde-cero para
DevOps y Usuario final, ejecutado con HOME-sandbox o contenedor descartable (compatible con
"revertí todo").

**Método estándar del charter (fijado tras ejecutarlo, 2026-08-27 — 4ª pasada):** el charter
§2.E se corre con **HOME-sandbox + fakes**, sin VM:

- Un `$HOME` descartable en un directorio temporal (`HOME=$(mktemp -d)`), y un directorio de
  **fakes primeros en el PATH** con stubs de `sudo`, `systemctl`, `loginctl`, `docker`,
  `apt-get` (y `uv`/`curl` según el escenario) que registran la invocación y devuelven lo que
  el escenario pida. El repo se COPIA al sandbox antes de correr nada — ojo: `ts-link-qr.sh`
  sourcea el `.env` del repo desde el que corre.
- Con eso los scripts (`setup-host.sh`, `ts-link-qr.sh`, `teardown-host.sh`, `marvin-doctor`)
  se ejecutan DE VERDAD, tal como están escritos, incluidos los caminos de error (sin uv, sin
  jq, credenciales inválidas, distro sin gestor, segunda corrida para idempotencia) — que es
  donde vive lo que la lectura no encuentra (caso real: los errores amigables de ts-link-qr
  eran código muerto por `set -e`, hallado EJECUTANDO, invisible leyendo).
- Queda honestamente afuera (y se declara en el reporte): la consola real de Tailscale, el
  `docker compose up` real, el APK y el escaneo reales. Eso se cubre por cadena documental
  (¿las instrucciones que el usuario LEE alcanzan?) y por las validaciones on-device del
  autor. "No tengo VM" ya no es motivo para declarar el charter inejecutable.

---

## 2. Los perfiles

Cada sección trae **misión**, **método** (el marco formal que usa), **charters** en formato
SBTM, y **qué produce**. Los charters están anclados a features reales de la app —
verificables contra el repo (`RemoteControl.kt`, `HostKeys.kt`, la demo `Tour.kt`,
`~/RemoteMarvinDocs/subidos/`, etc.).

---

### A. Usuario final no técnico

> **⚠️ Re-encuadrado en la segunda pasada (§5):** los usuarios reales de RemoteMarvin son
> **desarrolladores**, así que este persona pasó a ser un **dev que dogfoodea** (ver §5.3).
> Esta sección queda como registro histórico de la primera pasada; su restricción de "no leer
> código" ya no aplica al perfil actual.

**Misión.** Medir la calidad del onboarding **sin el sesgo del autor**: ¿alguien que solo
sigue la demo y el manual llega a usar la app sin ayuda externa?

**Método.** Onboarding puro. Métrica central: **TTFV** (time-to-first-value) — el "evento de
valor" acá es *el primer prompt de Claude respondido desde el teléfono*. Complementos: tasa
de completitud del onboarding, conteo de puntos de abandono. Regla de referencia del campo:
si no hay valor genuino en los primeros ~15 minutos, la retención cae.

**Charters.**

1. *Explorar el primer arranque desde un teléfono virgen, siguiendo SOLO la demo guiada y el
   manual PDF de Documentos, para descubrir hasta dónde se llega sin ayuda externa.* (90 min,
   cronometrar cada hito: instalar → demo → agregar host → enrolar → primera conexión →
   primer prompt respondido). **Éxito**: llega al primer prompt sin buscar ayuda afuera.
   **Fallo**: se traba y tiene que preguntarle a alguien o leer el código.
2. *Explorar la burbuja de bienvenida para descubrir si el usuario entiende QUÉ es la app.*
   (15 min). Tras leer solo la primera burbuja, ¿puede explicar con sus palabras para qué
   sirve? (Verifica el texto que ya se corrigió: "sistematiza la conexión con PCs locales y
   servidores… orientado al desarrollo con Claude Code").
3. *Explorar la puesta en marcha de la PC siguiendo la demo, para descubrir si el paso del
   plugin y del setup del host son ejecutables sin conocimiento previo.* (60 min). Ojo: el
   host requiere una terminal en la PC — ¿la demo asume que el usuario sabe abrir una?
4. *Explorar la recuperación de un error temprano para descubrir si los mensajes guían.*
   (45 min). Provocar los tropiezos típicos: PC apagada, clave no autorizada todavía, QR mal
   escaneado. ¿El mensaje dice qué hacer, o es un callejón?

**Qué produce.** Un TTFV con desglose por hito, la lista ordenada de puntos de abandono, y
para cada uno la severidad. Es el perfil que más rápido detecta deriva entre la demo/manual
y la realidad.

---

### B. UX / DevEx

**Misión.** Evaluar la interfaz sin usuarios reales (revisión experta) y la fricción de uso
diario: aprendibilidad, ergonomía del teclado, copiar/pegar, la demo, los feedback loops.

**Método.** Las **10 heurísticas de Nielsen** como checklist de inspección; **cognitive
walkthrough** (4 preguntas por cada paso: ¿intentará la acción correcta? ¿verá que está
disponible? ¿la asociará con su meta? ¿verá progreso al ejecutarla?); heurísticas móviles
**SMASH**; y un score perceptual con **UMUX-Lite** al cierre (2 ítems: "cumple mis
requisitos" / "es fácil de usar"; convertible a SUS con `SUS = 0.65 × ((I1+I2−2) × (100/12))
+ 22.9`).

**Charters.**

1. *Cognitive walkthrough del primer arranque (enrolar QR → primera conexión SSH → primer
   prompt) para descubrir bloqueos de aprendibilidad.* (90 min). Las 4 preguntas en cada
   paso; un "no" en cualquiera es un hallazgo.
2. *Explorar la ergonomía de la key row (Esc/Ctrl/Alt/Tab/flechas + fila Shift/Sel/Dictar)
   para descubrir fricción de tecleo.* (60 min). La ergonomía del teclado es **la queja #1**
   de todos los clientes SSH móviles del mercado. Verificar: ¿las teclas se aciertan con el
   dedo (target ≥48dp)? ¿la fila tapa el prompt? ¿Shift+Tab funciona? ¿el chevron `›`
   descubre Home/End/PgUp/PgDn? ¿se puede usar `vim`?
3. *Explorar copiar/pegar con "Sel" vs. selección nativa para descubrir si el usuario elige
   bien.* (45 min). Copiar un comando multilínea y pegarlo: con Sel llega entero (líneas
   lógicas), con la nativa llega cortado. ¿La app enseña la diferencia o el usuario la
   descubre a los golpes? (El manual ya lo documenta — ¿alcanza?).
4. *Evaluar la demo de primer uso contra el consenso UX de onboarding para descubrir si
   respeta las buenas prácticas.* (45 min). Checklist: ¿es skippeable? ¿re-lanzable
   (long-press en "_hosts")? ¿hace trabajo real o es un tour de UI? ¿bloquea con modales? El
   consenso es hostil a los tutoriales obligatorios; la de RemoteMarvin bloquea y narra —
   ¿es demasiado?
5. *Explorar los feedback loops del terminal para descubrir latencias que rompen el flow.*
   (60 min). Medir: eco tecleo→pantalla, tiempo prompt→primer byte de Claude, tiempo de
   reconexión tras cortar la red. La dimensión DevEx "feedback loops" aplicada.
6. *Auditoría rápida de accesibilidad para descubrir barreras.* (45 min). Accessibility
   Scanner + navegar cada pantalla con **TalkBack** una vez. Foco en el punto crítico de un
   terminal: ¿TalkBack lee la salida de forma usable o un blob ilegible? Targets ≥48dp;
   contraste del tema (los esquemas ANSI sobre negro casi nunca cumplen 4.5:1). El dictado
   por voz cuenta como feature de accesibilidad — anotarlo a favor.

**Qué produce.** Los hallazgos de heurística con severidad 0-4, el resultado del cognitive
walkthrough (paso × pregunta), un score UMUX-Lite/SUS, y el mini-audit de a11y.

---

### C. Dev (código e integración)

**Misión.** Mirar la app como quien la va a usar para programar de verdad y como quien podría
tocar su código: calidad del canal remoto, del flujo de documentos, y carga cognitiva de la
arquitectura.

**Método.** Code review dirigido + **FedEx tour** (seguir un dato de punta a punta) + la
dimensión DevEx **cognitive load**.

**Charters.**

1. *Explorar la lectura de un diff desde el celular para descubrir si se puede decidir con
   contexto suficiente.* (60 min). Pedirle a Claude un cambio real, y desde el teléfono leer
   el diff y aprobar/rechazar. La pantalla chica es la limitación estructural que reporta
   todo el campo ("you just wish you had a bigger screen"). ¿Alcanza para decidir bien?
2. *FedEx tour: seguir un documento subido desde el teléfono hasta `~/RemoteMarvinDocs/
   subidos/` y de vuelta a la vista, para descubrir cortes en la cadena.* (45 min). Subir una
   imagen y un binario; verificar que llegan intactos (el canal es stdin de un `cat` remoto),
   que Claude los encuentra ("te subí X, mirala"), y que la sección "Subidos desde el celular"
   los muestra.
3. *Revisar el canal remoto (`RemoteControl.kt`) para descubrir debilidades de manejo de
   errores y quoting.* (90 min). Leer `execResult` (¿distingue error de vacío?), el saneo de
   nombres (`nombreInseguro` + `ShellQuote`), el patrón stdin de `uploadDoc`/`transcribe`.
   ¿Hay caminos que se traguen el error? (El proyecto ya tiene historia de esto).
4. *Explorar la carga cognitiva de la arquitectura para descubrir dónde se filtra la
   complejidad.* (60 min). Contar cuántos conceptos hay que sostener a la vez
   (Tailscale + SSH + tmux + Claude Code + skills + noVNC) y, cuando algo falla, cuántas capas
   asoman en el mensaje. El campo reporta esto como el mayor freno de adopción ("a lot of
   moving parts… impossible for anyone but me to understand").

**Qué produce.** Hallazgos de code review con severidad, un mapa del flujo FedEx con sus
puntos de corte, y una estimación cualitativa de carga cognitiva por escenario de fallo.

---

### D. QA exploratorio

**Misión.** Romper la app con uso creativo y adversarial: estados raros, secuencias
inválidas, degradación por tiempo/recursos, interrupciones móviles.

**Método.** SBTM + **tours de Whittaker** (elegidos los de mayor rendimiento para esta app) +
las heurísticas móviles **I SLICED UP FUN** de Kohl (foco en *Interruptions*, *Network*,
*Store*, *Ergonomics*).

**Charters (cada uno es un tour).**

1. *Money tour: recorrer las features que la app anuncia (terminal persistente, dictado,
   documentos bidireccionales, visor noVNC) para descubrir si cumplen lo prometido.* (90 min).
   Es lo que un usuario paga/espera; empezar por acá.
2. *Saboteur tour: minar la app cortando recursos a mitad de un run de Claude Code
   (Tailscale, WiFi, el proceso tmux, la GPU del dictado) para descubrir pérdidas de estado.*
   (90 min). La reconexión/roaming es la 2ª dimensión más discutida del mercado y donde hay
   más desacuerdo real.
3. *Rained-Out tour: cancelar operaciones a mitad de camino (el enrolamiento QR, una subida
   de archivo grande, una sesión que conecta) para descubrir estados inconsistentes.* (60 min).
4. *Couch Potato tour: aceptar todos los defaults sin configurar nada, para descubrir si el
   camino de menor esfuerzo funciona.* (45 min).
5. *Antisocial / Crime-Spree tour: alimentar datos ilegales (nombres de archivo hostiles con
   comillas/rutas, secuencias de acciones en orden incorrecto) para descubrir manejo de
   entradas.* (60 min). Complementa al pentester desde el lado funcional.
6. *Interrupciones móviles (I SLICED UP FUN → Interruptions/Network): llamada entrante,
   rotación de pantalla, notificación, app a background, Doze, transición WiFi↔datos, para
   descubrir qué sobrevive.* (90 min). Batería con sesión activa 1 h; comportamiento tras
   "clear all recent apps" (los OEMs agresivos matan foreground services).

**Qué produce.** Un session sheet por tour con bugs, issues y el debrief PROOF; los bugs
entran al backlog con severidad.

---

### E. DevOps senior (puesta en marcha)

**Misión.** ¿El software es *instalable* sin sorpresas? Ergonomía de setup, idempotencia,
camino de upgrade, desinstalación limpia, y si la documentación describe la realidad.

**Método.** Fase **Analysis** de un PRR (Production Readiness Review de Google SRE) +
**Guidebook tour** (verificar que la doc coincide con el comportamiento) + el checklist de
ergonomía de software self-hosted.

**Charters.**

1. *Guidebook tour: instalar desde cero en una VM/host limpio siguiendo el README y la demo
   AL PIE DE LA LETRA, para descubrir divergencias doc↔realidad.* (90 min). ¿Cuántos comandos
   hasta funcionar? ¿Detecta prerequisitos (tmux, ssh, docker) o falla críptico? **Verifica
   el gap que ya apareció una vez**: ¿la puesta en marcha incluye el paso de instalar el
   plugin de Claude Code (`/plugin marketplace add` + `install`)? Sin él, el host queda
   andando pero Claude no sabe usar la app.
2. *Explorar la idempotencia corriendo `setup-host.sh` dos veces, para descubrir efectos
   acumulativos.* (45 min). ¿Rompe algo? ¿Es declarativo (bloques con sentinelas) o
   acumulativo? (El proyecto ya invirtió en esto — confirmarlo desde afuera).
3. *Explorar el camino de upgrade para descubrir incompatibilidades app↔host.* (60 min).
   Instalar una versión vieja del APK contra un host nuevo y viceversa. ¿Hay versionado?
   ¿Migraciones? ¿Qué se rompe?
4. *Explorar la desinstalación para descubrir basura huérfana.* (45 min). ¿Deja units de
   systemd, claves en `authorized_keys`, un nodo de tailnet enrolado sin dueño? El riesgo de
   "nodo huérfano" es específico del tailnet embebido.
5. *Sostener la respuesta a "¿qué hace esto que un Termius + tmux sobre Tailscale no haga?"
   para descubrir si la diferenciación se sostiene.* (30 min). El escéptico de HN ("what's
   new? he estado haciendo ssh-on-the-phone forever") es el revisor más duro; hay que tener
   respuesta medida.

**Qué produce.** Un reporte estilo PRR: hallazgos por las 6 dimensiones que apliquen, con la
lista de divergencias doc↔realidad como entregable estrella.

---

### F. SRE / operaciones (uso prolongado)

**Misión.** Distinto del DevOps: no el setup sino el **uso sostenido**. Modos de falla,
observabilidad, comportamiento ante cortes, recuperación.

**Método.** Un **ORR** (Operational Readiness Review) por sus 6 dimensiones (arquitectura y
dependencias, instrumentación/monitoreo, respuesta a emergencias, capacidad, gestión de
cambios, performance) + **All-Nighter/Saboteur tours**.

**Charters.**

1. *All-Nighter tour: mantener una sesión tmux viva 24 h+ para descubrir fugas y degradación.*
   (varias sesiones + observación pasiva). ¿Fugas de memoria/conexiones? ¿Consumo de batería?
   ¿El foreground service sobrevive a "clear all" y a los OEMs agresivos (Xiaomi/Samsung)?
   (Termux reporta hasta ~80% de batería sin wakelock — medir el equivalente acá).
2. *Explorar los modos de falla y sus mensajes para descubrir silencios peligrosos.* (90 min).
   Provocar cada uno y leer qué dice la app: PC suspendida, tmux muerto, key de Tailscale
   expirada (el default de 180 días es "potentially catastrophic" si el host solo se alcanza
   por tailnet), sin GPU para whisper, disco lleno. ¿Mensaje accionable o callejón?
3. *Explorar la observabilidad para descubrir qué se puede diagnosticar.* (60 min). ¿Dónde
   están los logs (app y host)? ¿Rotan? ¿Hay health check? ¿Señales para actuar rápido cuando
   algo se cuelga?
4. *Explorar la reconexión y el roaming (WiFi↔datos↔avión) para descubrir qué estado se
   recupera.* (90 min). ¿Vuelve la sesión o solo la conexión? ¿Se recupera el scrollback tras
   reattach? Comparar la *varianza percibida* de latencia contra el argumento pro-mosh (mosh
   baja el promedio pero sube la varianza — SSH+tmux es la elección de RemoteMarvin, hay que
   saber defenderla con datos).

**Qué produce.** Un reporte ORR con el mapa de modos de falla (síntoma → mensaje actual →
mensaje deseado) y las mediciones de recursos en uso prolongado.

---

### G. Seguridad ofensiva (pentester)

**Misión.** Superficie de ataque de la app en el dispositivo y en el canal: custodia de la
clave SSH, verificación de host key, componentes Android, clipboard, path traversal, modelo
de confianza del tailnet.

**Método.** **OWASP MASVS v2.1** (8 categorías) + perfiles **MASTG** (L1/L2/R) + **STRIDE**
sobre un DFD con los trust boundaries de la app + el checklist de pentest de cliente SSH.
*Trust boundaries de RemoteMarvin*: teléfono ↔ tailnet ↔ host; app ↔ otras apps Android
(IPC/clipboard); Claude ↔ contenido no confiable (terminal, docs); usuario ↔ agente autónomo.

**Charters.**

1. *Explorar la custodia de la clave privada (MASVS-STORAGE/CRYPTO) para descubrir exposición.*
   (90 min). ¿Está en el Android Keystore hardware-backed o en plaintext? ¿`allowBackup=false`
   y `dataExtractionRules`? ¿Qué expone un teléfono robado/desbloqueado? Recordar el matiz: el
   Keystore impide **extraer** la clave, no **usarla** — un atacante con el device desbloqueado
   puede firmar aunque no pueda copiarla.
2. *Explorar la verificación de host key (el TOFU de `HostKeys`) para descubrir MITM.* (60 min).
   ¿Se pina en el primer contacto? ¿Advierte al cambiar la clave, o hay algún camino con
   verificador promiscuo? (Es *la* falla clásica de clientes SSH; la app dice tener pinning en
   las 5 rutas — confirmarlo, no creerlo).
3. *Explorar los componentes exportados vía `adb` (MASVS-PLATFORM) para descubrir invocación
   no autenticada.* (60 min). `AndroidManifest` completo: `exported="true"`, intent-filters
   `BROWSABLE`, deep links, services y providers. ¿Se puede disparar una conexión o importar
   credenciales sin confirmación del usuario?
4. *Explorar clipboard, OSC 52, screenshots y logs para descubrir fugas.* (45 min). ¿La app
   copia claves/tokens al portapapeles (legible por otras apps)? ¿El diálogo de consentimiento
   OSC 52 >100 KB cubre el caso? ¿`FLAG_SECURE` en pantallas sensibles? ¿Se loguea la sesión o
   la authkey de Tailscale a logcat?
5. *Explorar la subida de documentos para descubrir path traversal.* (45 min). Nombres con
   `../`, rutas absolutas, bytes raros; ¿se escribe fuera de `~/RemoteMarvinDocs/subidos/`? (La
   app dice sanear con `nombreInseguro` — probar de romperlo).
6. *Modelar el tailnet embebido (STRIDE + MASVS-NETWORK) para descubrir riesgos del plano de
   confianza.* (90 min). ¿Dónde vive la auth key? ¿TTL/preauth? ¿Nodo separado o reusa el
   existente? ¿ACLs y tags? ¿Qué queda al desinstalar (nodo huérfano en el tailnet)? ¿Coexiste
   con la app oficial de Tailscale? ¿Qué declara sobre `VpnService` (tsnet userspace
   probablemente lo evita)?

**Plantilla de hallazgos.** El caso público `pocketshell` (cliente SSH Android con
verificador de host key promiscuo en producción + APK debug-signed con keystore committeada)
es el molde de "cómo se ve un hallazgo crítico acá". Verificar que RemoteMarvin no repite
ninguno: firma de release, keystore fuera del repo, host key pinneada.

**Qué produce.** Un reporte MASVS (control × cumple/no cumple/N-A) + el DFD con STRIDE por
elemento, hallazgos con severidad y CVSS orientativo.

---

### H. Arquitecto de IA

**Misión.** ¿El andamiaje agéntico es *diseñado* o accidental? Con el agravante de que el
agente corre en la máquina personal del usuario con shell real (≡ sudo).

**Método.** **OWASP Top 10 for Agentic Applications** (ASI01-10, foco en ASI09 y en
*Excessive Agency*, que subió al puesto 3 del Top 10 LLM 2026) + evaluación de skills
(progressive disclosure, calidad de triggers) + análisis de la superficie de prompt
injection. Referencia contra la cual comparar: el modelo de seguridad oficial de Claude Code
(permisos read-only por defecto, sandbox del bash tool, working-directory boundary, contexto
aislado para web fetch, trust verification).

**Charters.**

1. *Explorar la pantalla de aprobación en el celular (ASI09 + autonomía excesiva) para
   descubrir degradación del control humano.* (90 min). ¿Se lee el diff completo antes de
   aprobar, o solo un resumen? ¿El "auto mode" está por defecto? ¿Hay kill switch accesible?
   ¿La app respeta el permission-mode local o duplica decisiones ya tomadas en la PC? (Bug
   documentado en el propio Claude Code remoto — verificar que RemoteMarvin no lo herede). Es
   **el charter de severidad potencialmente 4** del programa.
2. *Explorar la superficie de prompt injection para descubrir canales no confiables tratados
   como instrucciones.* (90 min). Los dos vectores propios de la app: (a) los documentos que
   el usuario sube desde el teléfono a `subidos/` (imágenes con texto, PDFs, CSVs) que Claude
   después lee; (b) la salida de terminal (`cat`, `curl`, logs, mensajes de commit). ¿Se
   tratan como *datos* o pueden inyectar instrucciones al agente? Un `curl` malicioso cuyo
   output Claude procese es RCE con privilegios de confianza.
3. *Evaluar la calidad de las skills del plugin para descubrir si aportan o son ruido.*
   (60 min). ¿Cada skill *cambia el comportamiento por defecto* del agente, o es solo un
   comando que hay que acordarse de invocar? (Las buenas desplazan lo que el agente produce
   sin prompting constante). Dato de referencia: context files escritos por devs mejoran el
   desempeño ~+4%, los autogenerados que duplican el README lo empeoran ~−3% (ETH Zürich) —
   ¿las skills de RemoteMarvin aportan señal?
4. *Evaluar los triggers de las skills para descubrir falsos positivos y coste de contexto.*
   (45 min). ¿`share-doc` y el router disparan cuando deben? ¿Disparan cuando NO deben? ¿El
   `description` front-loadea el trigger? ¿`/doctor` muestra desborde de presupuesto de
   contexto? (Instalar demasiadas skills es "el error más común" por bloat).
5. *Contrastar el dictado local contra el `/voice` oficial para descubrir el valor real.*
   (30 min). El `/voice` de Claude Code **es cloud y no funciona por SSH** — justo el
   escenario de RemoteMarvin. El whisper local en la GPU del host no es una alternativa peor:
   es la única opción en ese contexto, y resuelve la privacidad. Verificar latencia (Whisper
   es batch: no emite hasta que soltás), precisión en vocabulario técnico (rutas, `snake_case`,
   comandos git) y que el audio no salga de la máquina.

**Qué produce.** Un scorecard ASI01-10 (riesgo × mitigación presente/ausente), la evaluación
de skills (aporta/ruido + triggers), y el mapa de superficie de inyección con las defensas
existentes.

---

### I. Diseñador/a gráfico/a (opcional; sumado el 2026-09-03)

> Perfil **à la carte**: se invoca cuando la pasada toca piezas visuales (app, manual PDF,
> README/release, QR). Def en `docs/agentes/disenador-grafico.md`; memoria con el contexto de
> marca verificado en `~/.claude-personal/agents/memoria/disenador-grafico.md`.

**Misión.** Auditar la coherencia de RemoteMarvin y de todas sus superficies con el **Manual de
identidad visual de Marvin** (isologotipo, color, tipografía, iconografía) y la **paridad de
marca entre superficies**. No evalúa gusto: evalúa apartamientos del manual y contradicciones
entre piezas.

**Método.** Inspección de **piezas renderizadas** (capturas reales del emulador, páginas del
manual PDF generado, PNG de los SVG oficiales) más **medición**: hex exacto contra `res/values`
y contra el píxel; contraste **WCAG AA**; métricas tipográficas con `fontTools` (x-height/cap,
anchos) y cobertura de glifos (`cmap`) de la fuente que de verdad dibuja cada carácter; medidas
de resguardo y tamaño mínimo. Anclaje obligatorio de cada hallazgo a una **página del manual** o a
un **chequeo objetivo**; lo demás se rotula "opinión" y va aparte. Restricciones de licencia como
premisa: el repo es GPL-3.0, las sustituciones tipográficas (osifont por ISOCPEUR, Jost por
Brandon Grotesque) son decisiones de licencia y **no se revierten**; las fuentes de referencia en
`fonts/` sirven sólo para medir; nada pirata; toda fuente nueva entra en `NOTICE.md`. §1.5
aplica: antes de pulir una pieza, preguntar si según el manual debería existir o verse así.

**Charters.**

1. *Isologotipo en la app contra las páginas 01-04 del manual* (ícono adaptativo, splash,
   isotipo del header, `marvin_isologo_bar.png`): variante correcta para cada fondo, área de
   resguardo, tamaño mínimo, sin deformación ni recoloreo. (45 min)
2. *Color contra la página 05*: paleta CRT vs `colors.xml`/`Paleta.kt` vs píxel renderizado;
   usos semánticos (verde ok / ámbar atención / rojo alarma) consistentes entre hosts y terminal;
   contraste AA de cada texto sobre petróleo. (45 min)
3. *Tipografía contra la página 06*: los cuatro roles → dónde se aplican de verdad en la app y en
   el manual PDF; fidelidad medida de osifont y Jost; jerarquía; y **quién dibuja cada glifo
   especial** (↺ ⟳ ✓ ⓘ 🔒 ⧉ ⇧) — un carácter en fallback del sistema es hallazgo. (60 min)
4. *Iconografía contra la gramática del manual*: los 8 VectorDrawable (grilla 24, trazo 2,
   rectas y diagonales, esquinas cortadas, firmas `[▲\\▼]`) y todo lo que hace de ícono sin ser
   de la familia (glifos de texto, emojis de sistema, íconos de AlertDialog/Toast). (45 min)
5. *Superficies fuera de la app*: manual PDF generado, README y página del release, salida del
   QR en terminal, DocViewer, notificaciones. (45 min)
6. *Estados y feedback*: vencido/alarma/conectada/transitorios; toasts y diálogos ¿de marca o de
   sistema? (premisa antes que pulido). (30 min)

**Qué produce.** Tabla de hallazgos (título · anclaje manual/chequeo · severidad · evidencia:
captura o medida · propuesta concreta), sección aparte de opiniones, y la memoria apendada. Es el
perfil que detecta deriva de marca entre la app y sus piezas satélite, y quien vigila que un fix
funcional (un toast, un diálogo, un glifo nuevo) no salga fuera del sistema visual.

---

## 3. Apéndices

### 3.1 Tabla maestra — dimensiones × perfil que las sondea

Índice de cobertura: las ~30 dimensiones que emergen del campo real, con el perfil que las
sondea primero. Sirve para ver que ninguna quede sin dueño y que ningún perfil quede vacío.

| # | Dimensión | Perfil primario |
|---|-----------|-----------------|
| 1 | Ergonomía de teclado / key row | UX · Dev · QA |
| 2 | Fidelidad de estado agente↔teléfono | Arquitecto IA · QA · Dev |
| 3 | Aprobaciones / permisos remotos | Arquitecto IA · UX · Seguridad |
| 4 | Persistencia y reconexión | SRE · QA · DevOps |
| 5 | Batería y foreground service | SRE · QA |
| 6 | Scrollback y copy/paste | UX · SRE · QA |
| 7 | Modelo de confianza del tailnet | Seguridad · DevOps |
| 8 | Custodia de claves en el teléfono | Seguridad |
| 9 | Superficie de riesgo del agente | Seguridad · Arquitecto IA |
| 10 | Latencia percibida del terminal | SRE · Dev |
| 11 | noVNC: control de puntero | UX · QA |
| 12 | noVNC: zoom y viewport | UX · QA |
| 13 | noVNC: teclado y caracteres | QA · UX |
| 14 | noVNC: rendimiento/codec | SRE · Dev |
| 15 | Voz: latencia end-to-end | Arquitecto IA · UX |
| 16 | Voz: precisión técnica | Arquitecto IA · Dev |
| 17 | Voz: mecánica push-to-talk | UX · QA |
| 18 | Voz: privacidad y offline | Seguridad · Arquitecto IA |
| 19 | Notificaciones: señal vs ruido | UX · Dev |
| 20 | Compartir documentos bidireccional | Dev · UX · QA |
| 21 | Time-to-first-value del onboarding | Usuario final · DevOps · UX |
| 22 | Calidad de mensajes de error | SRE · DevOps · Usuario final |
| 23 | Demo guiada: skippeable y re-lanzable | UX · Usuario final |
| 24 | Exactitud de la documentación | DevOps · Usuario final |
| 25 | Plugin/skills: coste de contexto | Arquitecto IA · Dev |
| 26 | Precio, cuenta y telemetría | Seguridad · DevOps |
| 27 | Riesgo de abandono / bus factor | DevOps · Arquitecto IA |
| 28 | Distribución Android / políticas | Seguridad · Dev |
| 29 | Diferenciación vs "es solo ssh+tmux" | DevOps · Arquitecto IA |
| 30 | Pantalla chica: revisar y decidir | UX · Dev · QA |

### 3.2 Tres tensiones con conflicto (charters transversales de discusión)

No son bug-hunting: son debates que el mercado no resolvió y donde RemoteMarvin tiene que
tener una postura defendible. Sirven como charters de discusión, no de exploración.

1. **Notificaciones — señal vs ruido.** La gente las pide y después las apaga por ruidosas.
   Nadie resolvió la granularidad ideal: "avisame solo cuando el agente esté bloqueado *y* yo
   no esté frente a la PC". ¿Qué hace RemoteMarvin hoy y qué debería hacer?
2. **mosh vs ssh+tmux.** Mosh gana en roaming y echo local; pierde en varianza de latencia
   percibida, puertos UDP bloqueados y falta de scrollback. RemoteMarvin eligió SSH+tmux sobre
   Tailscale — un DevOps senior va a preguntar por qué, y la respuesta tiene que ser medida,
   no dogmática.
3. **Granularidad de aprobaciones.** Entre `--dangerously-skip-permissions` (inseguro desde
   el teléfono, donde no ves bien qué aprobás) y aprobar todo a mano (inviable en móvil) no
   hay punto medio bien resuelto. Anthropic lanzó "auto mode" con clasificador interno, pero
   no se puede personalizar qué considera seguro. ¿Dónde se para RemoteMarvin?

### 3.3 Cómo ejecutar el programa

**Como agentes Claude.** Un subagente por perfil. Pero no es "correrlos todos en paralelo":
tienen un orden que importa y una mecánica de traspaso que los convierte en un relevo en vez
de ocho revisiones aisladas. Todo eso está en la **sección 4 (Protocolo de ejecución con
agentes)**.

**Con personas reales.** Cada perfil recibe su sección como guía, un formulario de debrief
**PROOF**, y —si es UX o Usuario final— el cuestionario **UMUX-Lite** (2 ítems) al cierre.
El coordinador junta los debriefs y puntúa con la escala unificada. Con 2-5 evaluadores por
perfil se detecta el grueso de los problemas de cada dimensión.

**El cierre, en ambos casos.** Los hallazgos confirmados entran en "Lo que queda abierto" de
`docs/revision-integral.md`, priorizados por severidad. Si un hallazgo toca una feature
visible, arrastra la regla de superficies (pendientes + manual + demo + skills).

### 3.4 Fuentes de las metodologías

- **UX**: Nielsen — 10 heurísticas, severidad, cognitive walkthrough, onboarding
  (nngroup.com); heurísticas móviles SMASH (Inostroza et al.); SUS/UMUX-Lite (measuringu.com).
- **QA**: SBTM (satisfice.com, Bach); tours de Whittaker (*Exploratory Software Testing*, 2009);
  I SLICED UP FUN (kohl.ca).
- **Seguridad**: OWASP MASVS/MASTG y MAS Checklist (mas.owasp.org); STRIDE
  (owasp.org/www-community/Threat_Modeling_Process).
- **DevOps/SRE**: PRR/ORR (sre.google/sre-book, sre.google/workbook).
- **DevEx**: DevEx paper (queue.acm.org/detail.cfm?id=3595878); SPACE
  (queue.acm.org/detail.cfm?id=3454124); DX Core 4 (getdx.com).
- **Agentes**: OWASP Top 10 Agentic ASI01-10 (genai.owasp.org); modelo de seguridad y skills
  de Claude Code (code.claude.com/docs/en/security, /skills, /plugins).
- **A11y**: developer.android.com/guide/topics/ui/accessibility; WCAG 2.2 (w3.org/WAI).

---

## 4. Protocolo de ejecución con agentes

Esta sección es para cuando el programa se corre con agentes Claude (un subagente por perfil).
Responde cuatro preguntas: en qué **orden**, con qué **contexto**, con qué **instrucción**, y
cómo cada agente **construye sobre lo que encontraron los anteriores** sin dejar de tener
libertad para investigar por su cuenta.

### 4.1 El principio de orden: la frescura es un recurso que se agota

La observación que ordena todo: **algunos métodos exigen ojos frescos, y los ojos frescos no
se recuperan.** Un agente que ya leyó el código, el backlog y el informe de seguridad no
puede después *experimentar auténticamente* "no entiendo qué hace este botón". La ingenuidad
del primer uso es un one-shot: se gasta una sola vez.

De ahí la regla: **la ingenuidad decrece y el contexto crece, monótonamente.** Se ordenan los
perfiles del que menos contexto necesita al que más, de modo que cada uno corra con exactamente
la frescura que su método requiere. Esto parte el programa en dos tiers.

**Tier ojos frescos** (contexto mínimo, NO leen el código fuente ni el backlog):
1. **Usuario final** — cero contexto previo. Solo la app publicada, la demo y el manual. Es el
   recurso más perecedero: se gasta primero, y en su forma más pura.
2. **UX / DevEx** — experto en heurísticas, pero el cognitive walkthrough del primer arranque
   necesita frescura. Conoce Nielsen; no debe conocer el código.
3. **DevOps senior** — su primer charter (Guidebook tour) es seguir el README *como está
   escrito*: haberse leído el código antes contaminaría el "¿la doc dice la verdad?". Es la
   bisagra entre los dos tiers.

**Tier informado** (contexto acumulado, cadena de dependencia real):
4. **Dev** — necesita el repo a fondo (code review). Produce el *mapa del código* que reusan
   los tres que siguen.
5. **QA** — ancla bugs al código (usa el mapa de Dev) y produce el *catálogo de lo que se
   rompe*.
6. **SRE** — extiende el catálogo de QA a uso prolongado + observabilidad.
7. **Seguridad** — usa el mapa de Dev (dónde vive el saneo, el quoting, el host-key) y los
   crashes de QA (crash → info disclosure). Produce las *fronteras de confianza*.
8. **Arquitecto de IA** — el capstone: usa las fronteras de Seguridad, el canal de Dev y el
   encuadre de la pantalla de aprobación de UX.

**Aristas de dependencia** (quién alimenta a quién; las que son secuenciales de verdad):

```
Usuario final ─┬─► UX ──────────────► Arq. IA
               └─► DevOps ──► (mapa operativo a todos)
Dev ──┬─► QA ──┬─► SRE
      │        └─► Seguridad ─► Arq. IA
      └─► Seguridad
UX ───────────────► Seguridad, Arq. IA   (encuadre de la pantalla de aprobación)
```

**Paralelismo permitido:** dentro del tier fresco, Usuario final va estrictamente primero;
UX y DevOps pueden solaparse. En el tier informado, Dev abre y después QA/SRE pueden correr
en paralelo; Seguridad espera a Dev+QA; IA cierra. No hay que serializar todo — solo respetar
las aristas.

### 4.2 Qué contexto recibe cada agente

El contexto NO es "todo para todos": darle el backlog al Usuario final le arruina la
ingenuidad; no darle el repo a Seguridad lo deja ciego. La matriz:

| Perfil | App | Repo código | Host oráculo | APK/adb | `revision-integral.md` | Manual PDF | Briefing de olas previas |
|--------|-----|-------------|--------------|---------|------------------------|-----------|--------------------------|
| Usuario final | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ninguno |
| UX / DevEx | ✅ | ❌ | ❌ | ❌ | solo tras su walkthrough | ✅ | Usuario final |
| DevOps | ✅ | ✅ (docs primero) | ✅ | ❌ | tras el Guidebook tour | ✅ | Usuario final, UX |
| Dev | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | DevOps |
| QA | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Dev, DevOps |
| SRE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | QA, DevOps |
| Seguridad | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Dev, QA |
| Arq. IA | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Seguridad, Dev, UX |

Dos aclaraciones que hacen la diferencia:

- **El "briefing de olas previas" es un digest, no los informes completos.** Volcarle a un
  agente los reportes enteros de los anteriores le infla el contexto y lo *sesga* (empieza a
  buscar lo que ya se encontró en vez de lo suyo). El coordinador destila de cada informe solo
  (a) los hallazgos de severidad ≥ 3 y (b) las *handoff notes* (ver §4.4) relevantes a ese rol.
  Media carilla, no diez.
- **La restricción de frescura es una instrucción explícita, no un olvido.** Al Usuario final
  se le *prohíbe* leer el código; a UX se le dice "hacé tu walkthrough primero, después podés
  mirar el backlog". Sin esa prohibición, un agente curioso lee todo y pierde el valor de su
  rol.

### 4.3 La plantilla de instrucción

Cada prompt de agente se arma con siete bloques. Los invariantes están en todos; las partes
`<variables>` salen de la sección del perfil (§2) y de la matriz (§4.2).

1. **Identidad y mentalidad.** *"Sos un/a `<rol senior>` haciendo una revisión alineada con
   `<método: MASVS / Nielsen / ORR / OWASP ASI…>`. Tu trabajo no es elogiar la app: es
   encontrar lo que su autor no ve."* El encuadre importa — un pentester al que le pedís
   "revisá la seguridad" es más blando que uno al que le decís "asumí que hay una falla y
   encontrala".
2. **Restricción de frescura.** Qué leer y qué NO, y cuándo (de la matriz). Explícito.
3. **Tus charters.** La lista de la sección del perfil, en formato SBTM (misión + time-box +
   qué descubrir). Con permiso de agregar charters propios si el método lo pide.
4. **Briefing de olas previas.** El digest (§4.4). Con la aclaración: *"esto es contexto, no
   una lista de lo que tenés que confirmar; tu trabajo es lo tuyo."*
5. **Mandato de investigación** (§4.5). Libertad explícita para buscar en web/papers/docs.
6. **Contrato de salida.** Hallazgos con **severidad 0-4** (§1.2) + justificación; el debrief
   **PROOF** (§1.1); y la **handoff note** (§4.4). Además: distinguir siempre *"lo confirmé
   contra la app/host real"* de *"lo sospecho / no pude verificar"* — un hallazgo sin verificar
   se marca como tal, no se infla.
7. **Reglas anti-alucinación.** Verificar contra la app y el host reales, no contra la
   suposición. No inventar features: si algo no existe, es un hallazgo ("esperaba X, no está"),
   no una alucinación. Anclar cada hallazgo a un archivo/pantalla/comando concreto.

### 4.4 La mecánica de traspaso: la handoff note

Lo que convierte ocho revisiones aisladas en un relevo. Además de sus hallazgos, cada agente
produce una **handoff note**: mirando hacia adelante, *"cosas que vi de reojo pero que no me
correspondían, y que el próximo rol debería mirar"*. Ejemplos del tipo de traspaso que emerge:

- Usuario final → *"me trabé enrolando el QR, no entendí de dónde sacar el código; que UX mire
  ese paso con lupa."*
- Dev → Seguridad: *"el saneo de nombres vive en `RemoteControl.nombreInseguro`; no lo audité
  a fondo, es tu terreno."*
- QA → SRE: *"maté el proceso tmux a mitad de un run y la app quedó mostrando 'conectado'; no
  esperé a ver si se recupera sola — probalo en tu ventana de uso prolongado."*
- UX → Arq. IA: *"la pantalla de aprobación muestra el comando pero no el diff completo; para
  vos eso es ASI09, para mí era visibilidad del estado."*

El coordinador destila las handoff notes en el briefing del agente siguiente (§4.2). Es el
combustible del relevo: cada ola arranca sabiendo dónde apuntar sin que se le dicte qué
encontrar.

### 4.5 Mandato de investigación

Cada agente tiene **libertad explícita para buscar en la web, papers y documentación** y
afilar tanto su método como sus hallazgos — no solo aplicar lo que ya está en el playbook.
Los puntos de partida por rol están en §3.4, pero el mandato es más amplio:

- Buscar el **estado del arte** de su método (¿salió una versión nueva de MASVS? ¿un checklist
  de a11y móvil más completo? ¿un tour de Whittaker que encaje mejor con esta app?).
- Buscar **incidentes comparables** (el caso `pocketshell` para Seguridad, los bugs del Claude
  Code remoto para IA/QA) como plantilla de "cómo se ve un hallazgo real acá".
- Buscar **contra-argumentos** a las decisiones de diseño de la app (el debate mosh vs ssh+tmux,
  la crítica de context-files autogenerados) para no validar por default.

Y una regla de retorno: **si un agente encuentra un artefacto mejor que el que trae el playbook**
(un método más fino, un checklist más completo), lo trae de vuelta y el playbook se actualiza.
Por la regla de superficies, el propio documento es una superficie viva.

### 4.6 Orquestación concreta

- **Coordinador** (el loop principal, o quien corra el programa): lanza cada ola, recibe los
  informes, destila las handoff notes en el briefing de la ola siguiente, y mantiene el backlog
  único ordenado por severidad.
- **Olas.** Ola 1: Usuario final (solo). Ola 2: UX + DevOps (se solapan). Ola 3: Dev (abre),
  luego QA + SRE. Ola 4: Seguridad, luego Arq. IA. No se serializa lo que es independiente,
  pero se respetan las aristas de §4.1.
- **Arranque de máximo ROI**, si no se corre todo: **Usuario final** (barato, revienta el
  onboarding), **Seguridad** (una app de acceso remoto lo amerita), **Arquitecto de IA** (la
  pantalla de aprobación, el hallazgo transversal) y **QA** (Saboteur + interrupciones). Cuatro
  agentes cubren las dimensiones de más peso; el resto se agrega según lo que aparezca.
- **Cierre.** Los hallazgos confirmados entran en "Lo que queda abierto" de
  `docs/revision-integral.md`, priorizados por severidad; los que tocan features visibles
  arrastran la regla de superficies (pendientes + manual + demo + skills).

---

## 5. Segunda pasada — verificación de fixes + superficies nuevas (post F6–F10 / v1.13.0)

La primera pasada (§1–§4) **descubrió** el backlog. Entre medio se corrió el Roadmap 2, que cerró
casi todo. Esta sección gobierna la **segunda pasada**: cada perfil ya no arranca de cero — tiene
**memoria** de sus hallazgos y un backlog mayormente resuelto. Su trabajo ahora es doble:
**re-verificar que sus propios fixes aterrizaron de verdad** y **atacar las superficies nuevas**.

### 5.1 Qué cambió desde la primera pasada (digest)

Todo pusheado, CI verde, un commit por fase. Detalle vivo (con las filas ✔ RESUELTO) en
`docs/revision-integral.md`.

- **F1 — Correctness de Documentos:** `listDocs` NUL-delimited (`\n` en el nombre ya no parte el
  listado), `Doc.soportado` (nombres inseguros mostrados deshabilitados), error honesto local.
- **F2 — Onboarding/skills/scripts:** README `/plugin`, evals de skills, hook PostToolUse de
  contenido no confiable (`flag-subidos-context.sh`), `.env.example`, `setup-host.sh` multi-distro.
- **F3 — Seguridad/a11y:** `FLAG_SECURE` (sólo el QR, por decisión del usuario), OSC52 con aviso
  atribuido al host + umbral 20KB, TalkBack en la terminal.
- **F4 — Cosmética/tema oscuro:** AlertDialogs oscuros, contraste, chevron ≥48dp, texto del QR.
- **F5 — Red:** "[reconectando…]" una vez por episodio; `endpoint()` fail-fast (no congela 15s).
- **F6 — Dictado con preview:** al soltar el mic, burbuja no-modal con **Descartar/Insertar**
  (insertar-para-editar), texto saneado (`\n`/control chars → espacio), nunca auto-Enter.
- **F7 — Notif de aprobación en background:** con la app atrás, un prompt de Claude postea una
  notificación (canal HIGH, `POST_NOTIFICATIONS`); tocarla trae la app y aparece la hoja.
- **F8 — Observabilidad:** ring buffer `Diagnostico` + `DiagnosticoActivity` (long-press en la barra
  de host), con Compartir/Limpiar; sin secretos ni contenido de terminal.
- **F9 — `teardown-host.sh`:** deshace el setup (units, helpers, bloques, sshd.d, linger, docker);
  NO toca `authorized_keys` ni borra nodos del tailnet (pasos guiados).
- **F10 — Detección de expiry de Tailscale (PARCIAL):** `marvints.Estado()` + `accesoVencido()`;
  si el nodo vivo reporta NeedsLogin/expired se avisa "reescaneá el QR". Falta validación vs tailnet
  real + el caso reinicio-tras-vencer (ver `docs/validar-expiry-tailscale.md`).
- **Release:** APK firmado **v1.13.0** publicado por `release.yml` (GitHub Releases + Obtainium).

### 5.2 Cómo cambia la lógica

- **La frescura ahora es distinta.** Tu memoria es una *foto vieja* (tus hallazgos previos). Lo
  fresco son las **superficies nuevas** (preview, notificación, diagnóstico, teardown, expiry): esas
  sí merecen ojos sin sesgo. Para el resto, sos un perfil *informado*.
- **Re-verificá, no confíes en el ✔.** Por cada hallazgo tuyo de sev≥3, chequeá contra el
  **código/comportamiento real** si el fix aterrizó y **se sostiene**. Un ✔ que no se sostiene se
  **reabre** en `revision-integral.md` con evidencia.
- **Atacá lo nuevo con tu lente** (§5.3) y **re-scoreá** si tu método produce un score (UX→SUS,
  Arq-IA→ASI01-10).
- **Handoff notes siguen** (§4.4). **Consolidación**: NO edites `revision-integral.md` vos mismo
  (regla throwaway de §5.4 + varios agentes en paralelo = carrera de escrituras; el backlog único lo
  mantiene el coordinador, §4.6). En tu reporte **proponé las filas exactas** a confirmar/ajustar/
  reabrir, con evidencia; el coordinador las aplica. Tu **memoria** sí es tuya: apendá el apéndice
  fechado (vive fuera del repo).

### 5.3 Foco por perfil (qué re-verificar + qué superficie nueva atacar)

| Perfil | Re-verificar (hallazgos propios) | Atacar (superficies nuevas) |
|--------|----------------------------------|------------------------------|
| **usuario-final (dev dogfooder)** | H1 dependencia circular del manual; H6 guía con la PC apagada (cambió con F5/F8); H8 auth fallida igual entra a la terminal+demo. **H3 queda MOOT** (asumir entorno dev ya es correcto) | UX vivida del **preview** (Descartar/Insertar), la **notificación** cuando Claude espera, la **discoverability del diagnóstico** (long-press), y "¿lo adoptaría un dev?" |
| **ux-devex** | Los 2 sev-4 (falso "Host conectado"+tour → WS-A; ceguera TalkBack → F3) y **re-scorear SUS** | Affordances del **preview** (¿Descartar tan fácil como Insertar?), **tema oscuro** de diálogos (F4), **chevron 48dp** (F4), UX del **diagnóstico** (F8), la **notificación** |
| **devops** | Su hallazgo estrella: el **teardown** (F9) — ¿deshace todo, idempotente, NO toca `authorized_keys`?; multi-distro (F2); README `/plugin` (F2); **distribución del APK** (release.yml/Obtainium) | Casos borde del `teardown-host.sh` y del release workflow |
| **dev** | `listDocs` NUL (F1); `execResult` (F1) | Correctitud/arquitectura/deuda del **código nuevo**: `Diagnostico`/`DiagnosticoActivity` (F8), preview en `DictationController` (F6), notif en `Aprobacion` (F7), `marvints.Estado`/`TailscaleBridge` (F10), lib de teardown (F9), `MarvinTestRunner` |
| **qa** | `raro'nombre` (F1); diálogo que rota (WS-D); sustitución de sesión | Edge cases del **preview** (re-dictar rápido, vacío, control chars = el saneo); **carreras de background/notificación** (F7); **diagnóstico** (overflow del buffer, limpiar durante eventos); **teardown** (estado parcial); **detección de expiry** |
| **sre** | Botín grande: H1 pérdida de sesión (hoy se anuncia — verificá el aviso y el Toast); H2 notif en background (F7); H3 expiry (F10); H5 endpoint fail-fast (F5); H6 observabilidad (F8); H7 chrome desacoplado (WS-A) | El **ring buffer** como herramienta SRE; la **completitud de la detección de expiry** (mid-sesión vs hueco reinicio-tras-vencer); confiabilidad de la notif |
| **seguridad-ofensiva** | H3 FLAG_SECURE (F3, sólo el QR); H5 umbral OSC52 (F3); H6 parser `listDocs` (F1); H2 default de usuario ya no root (WS-D) | El **PendingIntent** de la notif (F7); `POST_NOTIFICATIONS`; `teardown-host.sh` (¿de verdad NO toca `authorized_keys`?); exposición de info de `Estado()` (F10); ¿el **diagnóstico** filtra secretos? |
| **arquitecto-ia** | ASI09 (hoja de aprobación WS-F + notif F7); H4 preview (F6); H5 OSC52 (F3); H3 hook de contenido no confiable (F2); H6/H7 description/evals de skills (F2) | Cómo la **notificación** y el **preview** (insertar-para-editar) mueven la superficie de confianza agéntica. **Re-scorear ASI01–10** |

### 5.4 Libertad de método (y sus límites)

Cada agente puede **escribir y correr los tests que necesite** (`make unit`/`make host`/`make e2e`,
o pruebas descartables), **levantar el fixture**, **manejar la app en el emulador** y **experimentar**
con hipótesis. Reglas: **todo throwaway — al terminar REVERTÍ TODO** (nada nuevo ni tocado en el
repo; `revision-integral.md` incluido — las filas se proponen en el reporte, §5.2); **nunca
commit/push**; el **emulador es compartido y serializado** (un agente por vez corre `make e2e`/
maneja el AVD — compite con el runner de CI en esta misma máquina). La única escritura persistente
tuya es **tu archivo de memoria**, que vive fuera del repo.

### 5.5 Orquestación de la segunda pasada

- **Olas adaptadas** (la frescura pesa menos porque ya hay memoria, pero se respetan las aristas de
  §4.1): **usuario-dev + UX primero** (ojos frescos sobre la UI nueva), luego **DevOps**; **Dev abre**
  el tier informado (mapa del código nuevo), luego **QA + SRE**, después **Seguridad**, y **Arq-IA
  cierra**.
- **Emulador serializado:** nunca dos agentes con el AVD a la vez; el resto verifica por
  código/unit/host o espera.
- **Nota de registro:** la **fuente de verdad de las defs es el repo** (`docs/agentes/*.md`,
  ver su README); la copia instalada en `~/.claude-personal/agents/` es un artefacto que se
  re-copia tras cada edición (`cp docs/agentes/*.md ~/.claude-personal/agents/`). Un cambio
  toma efecto en una **sesión nueva**. Si la pasada corre en la misma sesión del edit, el
  prompt de spawn lleva la libertad + el reencuadre como respaldo. Las **memorias** de los
  agentes (`agents/memoria/`) son estado local de runtime y NO se versionan.

---

## 6. Tercera pasada — re-verificación + superficies nuevas (post v1.14.0–v1.20.0)

La 2ª pasada (§5) re-verificó los fixes de F6–F10 y cerró casi todo §F. Entre medio se hizo mucho más
(v1.14.0–v1.20.0). Esta 3ª pasada: cada perfil **re-verifica que sus ✅ de §F se sostengan** (sin confiar
en la marca) y **ataca las superficies nuevas** con su lente, aplicando el nuevo **§1.5** ("cuestioná la
premisa"). La lógica, el throwaway, la handoff y la consolidación son las mismas que §5.2/§5.4.

### 6.1 Qué cambió desde la 2ª pasada (digest)

- **Opción B (v1.14.0):** la **hoja de aprobación se REMOVIÓ**; alerta "Claude te espera" por hook
  host-side (`Notification/permission_prompt` → `marvin-notify.sh`); **modo lectura** (`hayPromptDeDecision`,
  firma real del PTY).
- **Freeze #1 (v1.15.x):** `VigiaUi` (watchdog UI); half-open → `forzarReconexion` (gate >3s, sin sonda).
- **v1.16.0:** diálogos legibles (tokens del tema); URL del repo en el quickstart.
- **v1.17.0:** `Diagnostico` **persistente** (`filesDir`, espeja `VigiaUi`); acceso **ⓘ** visible;
  "Limpiar" con confirmación.
- **v1.18.0:** fuente **persistida**; `DictationController.vivo` `@Volatile`.
- **v1.19.0:** **FGS aditivo** — `AvisosService` (dataSync), `EstadoApp`, toggle "Avisos en segundo
  plano", `NotificacionesRemotas`→`Context`.
- **v1.19.1:** dictado cancelado al cerrar su pestaña (`sesionEnJuego`/`tabCerrado`).
- **v1.20.0:** **OSC52 política A** — bloquea la copia host-iniciada (`copiaIniciadaPorVos`).
- **Plugin 1.7.0:** hook **PreToolUse** (`ExitPlanMode|AskUserQuestion`) = R1.
- **release.yml:** guard de **versionCode monotónico** + seeding.
- **Paso D:** `quitar_bloque_sentinelas` no borra a EOF sin `end`; `flag-subidos-context.sh` sólo lecturas.
- **Caveat de config:** hooks/notifs sólo disparan con el plugin cargado (remotemarvin sólo en
  `claude-personal`, no en el default).

### 6.2 Foco por perfil (re-verificar + atacar)

| Perfil | Re-verificar (sus ✅ de §F) | Atacar (superficies nuevas v1.14→v1.20) |
|--------|----------------------------|------------------------------------------|
| **usuario-final (dev dogfooder)** | diálogos oscuros (v1.16), ⓘ (v1.17), quickstart+URL | UX del toggle "Avisos en segundo plano", notif de **plan-mode** (R1), el **bloqueo OSC52** (¿confunde?), el **caveat de config**, modo lectura, fuente persistida. "¿lo adoptaría hoy?" |
| **ux-devex** | tema oscuro de diálogos, ⓘ; **re-scorear SUS** | affordance del toggle FGS + notif fija "avisos activos" + **Detener**; toast "Bloqueé una copia del host"; "Copiado" del Sel; notif de plan-mode |
| **devops** | teardown (F9), release.yml, distribución APK | **guard de versionCode** (bootstrap/seeding/¿release rechazable?); `<service>`/perms del FGS; hook plugin 1.7.0; cadencia de releases |
| **dev** | `listDocs`/`execResult` (F1) | correctitud/arq/deuda del código nuevo: `AvisosService`/`EstadoApp`, persistencia de `Diagnostico`, `DictationController` (`sesionEnJuego`/`tabCerrado`), `TerminalClients` (`copiaLaPediste`), `NotificacionesRemotas` (Context), `SshTerminalSession` (`forzarReconexion`), `VigiaUi`, `marvin-notify-decision.sh` |
| **qa** | `raro'nombre` (F1), rotación, sustitución de sesión | FGS (toggle/swipe/Detener/no-duplicar-tail); **OSC52 bloqueo** (ventana de gracia del Sel, selección nativa); dictado (cerrar durante grabación/round-trip/preview); persistencia del diagnóstico (overflow, crash); guard de versionCode |
| **sre** | H1-H7 (reconnect, notif, expiry F10, observabilidad) | **fix del freeze** (half-open/`forzarReconexion`/gate >3s — ¿confiable?); FGS vs Doze; **pérdida best-effort** (`tail -n0` saltea el hueco); ring buffer persistido; entrega R1 |
| **seguridad-ofensiva** | FLAG_SECURE (F3), teardown/`authorized_keys` | **OSC52 política A** (¿bypass? ventana de gracia; `isSelectingText` edge); hook **PreToolUse R1** (inyección vía `tool_name`/mensaje); **config-scoping** (¿riesgo que la notif no dispare?); PendingIntent del FGS + `POST_NOTIFICATIONS`; guard de versionCode. **Aplicá §1.5.** |
| **arquitecto-ia** | ASI01-10; hoja removida (opción B) | cómo mueven la superficie de confianza agéntica: notif por hook + **R1 plan-mode** + **bloqueo OSC52** (anti-secuestro por Claude inyectado) + hook PreToolUse; config-scoping. **Re-scorear ASI01-10.** §1.5. |

### 6.3 Orquestación

Olas como §5.5 (usuario-dev + UX → DevOps → Dev → QA + SRE → Seguridad → Arq-IA cierra); **emulador
serializado** (preferir verificación por código/unit/host; un AVD por vez, matarlo al terminar). Los
hallazgos confirmados se consolidan en una **§G** de `revision-integral.md` (los agentes proponen filas,
no las editan). Después, plan de implementación aparte.

---

## 7. Cuarta pasada — 8 perfiles sobre v1.28→v1.30 + fixes de onboarding (2026-08-27)

> **Registrada retroactivamente el 2026-09-03.** La 4ª pasada corrió **sin su sección en el playbook**: el
> foco por perfil viajó sólo en los prompts de spawn, y las defs (que dicen "la pasada VIGENTE es la última
> sección del playbook") resolvían a §6 (la 3ª). Su acta completa está en `docs/revision-integral.md` §L
> (hallazgos) y §K (charter §2.E), y las citas verbatim de los prompts en el registro local de invocaciones.
> Esta sección reconstruye lo que se pidió, para que la próxima pasada tenga precedente en el lugar correcto.

### 7.1 Qué cambió desde la 3ª pasada (digest, tal como se pasó a los agentes)

- **v1.28.x:** Enter en la fila de teclas; notificación de **plan-mode** (R1); filtrado de avisos.
- **v1.29.0:** dictado "sin frío"; keepalive.
- **v1.30.0:** **sticky de "acceso vencido"** (`marvints.Estado`), barra de la terminal con **⟲ Reescanear QR**,
  `EnrolarTailscale` (scanner compartido hosts/terminal), reinicio-tras-vencer + re-enrol de un toque
  validados en vivo.
- **Fixes de onboarding del mismo día (§K):** `ts-link-qr.sh` (curl fuera de `$(…)`, prereqs, header
  "efímera", label unificado), `setup-host.sh` (uv al inicio), README "Qué hace falta", quickstart in-app,
  manual/skill 1.9.0; y la auditoría de lineamientos (§J) que llevó las **defs de agentes al repo**.

### 7.2 Foco por perfil (lo que se pidió en los prompts)

| Perfil | Re-verificar (sus ✅ previos) | Atacar (superficies nuevas) |
|--------|-------------------------------|------------------------------|
| **dev** | sus ✅ de §F/§G | ciclo de vida del nodo en `marvints.go` (`Start`/`Stop`/`finish`, `configure()`), con un **lead del coordinador** (ventana de carrera en `finish`) a confirmar o refutar con `go test -race`; `TailscaleBridge`/`EnrolarTailscale` |
| **qa** | `raro'nombre`, rotación, sustitución de sesión | el **cluster de vencido** (sticky, ⟲, re-enrol, hosts vs terminal, dedup del Diagnóstico), sin duplicar la carrera que confirmaba dev |
| **seguridad-ofensiva** | FLAG_SECURE, OSC52 política A, teardown | **S8** (¿la auth key queda legible en `/proc/<pid>/cmdline` al pasar a `qrencode`?) con **PoC limpio** (el intento cortado se auto-capturó); §1.5 sobre el re-enrol |
| **sre** | H1-H7 | el **punto ciego del expiry del nodo del HOST** (§K: `remoteclaude` sin tag vence 2026-12-12; el stack de vencido sólo cubre el celu); recomendación concreta |
| **arquitecto-ia** | ASI01-10, hoja removida (opción B) | **re-enrol por QR como vector** (§1.5: ¿un QR rogue que mete el nodo en la tailnet de un atacante debería aplicarse sin confirmación? ¿qué gana y qué NO puede hacer?), consent-sin-info del re-enrol |
| **ux-devex** | diálogos oscuros, ⓘ, SUS | la **contradicción verde/rojo** (terminal honesto vs hosts con estado crudo del nodo) como problema de UX de confianza; paridad de superficies §1.6 |
| **usuario-final (dev dogfooder)** | H1/H6/H8 | **uso diario vivido** de lo nuevo (Enter, plan-mode, dictado, vencido/⟲); vara: "tu setup actual es ssh+tmux crudo: ¿esto te hace preferir la app o es fricción?"; ya había corrido el onboarding desde cero ese día |
| **devops** | teardown, release.yml, distribución | **confirmar que los fixes de onboarding de hoy quedaron bien** ("no confíes: verificá el estado actual del repo"); operabilidad continua (no el primer arranque); expiry del host |

### 7.3 Orquestación (lo que pasó) y lecciones

- **8 spawns en paralelo → rate limit 429**, cayeron los 8. Se relanzó en **dos olas de 4**: dev, qa,
  seguridad-ofensiva, sre (perfiles de código) → arquitecto-ia, ux-devex, usuario-final, devops (con digest
  de la ola 1). Eso invirtió el orden de frescura de §5.5/§6.3 (Arq-IA no cerró).
- **Emulador serializado por prompt; nadie lo usó**: el ciclo de vencido no era reproducible en el fixture
  (`enabled=false`) → los hallazgos de vencido quedaron "confirmados por código, no vividos". La validación
  on-device posterior (2026-09-02/03) encontró un gap que la pasada por código no vio (A4-1 mismatch).
- El coordinador **verificó cada claim contra el código** antes de asentarlo en §L; su propio lead de la
  carrera fue **refutado ejecutando** por dev (2,3 M de muestras) y reencuadrado al camino real (supersede).
- Se declaró "completa" con **un agente aún corriendo** (usuario-final, el más lento) → regla: llevar conteo
  lanzados-vs-completados; su caveat entró después como UF-1.
- El guard de secretos bloqueó la escritura del propio §L (heredoc citado que nombraba `.env`) → se refinó el
  guard con su autotest; nunca se esquiva.
- Resultado: 14 hallazgos verificados, todos aplicados en **v1.31.0** (plan por dependencias en 6 fases, un
  commit por fase) y el gap del mismatch en **v1.31.1**.

---

## 8. Quinta pasada del programa — 9 perfiles sobre v1.30.0→v1.31.1 (pasada VIGENTE)

Primera pasada con **nueve** perfiles (§2.I `disenador-grafico` hace su barrido inicial) y la primera en la
que el **ciclo de vencido se puede VIVIR en el emulador**: hay una cuenta Tailscale demo (expirar / re-enrolar
por API) y un sshd de prueba sin sudo con un shim de `docker` que simula el expiry del nodo del host. Lo que la
4ª confirmó por código, esta lo ejercita. La lógica, el throwaway, la handoff y la consolidación son las de
§5.2/§5.4; el pre-vuelo y los fixtures, los de §8.3.

### 8.1 Qué cambió desde la 4ª pasada (digest, 16 commits)

- **Bridge Go reescrito** (`tailscale-bridge/marvints.go`): closer único vía `cancel` (DEV-4A), `finish` sólo
  pisa si `upDone==done` (DEV-4B), patrones de rechazo ampliados + log de "no clasificado" (SRE-4p-2),
  `IdentidadRed` (A4-1). Tests `-race` nuevos. AAR reconstruido + **guard source↔AAR (`srchash`) en CI**.
- **Modelo UX del vencido** (`HostsActivity`, `MainActivity`, `SshTerminalSession`, `TailscaleBridge`):
  hosts en **ámbar** con reencuadre ("enrolamiento vencido — los hosts de LAN siguen"), clear del vencido
  **no optimista** (sólo con reconexión real por tailnet), latch local (DEV-4C), dedup del ERROR por episodio
  (QA4-4), sin filtrar el error crudo ni cortar el repoll (QA4-3), glifo ⟲→↺.
- **Funnel único de re-enrol** `EnrolarTailscale.aplicar` (QR terminal / QR hosts / key pegada): validación
  de forma de la key, toasts "Re-vinculando…" y "Vinculado a la tailnet: X" (sondeo 30 s, espera identidad).
- **Marcador global** `TailscaleBridge.ultimoReEnrolMs` (60 s): el diálogo "la clave del host cambió" no se
  ablanda tras un re-enrol por cualquiera de los tres caminos (`ReEnrolRecienteTest`).
- **Aviso proactivo del expiry del nodo del HOST** (`avisarSiExpiraHostPronto` + `marvin-doctor`); la línea
  en la terminal la borra el redibujo de tmux — queda en Diagnóstico.
- **Scripts de host:** auth key por **stdin** a `qrencode` (S8), `teardown-host.sh` quita `marvin-doctor`
  (DEVOPS-1), `EDITOR` en `update-environment` (UF-1), `ts-link-qr.sh`/`setup-host.sh`/README/quickstart
  del onboarding desde cero (§K).
- **Superficies:** manual y skill tocados seis veces (plugin 1.9.0 → 1.10.0 → 1.10.1); NOTICE.md.
- **Programa:** defs de agentes en `docs/agentes/`; 9º perfil `disenador-grafico` (§2.I). **Sin cambios:**
  `NotificacionesRemotas`, `AvisosService`, `Diagnostico`, `VigiaUi`, `DictationController`,
  `TerminalClients`, `RemoteControl`, `Tour`, `release.yml`, `e2e.yml`.

Leads del coordinador para **repartir, no asentar** (LEÍDOS, no ejecutados): **L1** tres ventanas para un mismo
episodio (25 s `reVinculando` en `MainActivity` / 30 s sondeo del toast en `EnrolarTailscale` / 60 s
`VENTANA_RE_ENROL_MS` en `TailscaleBridge`; en el AVD la reconexión ronda 20 s). **L2** `anunciarVinculacion`
lanza un hilo de 30 s por toque reteniendo la Activity. **L3** `avisarSiExpiraHostPronto` hardcodea
`docker exec remoteclaude-ts` (sin contenedor/jq = silencio) y el flag es por sesión. **L4** `HostsActivity`
repollea 1,5 s/3 s indefinidamente mientras `isEnabled && !isReady`. **L5** el guard `srchash` es de
consistencia, no de supply-chain.

### 8.2 Foco por perfil (re-verificar + atacar)

| Perfil | Re-verificar (sus ✅ shipeados en 1.31.x) | Atacar (superficies nuevas) — cómo / recurso |
|--------|--------------------------------------------|-----------------------------------------------|
| **usuario-final (dev dogfooder)** | UF-1 (`EDITOR` tras detach/reattach); sus sev-1 del 27/08 | Ciclo **vivido** desde APK virgen: quickstart → pegar key (`ts-demo.sh mint`) → host → `ts-demo.sh expire` → re-handshake (`adb shell svc wifi disable/enable`) → ámbar + banner + ↺ → key nueva → "Re-vinculando…" → "Vinculado a la tailnet: X" → verde sólo tras reconexión real; diálogo de host-key tras re-enrol (`test-sshd.sh rotate-hostkey`); aviso "vence en N días" (¿lo encuentra en Diagnóstico?); README + quickstart; vara "¿lo adoptaría hoy?". EMU (dueño en ola 1); cámara/QR = no vivible |
| **ux-devex** | UX-1 ámbar + reencuadre; UX-2 glifo ↺ (¿tofu en el AVD?); **re-scorear SUS** | Copy de los 4 estados/toasts; diálogo de host-key neutro; **L1** (¿qué ve el usuario a los 25-30 s?); paridad quickstart ↔ README ↔ manual (§1.6). Walkthrough con el handoff de usuario-final; EMU primero en ola 2; PDF |
| **devops** | S8 (fake `qrencode` volcando `/proc/self/cmdline`); DEVOPS-1; DEVOPS-2 (test negativo del `srchash` en copia); §K H1/H6/H7 | `setup-host.sh` uv al inicio vs segundo chequeo; Guidebook tour del README nuevo (charter §2.E **delta**); `marvin-doctor` con fake docker (TAGGED / null / N días / sin jq); teardown + doctor; **L5**; APK e2e-release ≠ publicado. HOME-sandbox + fakes (§1.6); sin EMU |
| **dev** | DEV-4A/4B/4C (`go test -race -count=50`); SRE-4p-2; `IdentidadRed` | `EnrolarTailscale.aplicar` (**L2**); `ultimoReEnrolMs`/`reEnrolRecienteDesde`; `avisarSiExpiraHostPronto` (**L3**, costo por conexión); repoll **L4**; matiz `esperaExpiro` H5/F5 (la primera llamada bloquea 15 s); cobertura de `display-entrypoint`. `make go/unit/lint`; entrega el **mapa del código nuevo** (½ carilla) para la ola 2; sin EMU |
| **qa** | QA4-1..4; QA4-5 (nota) | Matriz del vencido con `ts-demo.sh`: 2 pestañas (dedup), key inválida/usada/vencida, ↺ dos veces rápido (**L2**, supersede DEV-4B vivido), **L1** cronometrado, reinicio-tras-vencer, expire con app en background, host por LAN vs por tailnet, rotación durante "Re-vinculando…", diálogo de host-key dentro/fuera de 60 s. EMU segundo en ola 2 + Diagnóstico |
| **sre** | SRE-4p-1 (shim → "vence en 9 días"; §1.5: ¿la línea en terminal debería existir si tmux la borra?); SRE-4p-2 (¿el "no clasificado" llega a Diagnóstico?); 5e half-open | **L3**; umbral 21 d fijo y aviso por sesión (¿spam? ¿dedup?); expiry host + celu simultáneos; **L4** (30+ min en vencido: CPU, Diagnóstico 64 KB); Fase 0 del usuario. Shim + `make host` + código; EMU si libre |
| **seguridad-ofensiva** | S8 (PoC limpio, ojo auto-captura); A4-1 (**§1.5: ¿debe existir la rama ablandada?** 60 s tras cualquier `configure()` = coartada reabierta); QA4-3 (¿id de key en Diagnóstico/compartir?); `IdentidadRed` (¿expone dominio/email?) | `aplicar` ante clipboard hostil; el comando de `avisarSiExpiraHostPronto` construido en cliente y ejecutado en host (quoting; shim malicioso con JSON raro); `marvin-doctor` parseando JSON; `ts-link-qr.sh --config -` + `set -x`; **L5** modelo de amenaza. PoCs en test-sshd/sandbox + JVM; falsos positivos del guard se reportan, no se rodean |
| **arquitecto-ia** | A4-1 (consent-con-info, 60 s); ASI09; hook `flag-subidos-context.sh` | Re-enrol de un toque + marcador global + diálogo neutro como superficie agéntica (¿un Claude inyectado puede inducir a pegar una key ajena?); canal app→terminal del aviso (¿confundible con salida de Claude?); **§1.5 capstone**: tres pasadas endureciendo el vencido cuando §K H2 (tag → no expira) reduce el stack del celu — ¿reducir en vez de endurecer? Re-scorear ASI01-10. Cierra con el digest de ambas olas |
| **disenador-grafico** (barrido inicial) | — | Ch.1 isologo (`mipmap-*`, `marvin_isologo*.png`, splash, header) vs pp. 01-04 + SVG; Ch.2 color (`colors.xml`, `Paleta.kt` vs p. 05; píxel; WCAG AA del ámbar sobre petróleo); Ch.3 tipografía (4 roles; **quién dibuja ↺ ⟳ ✓ ⓘ 🔒 ⧉ ⇧**, cmap); Ch.4 iconografía (8 `ic_*.xml` vs gramática; íconos de AlertDialog/Toast); Ch.5 fuera de la app (PDF regenerado, README, release, QR ANSI, DocViewer, notificaciones); Ch.6 estados/toasts/diálogos de marca vs sistema (§1.5 primero). Medición + capturas; offline primero, EMU tras usuario-final |

### 8.3 Orquestación, fixtures y pre-vuelo

- **Pre-vuelo (bloqueante) antes del primer spawn:** repo limpio en el tag y `make all` verde; defs
  sincronizadas (`diff -q docs/agentes/*.md ~/.claude-personal/agents/`) y memorias presentes (no se editan);
  doc vivo sin párrafos stale; esta sección escrita; manual PDF regenerado en `~/RemoteMarvinDocs/`; APK
  **publicado** instalado **virgen** en el AVD (`adb uninstall` antes; nunca `e2e.sh`, que hace `-wipe-data`);
  helpers locales probados; guard de secretos activo; acta de conteo reanudable.
- **Fixtures y helpers** (locales, fuera del repo, en `~/claude-refs/`): `emu.sh` (start/stop/**lock
  <perfil>**/unlock/who/screencap — un dueño del AVD por vez), `ts-demo.sh` (list/mint/expire/revoke/status
  sobre la cuenta Tailscale DEMO; **nunca imprime credenciales**; los agentes NO leen el `.env`),
  `test-sshd.sh` (sshd de prueba en 2222 con shim de `docker`; authorize / rotate-hostkey / restore-hostkey).
  **Exclusión:** el fixture docker de e2e y el sshd de prueba comparten el puerto 2222 → nadie corre
  `make e2e` durante la pasada. Nunca contra el tailnet/nodo/contenedor real del usuario.
- **Olas (máx. 4 concurrentes, spawns escalonados 20-30 s):** **ola 1** usuario-final (dueño del AVD), devops
  (sandbox), dev (código; produce el mapa para la ola 2), disenador-grafico (offline primero, AVD cuando se
  libera) → **ola 2** ux-devex (AVD primero, con el handoff de usuario-final), qa (AVD segundo), sre,
  seguridad-ofensiva → **ola 3** arquitecto-ia cierra. Sin `-wipe-data` entre olas. **Gate entre olas:** conteo
  lanzados = completados, claims sev≥2 verificados por el coordinador, digest de ½ carilla en el acta.
- **Prompt de spawn:** los 7 bloques de §4.3 (incluidos time-box, mandato de investigación y handoff note,
  que la 4ª omitió) + método rotulado **EJECUTADO / LEÍDO** por hallazgo + filas **propuestas** para la sección
  nueva del doc vivo (no se edita) + memoria apendada como "5ª pasada del programa" (la memoria de
  usuario-final llama "5ª" a su corrida del 27/08, que es la 4ª del programa).
- **Consolidación:** sección **§M** de `docs/revision-integral.md` (antes de §L: el doc va en orden
  cronológico inverso), por clusters con `| Sev | ID | Hallazgo | Fix propuesto |`, "checks previos
  sostienen", "sin poder ejercer" (cámara/QR, S23). Luego repaso por qué/método, plan por dependencias
  aparte, y esta sección se actualiza con las lecciones.

---

*Los hallazgos de cualquier ejecución de este programa se consolidan en
`docs/revision-integral.md` (sección "Lo que queda abierto"), que es el backlog vivo del
proyecto.*

### 8.4 Lecciones de la corrida (2026-09-03, asentadas al cierre)

- **El limite de uso es compartido** entre coordinador y subagentes (ventana de 5 h): un agente sin
  limite consume ~25-45 puntos. Cuatro en paralelo tiraron un 429 a mitad de la ola 1. Regla:
  **maximo 2 concurrentes**, lanzar una tanda solo con el medidor de la barra de estado <= ~55 %
  (`statusline.sh` -> `rate_limits` en `statusline-last.json`), `autoContinueAtUsageLimit` activo, y
  esperar el reset (despertador con Monitor) en vez de recortar. **Nunca topes por agente** (ni
  tokens, ni llamadas, ni minutos, ni acortar sus mediciones): el usuario lo rechazo dos veces; una
  espera en primer plano no gasta tokens.
- **Un monitor de fondo NO despierta a un subagente**: sre quedo "finalizado" esperando 45 min. Las
  esperas largas van con `sleep 600` encadenados en primer plano; decirlo en el prompt.
- **El acta, los prompts y los checkpoints viven fuera de /tmp** (`~/claude-refs/pasada<N>/`): un
  reboot a mitad de pasada borro el scratchpad entero (acta, prompts, capturas, trace del ANR) y
  devolvio el AVD a un snapshot de un dia antes. Lo reconstruible se reconstruyo desde el contexto;
  la evidencia cruda no. Por eso §M cita archivo:linea + metodo de reproduccion, no capturas.
- **Fixture del emulador**: el APK publicado es arm64-only y muere con SIGILL bajo ndk_translation
  en el AVD x86_64; instalar SIEMPRE `assembleRelease -PmarvinEmuAbi` (= e2e.sh), copia durable en
  `~/claude-refs/apk/`. Tras un reboot verificar `ts_hostname` de la app vs `ts-demo.sh list`.
  Metodo adb paso a paso: con el teclado en pantalla abierto un tap en un boton cae en el teclado.
- **Fixtures nuevos que funcionaron**: tailnet DEMO por API (`ts-demo.sh`, nunca imprime creds),
  sshd de prueba sin sudo con shim de docker (`test-sshd.sh`, pid verificable para distinguir bug
  de artefacto: asi se refuto UF5-3), lock del emulador (`emu.sh`). Primera pasada que VIVE el
  vencido: tres perfiles lo ejercieron y el tema dominante (estado desacoplado del expiry) salio de
  ahi, no de leer codigo.
- **Prompts por archivo**: el brief completo en un .md durable y el prompt del spawn solo dice
  "leelo entero" + las reglas que no admiten excepcion. Los handoffs se apendan al archivo a medida
  que cierran las olas (ux-devex recibio lo vivido por usuario-final; qa el mapa de dev; arq-IA el
  digest verificado de los 8).
- **Verificar antes de asentar sigue rindiendo**: 3 refutaciones/degradaciones (UF5-3 artefacto,
  L2 cosmetico, la lectura de seguridad sobre onPause supersedida por el repro de qa) y un falso
  positivo que el propio sre descarto (key demo stale).
- **Guard de secretos**: falsos positivos nuevos (grep en codigo fuente con palabras de
  credenciales en la linea de comando; lectura solo-lectura del nodo real; `make host`; `tmux
  send-keys`). Reportados, no rodeados; a refinar con su autotest.
