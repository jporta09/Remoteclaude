# Licencias

**RemoteMarvin se distribuye bajo GPL-3.0-only** (texto completo en [`LICENSE`](LICENSE)).

No es una elección de estilo: la app incluye el motor de terminal de
[Termux](https://github.com/termux/termux-app), que es **GPLv3-only**, en
`android/terminal-emulator/` y `android/terminal-view/`. Ese código está enlazado dentro
del APK, así que la obra combinada sólo se puede distribuir bajo GPLv3. Las demás
dependencias son Apache-2.0 o BSD, que son compatibles hacia GPLv3 (en esa dirección).

## Código vendorizado

| Ruta | Origen | Licencia |
|---|---|---|
| `android/terminal-emulator/` | termux/termux-app | GPL-3.0-only |
| `android/terminal-view/` | termux/termux-app | GPL-3.0-only |

Ambos módulos conservan su `LICENSE` propio. Los cambios locales sobre ese código están
marcados con comentarios en el lugar; la idea es mantenerlos al mínimo para poder seguir
los upstream.

## Dependencias

| Dependencia | Para qué | Licencia |
|---|---|---|
| [connectbot/sshlib](https://github.com/connectbot/sshlib) | cliente SSH de la app | Apache-2.0 |
| [OkHttp](https://square.github.io/okhttp/) | WebSocket del dictado en vivo | Apache-2.0 |
| [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) | escaneo del QR de vinculación | Apache-2.0 |
| [Tailscale](https://github.com/tailscale/tailscale) (`tsnet`, vía gomobile) | red privada embebida | BSD-3-Clause |
| [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | dictado por lote (host) | MIT |
| [WhisperLiveKit](https://github.com/QuentinFuxa/WhisperLiveKit) | dictado en vivo (host) | Apache-2.0 |
| [noVNC](https://novnc.com/) / [TigerVNC](https://tigervnc.org/) | visor del escritorio (host) | MPL-2.0 / GPL-2.0 |

Los dos últimos grupos corren **en el host**, en su propio proceso o contenedor: no se
enlazan con la app.

## Fuentes

Las fuentes de marca comerciales (Brandon Grotesque) **no** están en el repo: `fonts/` está
en `.gitignore`. Lo que sí se distribuye, en `android/app/src/main/res/font/`:

| Archivo | Fuente | Licencia | Dónde se usa |
|---|---|---|---|
| `osifont.ttf` | [osifont](https://github.com/hikikomori82/osifont) | GPL-3.0 con *font exception* | títulos de la app y del manual |
| `ubuntu.ttf` | [Ubuntu](https://design.ubuntu.com/font) | Ubuntu Font Licence 1.0 | cuerpo del manual |
| `mononoki.ttf` | [Mononoki](https://madmalik.github.io/mononoki/) | SIL Open Font License 1.1 | terminal y código |
| `mononoki_bold.ttf` | Mononoki | SIL Open Font License 1.1 | destacados del manual |
| `marvin_icons.ttf` | Noto Emoji + Noto Sans Math + Noto Sans Symbols 2 | SIL Open Font License 1.1 | los 8 íconos de la interfaz |

`marvin_icons.ttf` son 3 KB con exactamente los ocho glifos de los botones (🖥 📄 🔑 🎤 ⧉ ⇧
⟳ ✕). Se arma con `scripts/build-icon-font.py`; ninguna fuente libre sola los cubre a los
ocho. Antes los dibujaba la fuente del sistema, así que se veían distintos en cada teléfono
y ⧉ (U+29C9) es tan poco frecuente que ni DejaVu lo tiene.

Y fuera del APK, sólo para generar el manual en PDF:

| Archivo | Fuente | Licencia | Dónde se usa |
|---|---|---|---|
| `scripts/manual-fonts/NotoEmoji-subset.ttf` | [Noto Emoji](https://fonts.google.com/noto/specimen/Noto+Emoji) | SIL Open Font License 1.1 (copia en `scripts/manual-fonts/OFL.txt`) | los íconos 🎤 🖥 📄 🔑 del manual |

Las tres fuentes de origen (Noto Emoji, Noto Sans Math, Noto Sans Symbols 2) son de Google
bajo SIL OFL 1.1 y ninguna declara *Reserved Font Name*, así que los subsets pueden
redistribuirse. La copia de la licencia está en `scripts/manual-fonts/OFL.txt`.

Es un **subset** de los cuatro glifos que el manual usa: 2 KB en vez de los 1,9 MB del
original. La OFL permite modificar y redistribuir, y esta fuente no declara *Reserved Font
Name*. No entra en el APK — ahí los emoji los dibuja el sistema.

`osifont` reemplazó a `isocpeur.ttf`, que llevaba `Copyright 1997, 1998 Autodesk Inc. All
rights reserved.` — una fuente de AutoCAD, sin ninguna concesión de redistribución. Para un
proyecto GPL-3.0 eso es directamente incompatible: la licencia exige poder redistribuir todo
lo que se distribuye. osifont persigue la **misma norma ISO 3098**, así que conserva el aire
de rotulación técnica, y su licencia es compatible.
