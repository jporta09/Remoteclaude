"""DG-2 (5ª pasada): el verde de los activos de marca es el canónico del manual (#71BF44), no el
#42A648 de los SVG de origen. Muestra el color dominante opaco de los PNG que la app dibuja."""
import os
from collections import Counter

import pytest

PIL = pytest.importorskip("PIL.Image")

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(RAIZ, "android", "app", "src", "main", "res")
VERDE_CANONICO = (113, 191, 68)
VERDE_SVG_VIEJO = (66, 166, 72)


def _dominante_verde(ruta):
    im = PIL.open(ruta).convert("RGBA")
    c = Counter(p[:3] for p in im.getdata() if p[3] > 200 and p[1] > p[0] + 30 and p[1] > p[2] + 30)
    return c.most_common(1)[0][0] if c else None


@pytest.mark.parametrize("ruta", [
    "drawable-nodpi/marvin_iso.png",
    "drawable-nodpi/marvin_isologo.png",
    "drawable-nodpi/marvin_isologo_bar.png",
    "mipmap-xxxhdpi/ic_launcher_foreground.png",
    "mipmap-mdpi/ic_launcher_foreground.png",
])
def test_el_verde_de_marca_es_el_canonico(ruta):
    dom = _dominante_verde(os.path.join(RES, ruta))
    assert dom == VERDE_CANONICO, f"{ruta}: verde dominante {dom}"
    assert dom != VERDE_SVG_VIEJO
