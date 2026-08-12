# Modelo de amenaza

RemoteMarvin le da a un teléfono una shell en tu máquina. Este documento dice **contra qué
protege y contra qué no**, porque la diferencia importa más que la lista de mecanismos.

## Qué se asume

- **El host es tuyo y confiás en él.** La app te da una shell: quien controle el host ya
  ganó. Acá no hay sandbox contra tu propia máquina.
- **La tailnet es privada pero no es un perímetro.** Tailscale cifra y autentica el
  transporte, pero cualquier dispositivo de tu tailnet —o cualquiera que quede autorizado
  en ella— alcanza lo que esté escuchando. Por eso nada se apoya *sólo* en estar adentro.
- **El teléfono puede perderse.** Los secretos en reposo se protegen suponiendo que
  alguien puede terminar con el dispositivo en la mano.

## Contra qué protege

| Amenaza | Cómo |
|---|---|
| Alguien en tu wifi mirando o controlando el escritorio virtual | El noVNC se publica **sólo en loopback** del host: desde la LAN no existe. La app llega tunelizándolo por su conexión SSH. |
| Un proceso local del host entrando al escritorio | `Xvnc` pide password (**VncAuth**). Se genera nuevo en cada arranque del contenedor y se publica en `~/.config/marvin/vnc-pass` en 0600; la app lo lee por tu propia conexión SSH y se lo pasa al visor, así que nunca lo tipeás. |
| Un proceso local leyendo la pantalla o inyectando teclas por el servidor X | El `:99` ya no corre con `-ac`: exige **MIT-MAGIC-COOKIE-1**. La cookie se publica en `~/.config/marvin/Xauthority` (0600) y la usan el render-daemon y `run-visible.sh`. Sin ella, el display rechaza la conexión. |
| Un intermediario haciéndose pasar por tu host | La clave del host se **fija en el primer uso** (TOFU) y, si cambia, la conexión se **rechaza**. Sólo la terminal ofrece confiar en la nueva, con las dos huellas a la vista; documentos, dictado y visor fallan sin preguntar. |
| Alguien en la red del celular entrando al dictado | El túnel local bindea `127.0.0.1`, no `0.0.0.0`. |
| Un server ajeno al que SSH-eás desde la app | El `RemoteForward` que le lleva tu display y tu render-daemon sólo se tiende a servers en una **allowlist explícita** (`marvin-allow-display <host>`). |
| Un proceso remoto escribiendo el portapapeles del teléfono | OSC 52 copia sin preguntar sólo por debajo de ~100 KB; arriba de eso pide confirmación con el tamaño a la vista. |
| Nombres hostiles en comandos remotos | Todo lo que se interpola en un comando SSH pasa por comillado POSIX (`ShellQuote`) y los nombres de sesión por `TmuxName`. |
| Robo del teléfono | La auth key de Tailscale y demás secretos se guardan cifrados (AES-GCM) con una clave del **AndroidKeyStore**, que no sale del dispositivo; `allowBackup=false` evita que salgan en un backup. |
| Que R8 rompa algo en silencio en el APK publicado | `verifyReleaseKeepRules` verifica en cada release que lo que se busca por nombre en runtime siga intacto. |

## Contra qué NO protege

- **Un host comprometido.** Es el punto de partida, no una falla.
- **Alguien con tu teléfono desbloqueado.** La app no pide segundo factor. Si eso te
  importa, el candado del sistema es la defensa.
- **Otros usuarios locales del host.** Los daemons escuchan en loopback y el drop de
  archivos está en `0700`, pero un usuario local con paciencia tiene superficie. La app
  está pensada para máquinas de un solo dueño.
- **Un proceso local que ya tenga la cookie de X.** El `:99` ahora exige
  MIT-MAGIC-COOKIE-1, pero el archivo es legible por tu usuario: cualquier cosa que corra
  *como vos* puede leerlo. Es el mismo límite que tiene tu sesión X de escritorio.
- **Tráfico fuera de la tailnet.** Todo lo que la app hace va por Tailscale o por SSH; no
  hay canal propio ni telemetría.

## Instalar sin root (`setup-host.sh --sin-sudo`)

En un servidor donde no tenés permisos, el setup instala igual todo lo que es a nivel usuario
(daemons, `~/.ssh/config`, `~/.tmux.conf`) pero **no puede endurecer el sshd**. Como todo el
modelo asume solo-clave, ese modo **verifica** en qué estado quedó — preguntándole al propio
servidor qué métodos de autenticación ofrece — y avisa fuerte si acepta contraseñas, en vez de
dejarlo librado a un "revisalo a mano". La verificación también cuenta
`keyboard-interactive`: es la puerta de atrás clásica, con `PasswordAuthentication no` puesto
pero PAM pidiendo contraseña igual.

## Reportar un problema

El repo es personal y no tiene programa de bug bounty. Si encontrás algo, abrí un issue
—o, si es sensible, escribime en privado— con pasos para reproducirlo.

## Dónde está el detalle

`docs/revision-integral.md` tiene los ~55 hallazgos de la revisión con su estado, incluido
lo que quedó abierto a propósito y por qué.
