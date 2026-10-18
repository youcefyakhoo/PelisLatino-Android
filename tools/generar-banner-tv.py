#!/usr/bin/env python3
"""Genera el banner de Android TV (320x180 px, el tamano que pide el lanzador).

Por que un PNG y no un vector: `android:banner` lo pinta el lanzador del
televisor, y la mayoria espera un mapa de bits de 320x180. Un vector se escala
raro y no admite texto (los VectorDrawable no llevan tipografia).

Salida: app/src/main/res/drawable-nodpi/tv_banner.png
"""
import os
from PIL import Image, ImageDraw, ImageFont

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FUENTE = os.path.join(RAIZ, "app", "src", "main", "res", "font", "poppins_semibold.ttf")
SALIDA = os.path.join(RAIZ, "app", "src", "main", "res", "drawable-nodpi", "tv_banner.png")

ANCHO, ALTO = 320, 180

# Los tres cortes del degradado teal de la app (values/colors.xml).
ARRIBA = (10, 126, 144)    # pelis_wall_top
MEDIO = (2, 174, 196)      # pelis_wall_mid
ABAJO = (1, 118, 142)      # pelis_wall_bottom

img = Image.new("RGB", (ANCHO, ALTO))
px = img.load()

# Degradado vertical en tres tramos: sube hasta el medio y vuelve a bajar.
for y in range(ALTO):
    t = y / (ALTO - 1)
    if t < 0.5:
        k = t / 0.5
        a, b = ARRIBA, MEDIO
    else:
        k = (t - 0.5) / 0.5
        a, b = MEDIO, ABAJO
    color = tuple(round(a[i] + (b[i] - a[i]) * k) for i in range(3))
    for x in range(ANCHO):
        px[x, y] = color

dibujo = ImageDraw.Draw(img, "RGBA")

# Un halo suave arriba a la izquierda, como el bokeh del fondo de la app.
halo = Image.new("RGBA", (ANCHO, ALTO), (0, 0, 0, 0))
hd = ImageDraw.Draw(halo)
for r in range(120, 0, -4):
    alfa = int(26 * (1 - r / 120.0))
    hd.ellipse([40 - r, 20 - r, 40 + r, 20 + r], fill=(255, 255, 255, alfa))
img = Image.alpha_composite(img.convert("RGBA"), halo).convert("RGB")
dibujo = ImageDraw.Draw(img, "RGBA")

# El anillo del boton "Todos", a la izquierda del texto.
cx, cy, radio = 62, 90, 26
dibujo.ellipse([cx - radio, cy - radio, cx + radio, cy + radio],
               outline=(255, 255, 255, 255), width=4)
dibujo.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=(255, 255, 255, 255))

# Nombre. Se baja el tamano si no cabe, en vez de recortarlo.
texto = "BLABONGO"
x_texto = 110
for tamano in range(46, 17, -2):
    fuente = ImageFont.truetype(FUENTE, tamano)
    caja = dibujo.textbbox((0, 0), texto, font=fuente)
    if caja[2] - caja[0] <= ANCHO - x_texto - 16:
        break
alto_texto = caja[3] - caja[1]
dibujo.text((x_texto, cy - alto_texto / 2 - caja[1]), texto, font=fuente,
            fill=(255, 255, 255, 255))

os.makedirs(os.path.dirname(SALIDA), exist_ok=True)
img.save(SALIDA, "PNG", optimize=True)
print("escrito %s  %dx%d  %d bytes" % (SALIDA, ANCHO, ALTO, os.path.getsize(SALIDA)))
