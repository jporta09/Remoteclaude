# Código vendorizado de Termux (GPLv3)

`terminal-emulator/` y `terminal-view/` son código de [Termux](https://github.com/termux/termux-app)
(GPL-3.0), copiado (vendorizado) a este repo.

## Modificaciones de Remoteclaude
- `TerminalSession.java` reescrita como **abstracta y agnóstica al transporte**:
  se quitó el spawner de proceso local por **JNI/PTY** (`JNI.java`, `src/main/jni/`)
  para poder alimentar el emulador desde un transporte externo (eco, SSH).
- Se eliminaron `JNI.java`, la carpeta `src/main/jni/` y los tests.
- `TerminalView.java`: el `inputType` del editor pasó de `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
  a `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS` (el tipo password rompía la entrada
  de acentos/caracteres especiales en varios teclados).

Por la GPL-3.0, todo el proyecto que enlaza este código queda bajo GPL-3.0, y se
provee el código fuente correspondiente.
