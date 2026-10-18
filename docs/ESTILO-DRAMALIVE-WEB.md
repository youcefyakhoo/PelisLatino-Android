# web.dramalive.cc — sistema de diseño medido, y cómo se aplicó a la app

> Este documento **no describe una impresión visual**: cada número de aquí salió
> de medir el sitio real. El sitio se inspeccionó por CDP sobre el Chrome que ya
> estaba abierto en el puerto 9222, sin lanzar un navegador nuevo, y los volcados
> crudos están en `recon-dramalive/out/` para que cualquiera pueda rehacer las
> cuentas.

## 1. Método (por qué no bastó con "mirar el CSS")

`https://web.dramalive.cc/` es una app **Flutter web**. Eso cambia por completo
la forma de sacarle los tokens:

- No hay DOM de la interfaz. El contenido se pinta en `<canvas>` dentro de
  `<flt-glass-pane>`, así que `getComputedStyle` sobre los elementos de la
  pantalla devuelve **cero**: el primer barrido (`01-snapshot.mjs`) encontró
  0 variables CSS y un árbol de 31 nodos con un solo hijo real.
- El HTML útil es el `flutter_bootstrap.js` (cargador estándar) y un
  `manifest.json` con `theme_color: #ffffff`, que no dice nada del tema oscuro.
- El bundle `main.dart.js` (4.36 MB) está minificado por dart2js y **no conserva
  los literales de color en hexadecimal**: las constantes ARGB quedan como
  enteros decimales sueltos. El barrido de `0xFF......` da 0 aciertos; el de
  enteros sí recupera la paleta de Material por defecto (`#2196F3`, `#F44336`,
  `#4CAF50`, `#FFD740`, `#18FFFF`, `#69F0AE`, `#E040FB`, `#8AF684`, `#FFDBFF`…),
  que es justo la que el sitio usa para el confeti del fondo.

La vía que sí funcionó:

| Paso | Script | Qué saca |
|---|---|---|
| 1 | `recon-dramalive/01-snapshot.mjs` | Capturas, histograma de color, esqueleto DOM |
| 2 | `recon-dramalive/02-frames-semantica.mjs` | **Árbol de semántica de Flutter** (236 nodos con rol, etiqueta y rect) |
| 3 | `recon-dramalive/03-colores.py` | Moda de color por región sobre la captura |
| 4 | `recon-dramalive/04-pantallas.mjs` | Inventario de pantallas navegando el raíl |
| 5 | `recon-dramalive/05-bundle.mjs` | Descarga y analiza `main.dart.js` |
| 6 | `recon-dramalive/06-movil.mjs` | Render a **393×851 @3x** en una pestaña aparte (el tamaño que importa para Android) |
| 7 | `recon-dramalive/07-tokens.py` | Tokens por región (moda, no píxel suelto) |
| 8 | `recon-dramalive/08-geometria.py` | Radios, huecos, alturas y perfil del degradado, medidos por borde |
| 9 | `recon-dramalive/09-generar-confeti.py` | Genera `res/drawable/bg_confetti.xml` |

**El truco que abrió la puerta** fue el paso 2: Flutter web trae un
`<flt-semantics-placeholder>` que, al pulsarlo, activa el árbol de accesibilidad
y materializa la interfaz como DOM real con `role`, `aria-label` y geometría
exacta. Sin eso solo había una captura de pantalla; con eso hay posiciones al
píxel (de ahí salen, por ejemplo, los 72 px del raíl y los 88 px de paso entre
categorías).

## 2. De qué está hecha la piel

El resumen que lo explica todo: **no es una app negra con acento de color, es una
app teal con vidrio encima de un fondo de color.**

1. Un fondo ("wallpaper") **degradado teal** con bokeh y confeti, que va de
   oscuro arriba a brillante en el centro y vuelve a bajar.
2. Encima, **superficies translúcidas**: cabecera, tiles, chips y el cajón son
   veladuras que dejan pasar ese fondo.
3. Un único color de acento, **azul `#2196F3`**, usado como **relleno** del
   elemento activo — nunca como anillo.
4. **Blanco puro** para todo el texto.
5. Un borde **negro** fino alrededor de los tiles, no un filete claro.

## 3. Color (medido)

### Fondo (perfil vertical, columna de hueco entre tiles, móvil)

| Altura | HEX medido |
|---|---|
| 0 dp | `#067888` |
| 160 dp | `#01A9C1` |
| 280–560 dp | `#01ABBF` → `#02AEC4` (meseta) |
| 600 dp | `#0199B1` |
| 720 dp | `#02A0B8` |
| 800 dp | `#027C95` |
| 840 dp | `#01758D` |

Se reproduce con tres paradas: `pelis_wall_top #0A7E90` → `pelis_wall_mid
#02AEC4` → `pelis_wall_bottom #01768E`.

### Superficies

| Elemento | Medido (escritorio) | Medido (móvil) | Token |
|---|---|---|---|
| Raíl lateral | `#001E24`, `#002C31` | — | `pelis_bg_dark #001A20` |
| Raíl, ítem activo | `#273D42` (51 % de la región) | — | `pelis_card_high` |
| Cabecera / barra | `#024C5C`, `#024E5E` | `#025462`, `#017282` | `pelis_surface #024C5C` |
| Campo de búsqueda | `#276877` | — | `#28FFFFFF` sobre cabecera |
| Tira de categorías | `#0A4F60`, `#034E60` | `#025A6A` | `pelis_scrim_soft` |
| Tile de canal | `#023C46`, `#023C47` | `#048A98`, `#015E68` | `pelis_tile_bg #B3023A44` |
| Hueco entre tiles (fondo) | — | `#00A1B6` | (degradado) |
| Bajo la rejilla | — | `#014E5B` | `pelis_wall_bottom` |
| Cajón (drawer) | — | `#000B0D`, `#001517` | `pelis_nav_bg #F2000E12` |
| Texto del cajón | — | `#E6E7E7` | `pelis_text #FFFFFF` |
| Borde del tile | `#000000` (fila 142) | `#000202` | `pelis_tile_edge #CC000000` |

### Acento

| Uso | HEX | Dónde se midió |
|---|---|---|
| Relleno del elemento activo | **`#2196F3`** | 40.5 % de la región del chip de país seleccionado (escritorio) y 19.1 % en móvil |

`#2196F3` es Material Blue 500, y también aparece en el bundle del propio sitio.
El sitio **no tiene un segundo color de marca**: todo lo demás lo resuelve con
blanco sobre teal.

### Confeti (paleta del bundle `main.dart.js`)

`#FFFFFF` `#F44336` `#FF9800` `#FFD740` `#4CAF50` `#2196F3` `#9C27B0` `#E91E63`
`#3F51B5` `#18FFFF` `#64FFDA` `#69F0AE` `#40C4FF` `#FF5252` `#FFAB40` `#FF4081`
`#448AFF` `#E040FB` `#8AF684` `#FFDBFF` `#AB24E3` `#2AF1F1` `#F6E309` `#5BB1F?`
`#FA3942` `#C00B31` `#EFF47D`

## 4. Geometría (medida)

### Móvil — 393×851 @3x (esto es lo que importa para Android)

| Medida | Valor medido | Cómo se modela en Android |
|---|---|---|
| Rejilla de canales | **3 columnas** (paso 121 dp) | `columnasPara()` = `round(ancho/121)` |
| Ancho de tile | **112.3 dp**, cuadrado | derivado del ancho |
| Hueco entre tiles | **9 dp** | `canal_tile_gutter = 4.5dp` (el margen lo pone cada tile) |
| Margen lateral | 19 dp | padding 16 dp + los 4.5 dp del margen |
| Radio del tile | **14.3 dp** (ajuste del arco: 43 px a 3x) | `radius_tile = 15dp` |
| Cuadro de categoría | **47.7 × 47.7 dp** | `canal_tab_icon = 48dp` |
| Bloque de la categoría activa | **76.3 × 52.7 dp** | `<inset>` de −14 dp / −3 dp en `bg_canal_tab` |
| Rótulo de categoría | cap height 7.3 dp → ~12 sp | `TextAppearance.Blabongo.TabLabel` |
| Paso entre categorías | 88 dp | `minWidth 80dp` + margen 8 dp |
| Banda de nombre del tile | ~19 % del alto (28 px sobre 149) | `canal_name_h = 22dp` |
| Barra superior | ~56 dp | `appbar_height = 56dp` |
| Radio del campo de búsqueda | pastilla (radio = alto/2) | `radius_pill = 25dp` |

La rejilla cuadra: `3 × 112.3 + 2 × 9 + 2 × 19 = 392.9` sobre 393 dp de ancho.

### Escritorio — 1366×641

Del árbol de semántica **y** de los bordes por píxel. Cuidado: los `rect` de la
semántica de Flutter no siempre son el dibujo —el campo de búsqueda, por ejemplo,
declara 1226 px de ancho en el árbol y luego la pastilla visible mide 340—, así
que lo que manda es el perfil de píxeles.

| Elemento | Medido |
|---|---|
| Raíl lateral | `0..72` |
| Barra superior | y `0..56` |
| Pastilla de búsqueda | `x 651..991` (340 px de ancho), muy lejos de los 1226 px que declara la semántica |
| Tira de categorías | y `58..143`, celdas de 88 × 85 **sin hueco**, paso 88 |
| Rejilla de canales | `x 87..1351`, **8 columnas** |
| Tile | **149 × 149**, cuadrado, con 8-9 px de hueco → celda de 158 |
| Banda de nombre | 28 px dentro del tile (19 %) |
| Borde del tile | 1 px negro `#000000` |
| Relleno del tile | negro al **44 %** |

**Corrección sobre la primera medición:** la v9 anotó aquí "celda 158 ancho × 187
alto = imagen 158 + banda 29", leyendo como ciertos los `rect` de los nodos
`flt-semantics` de etiqueta e imagen. Al medir el borde por píxel se ve que el
tile es **cuadrado de 149** y que la banda va **dentro**. De esa confusión salió
también el `canal_name_h = 22dp`, que resultó correcto por casualidad.

## 5. Inventario de componentes (capturas `recon-dramalive/out/`)

- **Raíl lateral** (escritorio, 72 px): 7 acciones de ícono; el activo es un
  cuadro teal claro `#273D42`.
- **Barra superior**: hamburguesa, título, y a la derecha descargar / buscar /
  refrescar.
- **Tira de categorías**: un botón circular de cabeza + cuadros de logo con el
  rótulo debajo. La seleccionada es un bloque azul lleno; las demás, sin fondo.
- **Rejilla de canales**: tiles de vidrio con el logo centrado y el **nombre en
  una banda dentro del tile, pegada al borde inferior**.
- **Cajón**: panel casi negro, avatar grande, rótulos de sección en versalitas,
  filas de ícono + texto separadas por filetes de 1 px.
- **Pantalla de acceso** (a la que lleva el raíl): tema **claro** con botón
  morado `#6A4C93`. Está **fuera del alcance** de esta réplica: no es la piel
  del catálogo, es el formulario de login, y en la app no existe esa pantalla.
- En la captura de escritorio aparece un **tile con un anuncio** encima (`Ad`):
  es publicidad del sitio, no diseño.

## 6. Qué cambió en la app (v9)

| Antes (v8) | Ahora (v9) | Por qué |
|---|---|---|
| Fondo negro neutro `#0D0F14` | Degradado teal + confeti (`bg_wallpaper`) | El sitio no tiene ni un píxel negro neutro: todo es teal |
| Superficies `#14171E` / `#1A1E27` / `#232837` | `#024C5C` / `#023945` / `#03505F` | Medidas en el sitio |
| Acento cian `#42D1FF` usado como **anillo** | Azul `#2196F3` usado como **relleno** | El chip activo del sitio es un bloque azul lleno |
| Txet sobre acento en tinta oscura `#06121B` | Blanco, como el sitio | El sitio pone el rótulo en blanco sobre el azul |
| Cabeceras opacas `bg_header_cine` a negro | Veladuras translúcidas sobre el fondo | En el sitio se ve el wallpaper a través |
| Campo de búsqueda 46 dp, radio 10 dp | 50 dp, **pastilla** | Medido: 52 px de alto en una barra de 56, totalmente redondo |
| Tiles de superficie opaca `#14171E` | Vidrio `#B3023A44` sobre el wallpaper | El sitio deja pasar el confeti |
| Borde del tile: filete azulado | Negro `#CC000000` | El perfil de píxeles del borde da `#000202` |
| Banda de nombre: degradado ya oscuro | De transparente a oscuro | Es lo que se ve: el nombre flota sobre el logo |
| Cajón `#024C5C` | Casi negro `#F2000E12` | Medido `#000B0D` |
| Tira de tópicos: fondo de superficie + anillo | Sin fondo; el activo con relleno azul | Igual que las categorías del sitio |

### Decisiones de contraste (donde me aparté del sitio a propósito)

El sitio usa `#2196F3` con texto blanco: **3.1:1**. Eso pasa para un chip (es un
control, el mínimo es 3:1) pero **no** para el texto de un botón. Así que:

| Token | Valor | Uso | Contraste |
|---|---|---|---|
| `pelis_primary` | `#2196F3` | Relleno de chips y categorías activas | 3.1:1 con blanco — **igual que el sitio** |
| `pelis_primary_aa` | `#1565C0` | Relleno del CTA con texto blanco | **5.7:1** |
| `pelis_accent` | `#64B5F6` | Texto e íconos de acento sobre teal | 4.3:1 sobre `#024C5C`, 6.1:1 sobre `#012A33` |
| `pelis_ink` | `#FFFFFF` | Texto sobre relleno de acento | como el sitio |

`#2196F3` **no** sirve como color de texto sobre la cabecera teal: da 2.6:1. Por
eso el acento de texto es un escalón más claro de la misma rama Material.

## 7. Segunda pasada: la geometría que faltaba (v9.1)

La v9 cambió la piel (color, fondos, estados) pero dejó la **geometría** con los
números de la v8, que venían de otra app. Volviendo a medir el sitio —esta vez
borde por borde sobre las capturas, y no solo la moda de color— aparecieron cinco
diferencias que se ven a simple vista:

| Qué | v9 | Medido en el sitio | v9.1 |
|---|---|---|---|
| **Columnas de la rejilla** | `anchoDp / 150` → **2** en un teléfono | paso de **121 dp** (tile 112.3 + hueco 9) sobre 393 dp → **3** | `round(anchoDp / 121)` |
| **Hueco entre tiles** | 8 dp de margen *por tile* = **16 dp** de hueco | **9 dp** | 4.5 dp de margen |
| **Relleno del tile** | teal `#B3023A44` | **negro al 44 %** | `#70000000` |
| **Cuadro de categoría** | fondo solo cuando activo | cuadro oscuro **siempre**; el activo **crece** a 76.3 × 52.7 dp | `bg_canal_tab` con `<inset>` negativo |
| **Cabecera** | barra + título/conteo/badge + buscador a todo lo ancho | **una sola fila** de 56 px | una fila de 56 dp |

### Las tres medidas que cambian el aspecto

**1. La rejilla pasa de 2 a 3 columnas.** Es la diferencia más grande: la v8 pedía
150 dp por columna (una decisión explícita de "losas más grandes que el sitio"),
así que en un teléfono de 360-411 dp salían 2 columnas con tiles al doble de
tamaño. El paso medido es de 121 dp.

**2. El hueco.** El margen lo pone *cada* tile, así que un margen de 8 dp produce
un hueco de 16 dp entre vecinos. Los 9 dp medidos piden un margen de 4.5 dp.

**3. El tile no está teñido: está oscurecido.** Con dos muestras contiguas a la
misma altura —tile en x 95..135, hueco en x 241..248— sale wallpaper `#037285` y
tile `#01404A`. Resolviendo `canal = fondo·(1−a)` da **a = 0.44** en G y en B: es
negro al 44 %, y por eso el tile hereda el color de lo que tiene detrás. La v9 lo
pintaba de teal al 70 %, que se ve plano y no responde al fondo.

### El cuadro de categoría, que es el detalle que más se nota

Medido a 393×851 dp (cajas por color, no a ojo):

```
cuadro normal (Shahid)   47.7 × 47.7 dp   en (158.0, 66.0)
bloque activo (Sports)   76.3 × 52.7 dp   en (230.0, 62.0)
rótulo "Sports"          y 117.7..126.7   ->  DEBAJO del bloque, fuera
```

Dos cosas que esto corrige:

- El fondo lo lleva **el cuadro del icono**, no la celda entera: el rótulo va
  debajo, sobre el fondo de la app. En la v9 el azul envolvía icono *y* rótulo.
- El activo **crece**, sobre todo a lo ancho (48 → 76 dp). Se reproduce con un
  `<inset>` de valores **negativos** en el selector, que es legal en Android y
  expande el drawable por fuera de los límites de la vista.

### La cabecera

En el sitio la barra es *una fila*: nombre + buscador + iconos, 56 px. La app
apilaba título, píldora de conteo, insignia EN VIVO y una fila aparte con el
buscador a todo lo ancho: unos 150 dp de pantalla donde el sitio gasta 56. Ahora
es una fila de 56 dp con el rótulo corto, el conteo como texto suelto junto al
título, el buscador con peso y los dos iconos.

Se pierden la insignia **EN VIVO** y la píldora del conteo (decisión del usuario,
no medida); `bg_pelis_badge_live` y `@string/canales_en_vivo` quedan sin uso por
si vuelven.

### Cómo organiza el sitio los canales

La tira de web.dramalive.cc no es decorativa: **es el índice del catálogo**, y su
orden es una decisión de producto. Medido en la tira (x creciente, 46 categorías
visibles y sigue):

```
[◎ Todos]  Favourites  Most watched  Peru  Live Matches        <- fijos
           Alwan  Shahid  Sports  Arabic Entertainment         <- grupos del
           News  Kids  Documentary  Religious  Movies  Music      playlist,
           ART  OSN  NETFLIX  MBC  Rotana  Cooking  Weyyak        de más canales
           Bein Entertainment  Bein Sport  unsorted  Relax        a menos
           Science  Anime  Roya  Live Twitch
           Syria  Lebanon  Saudi Arabia  Egypt  United Arab     <- PAÍSES, al
           Emirates  Jordan  Qatar  Iraq  Kuwait  Oman  Yemen      final y en
           Bahrain  Palestine  Sudan  Tunisia  Morocco  Algeria    orden
           Libya  ...                                              alfabético
```

Contra eso, la app hacía dos cosas distintas:

| | Sitio | App antes | App ahora |
|---|---|---|---|
| Cuántas categorías | **todas** | **12** (`MAX_TABS`), las 11 más pobladas | todas |
| Orden de los grupos | de más canales a menos | igual | igual (ya coincidía) |
| Países | **bloque al final**, alfabético | mezclados con los géneros, por conteo | bloque al final, alfabético |
| Canales sin `group-title` | categoría **"unsorted"** | `primerGrupo()` devolvía `""` y **no generaban pestaña**: solo se veían bajo "Todos" | cajón **"Sin clasificar"** |

La v8 recortaba a 12 con el argumento de que "una tira de 60 pestañas no es
usable". El sitio demuestra lo contrario: 46 categorías y un scroll horizontal.
El tope se quitó y los canales sin grupo dejaron de desaparecer de la
organización.

Para separar los países no hizo falta una tabla escrita a mano: los nombres salen
de `Locale.getISOCountries()` + `getDisplayCountry()` (~250 entradas, en inglés y
en español), que es lo que titula los grupos de los índices públicos. Así
`gruposPrincipales()` reparte las categorías en dos bloques y ordena cada uno a
su manera — grupos por conteo, países por nombre.

Lo que **no** se replicó, porque no es organización sino producto: `Favourites` y
`Most watched` necesitan persistencia y pantalla propias, y `Live Matches` ya
existe en la app como la Agenda deportiva. Tampoco se fija el país del
dispositivo en un hueco delante (el "Peru" de la captura): allí está porque el
sitio geolocaliza al usuario, y en la app eso dejaría un "Spain" fijo delante de
una lista peruana según dónde esté el teléfono.

## 8. El APK de Drama Live (v9.2)

Hasta aquí todo salió de **medir píxeles** de la web. El 16/10 apareció un
`dramalive.apk` en la raíz del proyecto y eso cambia la fuente de verdad: un APK
se puede **leer**, no hay que estimarlo.

### Qué es ese APK (y qué no es)

```
package      com.sneig.livedrama
versionName  13.0.3     <- la misma versión que el sitio (flutter_bootstrap.js?v=13.0.3.241)
minSdk 16, targetSdk 34, 8 ficheros classes.dex, 672 layouts
```

En el manifiesto hay **decenas de `activity-alias`**: `com.app.dramalivetvplayer`,
`net.dramalive.tvplayer`, `dramalive2022.app`, `app2022.livedrama11`,
`com.semprotech.dramalive2`… y recursos `m3uplayer_*`. Es un **reproductor IPTV
white-label** con el tema de Drama Live puesto encima, no el mismo código que la
web Flutter. Comparte versión, nombre y lenguaje de diseño; no comparte código.

### Cómo se lee un APK sin herramientas de pago

`aapt2` (el del SDK de Android) decodifica los XML compilados:

```powershell
aapt2 dump resources dramalive.apk            > recursos.txt
aapt2 dump xmltree --file res/_r.xml dramalive.apk     # un layout concreto
```

El problema es que las referencias salen como IDs crudos
(`android:background=@0x7f06009c`). Para eso está
`recon-dramalive/19-resolver-apk.py`, que cruza el layout con la tabla de
recursos y lo imprime como `@color/colorToolbar (#808e888f)`; y
`recon-dramalive/20-resumen-layout.py`, que deja solo lo que define la
organización (tamaños, ratios, márgenes, tamaños de letra) en vez de los 200
atributos de cada vista.

> **Sobre REA.** Se probó `npx rea-agents@latest` (v6.5.0, funciona). Su ruta de
> APK Android necesita un JAR de `jadx-headless-mcp` que hay que aportar, y la
> nativa, Hopper/Ghidra/IDA: `rea doctor` los da todos por ausentes. Para
> **recursos** no hace falta: `aapt2` da lo mismo. Para **código** (cómo decide
> la app qué canales muestra) sí haría falta JADX, que es lo que queda pendiente.

### Lo que dice, y lo que cambió

| | `web.dramalive.cc` (medido a píxel) | `dramalive.apk` (leído) |
|---|---|---|
| **Ficha de categoría** | cuadro de ~48 dp con el rótulo **debajo**, fuera | `item_live_topic`: **80×80 dp**, margen **4 dp** (paso 88), icono redondeado **42 dp** (esquinas 8 dp), rótulo **dentro**, blanco, autoSize **12–20 sp** |
| Categoría activa | azul plano | degradado **`#1fa2ff → #376aa1`** a 180°, borde 1 dp **`#cecece`**, esquinas **4 dp** |
| Categoría en reposo | sin fondo | **sin fondo** (solo `?selectableItemBackground`) |
| **Ficha de canal** | tile **cuadrado** con el nombre dentro, en rejilla | `item_live_list`: **FILA** de `match_parent`, logo **100×100 dp** a la izquierda, nombre al lado, autoSize **14–20 sp** |
| Tiles cuadrados | son la rejilla de canales | `item_grid` **140×180 dp** (logo 110×110) es para **shows/películas**, no para canales |

Dos correcciones que salieron de aquí:

1. **El rótulo de categoría iba fuera y ahora va dentro.** En la web está debajo
   del cuadro; en el APK está dentro de los 80×80, y el cuadro no lleva fondo
   oscuro en reposo (yo se lo había puesto).
2. **La rejilla de canales pasa a ser una lista de filas.** Es la diferencia
   grande. El cliente Android de Drama Live usa filas, y además resuelve un
   problema real de la app: los nombres son bilingües y largos
   (`KTV Sport | الكويت الرياضية`) y en un tile de 112 dp se recortaban. Con la
   fila entran completos y el logo pasa de 112 a 100 dp.

`columnasPara()` y su test desaparecieron con la rejilla; lo que queda de la
organización (categorías, orden, países) se prueba en `M3uParserTest`.

De paso, con logos de 100 dp en vez de tiles de 112-166, se corrigieron dos
tamaños que quedaban sobredimensionados: `Logos.ANCHO_TILE` 220 → **300** (100 dp
a 3x) y `Logos.AVATAR_PX` 440 → **320**. El avatar de 440 px por fila era el doble
de lo necesario en una lista larga.

## 9. La pasada en un móvil de verdad (v9.3)

Hasta aquí todo era estático: build, tokens dentro del APK, tests. El 17/10 se
conectó un **Galaxy A07 (SM-A075M, Android 16, 720×1600, densidad 300)** por adb
Wi-Fi, y eso destapó tres cosas que ningún test podía ver.

### El teléfono tenía un build viejo

La app instalada **no contenía ni uno** de los tokens nuevos
(`pelis_wall_mid`, `pelis_tile_bg`, `pelis_topic_start`, `canal_logo_row`): era de
antes de la réplica. Por eso la primera captura enseñaba el diseño anterior
—fondo oscuro y banda de título clara— y no el teal.

De paso apareció que `app/build/outputs/apk` estaba vacío y `.gradle` con fecha
posterior a la última compilación: alguien compiló desde Android Studio y no dejó
APK. Se recompiló, se instaló y ahí sí apareció la piel nueva.

### El bokeh era una mancha, no un desenfoque

La captura de la pantalla de Canales enseñó el fallo más grande de toda la
réplica. El generador de `bg_confetti` dibujaba el bokeh como **anillos
concéntricos de color plano** (para no depender de gradientes dentro de un
`VectorDrawable`). En pantalla eso no se lee como desenfoque: se lee como **discos
planos gigantes** de amarillo, verde y rojo, y además se ven a través de las filas
translúcidas.

Estaba mal razonado desde el principio: quité los gradientes del vector por
robustez y los sustituí por algo que se ve peor. La solución es la contraria:

- `bg_confetti` se queda **solo con el confeti menudo** (54 formas, radio máximo
  2.78 dp).
- Los halos pasan a `bg_wallpaper` como `<gradient android:type="radial">` dentro
  de `<shape>`, que sí tiene borde suave, con alfa 8–12 %.

Cuatro halos (ámbar arriba-izquierda, verde arriba-derecha, verde abajo-derecha,
rosa abajo-izquierda) dan la variación de color sin manchas.

### Los rótulos de categoría no cabían

Los `group-title` de los índices públicos son técnicos: `live_matches`,
`arabic_sports`, `ar_2`. En el cuadro de 80 dp se veían **"live_matc"** y
**"arabic_sp"**, cortados a mitad de palabra porque no tienen espacios y no podían
partirse en dos líneas.

Dos arreglos:

1. `rotuloDeTopico()` cambia `_` y `-` por espacios y pone mayúscula:
   `live_matches` → `Live matches`. La **clave** de la categoría no cambia, solo
   el texto que se pinta. Es pura y tiene test.
2. El rótulo pasa a `singleLine` + elipsis, como el APK (`singleLine=true` en
   `item_live_topic`), con autoSize 10–20 sp. Con 80 dp y nombres largos, a 12 sp
   ni una línea cabía.

Todavía se corta con elipsis ("Live matc…"). Es la tensión real entre un cuadro de
80 dp y nombres que en el sitio son cortos ("News", "Kids"). Se resolvería
ensanchando el cuadro, pero entonces deja de ser el del APK.

### La corrección que trajeron las capturas del cliente real

Con las capturas del **Drama Live real** sobre la mesa (`cur-00.png` de Kids,
`nav-rt.png` de Most watched, `sec-tv.png`, `sec-series.png`), quedó claro que la
v9.2 se había equivocado en las dos cosas que cambió:

| | Lo que leí del APK (v9.2) | Lo que pintan las capturas del real | Ahora |
|---|---|---|---|
| Ficha de canal | fila con logo de 100 dp + nombre al lado | **rejilla de 3 columnas** de tiles cuadrados con el nombre **dentro**, en la banda inferior | rejilla |
| Ficha de categoría | cuadro de 80×80 con icono y rótulo **dentro** | cuadro de icono con el rótulo **debajo**, fuera; el bloque azul envuelve **solo el icono** | icono + rótulo debajo |

`item_live_list` existe en el APK, sí, pero **no es la pantalla que la app pinta**:
ese build no es el mismo que el de las capturas. Leer un layout de un APK no
prueba que ese layout se use; para eso hace falta el código (JADX) o ver el
cliente funcionando.

Se revirtió a la rejilla (`columnasPara` volvió con su test) y el bloque azul se
movió de la celda al icono, así el rótulo queda sobre el fondo, como en `nav-rt`.

### v9.6: UNA sola barra, como el cliente real

Comparando la captura del teléfono con las del Drama real (`nav-rt.png`,
`m-00.png`, `probe-search.png`) saltaba a la vista: Drama gasta **una barra**
(`☰ Título 🔍 ⟳`) y la app gastaba **dos** —la de la actividad con la marca
"BLABONGO" y debajo la del fragmento con título, buscador y dos iconos.

Lo que se hizo:

| Antes | Ahora |
|---|---|
| Barra: `☰ BLABONGO` | Barra: `☰ <sección>` (el nombre del menú lateral) |
| Cabecera del fragmento: `Canales [buscador] ⟳ ⋮` | Nada: sus acciones son ítems del menú (`res/menu/menu_canales.xml`) |
| Buscador fijo en la cabecera | `SearchView` con `collapseActionView`: icono, y al pulsarlo se despliega la píldora en la barra |

El despliegue es calcado a `probe-search.png`: sale una flecha atrás, la consulta
en línea sustituyendo al título, una ✕, y filtra según se escribe. Al plegarla se
limpia el filtro (si no, la rejilla queda filtrada sin que se vea por qué).

**Dos fallos que solo se ven en aparato:**

1. **El título volvía a "BLABONGO" al girar el teléfono.** La actividad se recrea,
   `loadFragment()` no corre (hay `savedInstanceState`) y la barra se quedaba con
   el `label` de la app. Se arregló guardando la sección en `onSaveInstanceState` y
   pintando el título también en `onCreate`.
2. **El icono de refrescar salía negro.** El `colorControlNormal` del tema es gris
   y los iconos del menú de la barra no heredan el overlay oscuro: `ic_refresh`
   tenía relleno `#FF000000` y se pintaba tal cual. Ahora es blanco de origen, como
   `ic_pelis_search` e `ic_playlist_add`.

### Lo que se comprobó de verdad
```
VRI[Main] Received frameCommittedCallback lastAttemptedDrawFrameNum=4 didProduceBuffer=true
VRI[Main] Relayout returned: old=(0,64,720,1600) new=(0,64,720,1600) relayoutAsync=false
```

Cero `FATAL`, cero `InflateException`, cero `Resources$NotFound` en dos arranques
completos: los recursos nuevos inflan y dibujan en un dispositivo real.

Colores muestreados sobre la captura, ya con la piel nueva:

| Zona | Muestreado |
|---|---|
| Barra de herramientas | `#052F35` |
| Fondo entre filas | `#159DA6` |
| Relleno de fila (negro 44 % sobre el fondo) | `#065962` |
| Tira de categorías | `#0F96A2` |

La barra de estado sale **negra** (`#000000`): Android 16 ya ignora
`android:statusBarColor` y la app no pide dibujo detrás de las barras. Queda
anotado como pendiente, no como rotura.

### Nota sobre capturar la pantalla

El primer `screencap` se hizo sin comprobar qué había en pantalla y salió la **app
de pagos del usuario**, con una referencia escrita. Se borró la captura en el acto
y se escribió `recon-dramalive/21-captura-segura.ps1`, que lee `mCurrentFocus` y
**aborta si la ventana con el foco no es `dza.fut.paipo`**. Por defecto ni siquiera
lanza la app: solo captura si el usuario ya la tiene delante (`-Lanzar` para
traerla, opt-in).

## 10. El fallo que se coló (y la guarda que lo impide repetir)

La primera entrega de esta piel **no abría**: la app se cerraba antes de pintar
nada. La causa estaba en el confeti generado.

El generador emitía los círculos del bokeh así:

```
a78,0 0 1 156,0
```

Un arco necesita **siete** parámetros — `rx ry rot large-arc-flag sweep-flag
dx dy` — y ahí solo había seis. El parser lee `rx=78`, `ry=0`, `rot=0`,
`large-arc-flag=1`, y luego el "sweep-flag" se come el `156`; cuando toca leer
el flag del segundo arco, se encuentra un `-`. `android.util.PathParser` espera
un `0` o un `1` literal ahí.

Lo importante es **por qué no lo cazó nada**:

- `aapt2` **no parsea** `android:pathData`: la guarda como cadena y la compila.
  El build pasa en verde con un path inválido dentro.
- Lint tampoco la mira.
- Y el drawable cuelga de `bg_window_cinematic` → `bg_wallpaper` →
  `bg_confetti`, que es el **`android:windowBackground` del tema**. Ese drawable
  se infla para la ventana *antes* de que la activity pinte nada, así que el
  fallo no daba una pantalla en rojo: mataba el proceso en el arranque.

Dos cosas se hicieron al respecto:

1. **El generador ya no usa gradientes** (`<aapt:attr>` con `<gradient>` dentro
   de un `<path>`) ni arcos mal formados: el bokeh son círculos concéntricos de
   alfa decreciente, todo `<path>` con color plano. Menos superficie de fallo y
   sin depender del soporte de gradientes en `VectorDrawable`.
2. **`RecursosLayoutTest` valida el `pathData` de todos los vectores.** Ese test
   ya existía justo para esta clase de bug ("el `InflateException` que no ve ni
   el build ni lint"), así que la comprobación vive donde tenía que vivir: corre
   sin dispositivo, con `./gradlew :app:testDebugUnitTest`.

   El mismo parser está en Python para poder usarlo desde fuera de Gradle:

   ```powershell
   python recon-dramalive/10-validar-vectores.py            # los 77 vectores
   ```

Regla que sale de aquí: **cualquier `pathData` generado por script se valida
antes de compilar.** Escribir un vector a mano es tedioso; generarlo con un
bucle es cómodo, y es exactamente donde un parámetro de menos pasa inadvertido.

## 11. Lo que NO se pudo replicar (y por qué)

- **El difuminado real.** El fondo del sitio es un bitmap difuminado con bokeh.
  Un `<vector>` de Android no tiene desenfoque gaussiano; lo que se reprodujo es
  la *estructura* (gradiente + círculos suaves con gradiente radial + confeti),
  no el bitmap. Es la diferencia más visible si se ponen las dos pantallas al
  lado.
- **El desenfoque de fondo detrás de las superficies.** El sitio consigue el
  efecto "vidrio" con `backdrop-filter`, que Android no tiene para vistas
  normales (`RenderEffect` existe desde API 31 y exige cuidar el rendimiento, y
  el repo ya decidió no usarlo). El vidrio se aproxima con alfa, que es lo que
  hace `pelis_tile_bg`.
- **La pantalla de acceso**, por lo dicho arriba: es un formulario claro con
  acento morado, ajeno a la piel del catálogo.
- **El confeti animado.** En el sitio las partículas se mueven; aquí es una capa
  estática. Animar 54 formas en el fondo de ventana costaría más de lo que
  aporta.

## 12. Cómo verificar

```powershell
# El sandbox de DSH no deja escribir en ~/.gradle, así que Gradle se invoca
# directamente y con la caché de dependencias en modo lectura:
$env:GRADLE_USER_HOME   = "$PWD\.gradle-home"
$env:GRADLE_RO_DEP_CACHE = "$env:USERPROFILE\.gradle\caches"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.1.0-bin\*\gradle-9.1.0\bin\gradle.bat" `
    :app:assembleDebug --offline
```

Comprobación dura de los recursos sin emulador, sobre el APK ya construido:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\aapt2.exe" dump resources `
    app\build\outputs\apk\debug\app-debug.apk | Select-String 'pelis_wall_mid|pelis_primary|bg_confetti'
```

Y para volver a medir el sitio (con el Chrome de depuración en el 9222):

```powershell
node recon-dramalive\06-movil.mjs      # capturas móviles frescas
python recon-dramalive\07-tokens.py    # tokens por región
python recon-dramalive\08-geometria.py # radios y huecos
```
