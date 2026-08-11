"""Arma la fuente de íconos de la app tomando los contornos de tres fuentes OFL.

Los ocho íconos de la interfaz (🖥 📄 🔑 🎤 ⧉ ⇧ ⟳ ✕) no están en ninguna de las fuentes de
marca, así que sin esto los dibuja la fuente del sistema: se ven distintos en cada teléfono
y ⧉ (U+29C9) es tan poco frecuente que ni DejaVu lo tiene — en un equipo sin cobertura el
botón "Sel" saldría como un cuadrito.

Ninguna fuente libre sola los cubre a los ocho: los emoji salen de Noto Emoji, ⧉ ⇧ ⟳ de Noto
Sans Math y ✕ de Noto Sans Symbols 2. Se construye una fuente nueva con FontBuilder en vez de
fusionar las originales: fusionar arrastra tablas incompatibles entre sí (MATH, OS/2 de
versiones distintas) y falla de formas poco claras.

Uso (las tres fuentes se bajan de github.com/google/fonts, todas SIL OFL 1.1):
    uv run --with fonttools python scripts/build-icon-font.py <dir-con-las-fuentes> <salida.ttf>
"""
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem
from fontTools.varLib.instancer import instantiateVariableFont

SP, SALIDA = sys.argv[1], sys.argv[2]
UPEM = 1000

# glifo -> (fuente, ¿es variable?)
FUENTES = [
    (f"{SP}/NotoEmoji.ttf", [0x1F5A5, 0x1F4C4, 0x1F511, 0x1F3A4], True),
    (f"{SP}/notosansmath.ttf", [0x29C9, 0x21E7, 0x27F3], False),
    (f"{SP}/notosanssymbols2.ttf", [0x2715], False),
]

glifos = {".notdef": TTGlyphPen(None).glyph()}
anchos = {".notdef": (UPEM // 2, 0)}
cmap = {}

for ruta, cps, variable in FUENTES:
    f = TTFont(ruta)
    if variable and "fvar" in f:
        f = instantiateVariableFont(f, {"wght": 400}, inplace=True)
    if f["head"].unitsPerEm != UPEM:
        scale_upem(f, UPEM)
    gs = f.getGlyphSet()
    cm = f["cmap"].getBestCmap()
    for cp in cps:
        origen = cm[cp]
        nombre = f"uni{cp:04X}"
        pen = DecomposingRecordingPen(gs)   # aplana los compuestos
        gs[origen].draw(pen)
        tt = TTGlyphPen(None)
        pen.replay(tt)
        glifos[nombre] = tt.glyph()
        anchos[nombre] = f["hmtx"].metrics[origen]
        cmap[cp] = nombre

orden = list(glifos)
fb = FontBuilder(UPEM, isTTF=True)
fb.setupGlyphOrder(orden)
fb.setupCharacterMap(cmap)
fb.setupGlyf(glifos)
fb.setupHorizontalMetrics(anchos)
fb.setupHorizontalHeader(ascent=800, descent=-200)
fb.setupNameTable({
    "familyName": "Marvin Icons",
    "styleName": "Regular",
    "psName": "MarvinIcons-Regular",
    "version": "1.0",
    "copyright": "Glifos de Noto Emoji, Noto Sans Math y Noto Sans Symbols 2 "
                 "(Google, SIL Open Font License 1.1). Subset para RemoteMarvin.",
    "licenseDescription": "Licensed under the SIL Open Font License, Version 1.1.",
    "licenseInfoURL": "https://scripts.sil.org/OFL",
})
fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
fb.setupPost(keepGlyphNames=False)
fb.save(SALIDA)
print(f"{SALIDA}: {len(cmap)} glifos")
