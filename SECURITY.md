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
- **El contenido del escritorio virtual.** `Xvnc` corre sin password: lo que lo protege es
  que su puerto no sale del loopback y que llegar exige tu clave SSH. Sumar `VncAuth` y
  sacar `-ac` sigue pendiente como defensa en profundidad.
- **Tráfico fuera de la tailnet.** Todo lo que la app hace va por Tailscale o por SSH; no
  hay canal propio ni telemetría.

## Reportar un problema

El repo es personal y no tiene programa de bug bounty. Si encontrás algo, abrí un issue
—o, si es sensible, escribime en privado— con pasos para reproducirlo.

## Dónde está el detalle

`docs/revision-integral.md` tiene los ~55 hallazgos de la revisión con su estado, incluido
lo que quedó abierto a propósito y por qué.
