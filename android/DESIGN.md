# RemoteMarvin — diseño de la app

Cómo está armada por dentro y **por qué**. Lo que hace de cara al usuario está en el
[README de la raíz](../README.md); el modelo de amenaza, en [`SECURITY.md`](../SECURITY.md).

## La decisión de fondo

La primera versión usaba un **contenedor privilegiado** con `pid: host` cuyo sshd hacía
`nsenter --target 1` para saltar a la shell del host, y mosh para sobrevivir a los cortes.
Eso se eliminó. Hoy la app **SSH-ea al sshd del propio host** como tu usuario, con `tmux`
del lado del host para persistencia.

El cambio compra tres cosas: no hay nada corriendo con privilegios de root sólo para darte
una terminal; el host no necesita más que `openssh-server` y `tmux`; y la persistencia queda
en manos de `tmux`, que ya la resolvía mejor que mosh para este caso (la sesión sigue viva
aunque el teléfono se apague por horas).

## Piezas

```
MainActivity ── arma el layout y cablea; ciclo de vida, permisos, red
   │            y los diálogos de identidad del host
   ├── TabsController      pestañas = sesiones tmux; barra, persistencia, reenganche
   ├── KeypadView          teclas extra; Ctrl/Alt/Shift como modificadores pegajosos
   ├── DictationController dictado por voz (en vivo + por lote)
   └── TerminalClients     callbacks del motor vendorizado (zoom, modificadores, OSC 52)

SshTerminalSession   una sesión SSH+tmux, con reconexión
RemoteControl        comandos sueltos por SSH (listar sesiones, renombrar, matar, dictar)
HostKeys             fijación TOFU de la clave del host
KeyStoreSsh          par de claves de la app, privada en el AndroidKeyStore
SecretStore          secretos cifrados (AES-GCM) con una clave del Keystore
TailscaleBridge      nodo Tailscale embebido (tsnet vía gomobile)
PortTunnel           port-forward local sobre la conexión SSH (visor, dictado en vivo)
LiveDictation        WebSocket contra WhisperLiveKit, tunelizado
```

Y la lógica que no necesita Android, separada para poder probarla en milisegundos:
`TabPlan` (nombre libre de pestaña, índice activo tras cerrar), `TerminalKeys` (bytes de
Ctrl, CSI Z), `ShellQuote`, `TmuxName`, `WavHeader`, `WlkSnapshot`, `DocKind`.

## Decisiones que no son obvias

**Las pestañas son sesiones tmux, no conexiones.** El nombre de una pestaña nueva se elige
mirando las sesiones abiertas **y las que ya existen en el host**: si sólo mirara las
propias, `tmux new -A` engancharía una sesión de trabajo ajena creyendo que crea una nueva.
Por lo mismo, `"term N"` tiene que ser punto fijo del saneado de nombres — si el saneado lo
tocara, al reconectar la app buscaría un nombre distinto del que creó y perderías pestañas.

**La clave del host se fija y su cambio se rechaza.** Sólo la terminal ofrece confiar en la
clave nueva, mostrando las dos huellas. Documentos, dictado y visor fallan sin preguntar: si
cualquier camino pudiera aceptar una clave nueva, confiar dejaría de ser una decisión.

**Ctrl/Alt/Shift son modificadores pegajosos.** Se tocan, quedan activos y el siguiente
carácter sale modificado, como en una terminal de escritorio. Por eso su estado vive en
`KeypadView`, junto a los botones que lo muestran, y no suelto en la activity.

**El visor y el dictado viajan por la conexión SSH.** Nada de ellos escucha en la red: el
noVNC del host está publicado sólo en loopback y la app lo alcanza tunelizando ese puerto.
El túnel local bindea `127.0.0.1` explícitamente — el overload por defecto de sshlib bindea
`0.0.0.0`, que dejaría el dictado abierto a la red del teléfono.

**El dictado fija la sesión destino al soltar el micrófono, no al terminar.** El round-trip
tarda segundos y, si mientras tanto cambiás de pestaña, el texto aparecía en la equivocada.

**El nodo Tailscale necesita que le pasen las interfaces de red.** En Android, Go no puede
enumerarlas, así que la app se las alimenta con `netmon.RegisterInterfaceGetter` y las
refresca en cada cambio de red; sin eso, el nodo no se recupera de un wifi → datos.

## Stack

Kotlin, vistas programáticas (sin XML de layout ni Compose), AGP 8.5.2, Gradle 8.9.

| Dependencia | Para qué |
|---|---|
| `org.connectbot:sshlib` | cliente SSH (fork de trilead) |
| motor de terminal de Termux, vendorizado | emulación y vista de terminal |
| `okhttp` | WebSocket del dictado en vivo |
| `zxing-android-embedded` | escaneo del QR de vinculación |
| `marvints.aar` | Tailscale embebido (`tsnet`, vía gomobile) |

R8 está activo en release. Las reglas conservan lo que `libgojni.so` busca **por nombre vía
JNI** y trilead, que elige algoritmos por nombre de clase; `verifyReleaseKeepRules` lo
verifica en cada build. Ver [`app/proguard-rules.pro`](app/proguard-rules.pro).

## Qué falta

`docs/revision-integral.md` lleva el registro vivo. Lo abierto a hoy: password de VNC y
sacar `-ac` del `Xvnc` (defensa en profundidad; la exposición ya está cerrada), y que la
verificación del release sea caja negra sobre el APK byte-idéntico al publicado.
