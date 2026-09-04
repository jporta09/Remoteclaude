#!/usr/bin/env python3
"""Re-tinta el verde de los activos de marca al canónico #71BF44 (DG-2, 5ª pasada).

Los SVG oficiales del manual traían #42A648 y de ahí salieron los PNG del isologo y el launcher,
mientras toda la UI usa el verde del manual (#71BF44). El dueño de la marca eligió el del manual.
Sólo se tocan los píxeles del tono del verde VIEJO (122° ± 12°, saturación ≥ 0,35): el canónico
(98°) no se toca aunque se corra dos veces; el petróleo, el blanco y la transparencia quedan
intactos, y los bordes antialiasados conservan su oscuridad relativa (se escala el valor HSV, no
se pisa el color).

Uso:  uv run --with pillow python scripts/retint-brand.py <png...>
"""
import colorsys
import sys

from PIL import Image

VIEJO = (66, 166, 72)
NUEVO = (113, 191, 68)


def _hsv(rgb):
    return colorsys.rgb_to_hsv(*(c / 255 for c in rgb))


def retintar(im: Image.Image) -> tuple[Image.Image, int]:
    im = im.convert("RGBA")
    hv, _, vv = _hsv(VIEJO)
    hn, sn, vn = _hsv(NUEVO)
    px = im.load()
    cambiados = 0
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if not (abs(h - hv) <= 12 / 360 and s >= 0.35):
                continue
            v2 = min(1.0, v * (vn / vv))
            r2, g2, b2 = (round(c * 255) for c in colorsys.hsv_to_rgb(hn, sn, v2))
            px[x, y] = (r2, g2, b2, a)
            cambiados += 1
    return im, cambiados


def main(rutas):
    for ruta in rutas:
        im, n = retintar(Image.open(ruta))
        if n:
            im.save(ruta, optimize=True)
        print(f"{ruta}: {n} píxeles re-tintados")


if __name__ == "__main__":
    main(sys.argv[1:])
