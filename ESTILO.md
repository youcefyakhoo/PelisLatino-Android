# Estilo BLABONGO — sistema de diseño (v9)

> **v9 — la piel es la de `web.dramalive.cc`, medida, no interpretada.**
> La v8 se había inspirado en `dramalive.app` (negro + cian, con **anillo** de
> selección). El sitio que se pidió replicar es otro: `web.dramalive.cc` es una
> app **teal** — fondo degradado de color con bokeh y confeti, superficies de
> **vidrio** encima, y un único acento azul **`#2196F3`** que se usa como
> **relleno**, no como línea. El negro neutro desaparece: no hay un solo píxel
> `#0D0F14` en el sitio.
>
> Todo lo de esta versión salió de medir el sitio real por CDP: árbol de
> semántica de Flutter (236 nodos con geometría), moda de color por región y
> perfiles de borde para los radios. **El método, las tablas de medidas y las
> decisiones de contraste están en [`docs/ESTILO-DRAMALIVE-WEB.md`](docs/ESTILO-DRAMALIVE-WEB.md)**,
> y los volcados crudos en `recon-dramalive/out/`.
>
> Resumen del cambio: `pelis_bg` `#0D0F14` → `#012A33`; acento `#42D1FF` (anillo)
> → `#2196F3` (relleno); cabeceras opacas → veladuras sobre `bg_wallpaper`;
> tiles opacos → vidrio sobre negro al 44 %; campo de búsqueda → pastilla; cajón
> → casi negro. Lo que sigue de la v8 y sigue valiendo: una sola familia
> (Poppins), la escala en `dimens.xml`, y la regla de oro de no volver a
> escribir un `#RRGGBB` en un layout.
>
> **v9.1 — la segunda pasada fue de geometría, no de color.** La v9 cambió la
> piel pero dejó las medidas de la v8, que venían de otra app: la rejilla salía
> de **2 columnas** (el sitio usa 3), el hueco era de 16 dp (el sitio, 9), el tile
> iba teñido de teal en vez de oscurecido al 44 %, el cuadro de categoría no
> existía en reposo y la cabecera apilaba cuatro filas donde el sitio gasta una.
> Está todo en [`docs/ESTILO-DRAMALIVE-WEB.md`](docs/ESTILO-DRAMALIVE-WEB.md) §7,
> con las cajas medidas al píxel.

> **v8 — Canales en vivo con el lenguaje de DramaLive.** La base negra se queda, y se le
> importa el *vocabulario de componentes* de dramalive.app: tiles cuadrados de 3 columnas
> con el nombre en una banda inferior, **anillo** de acento (no relleno) en el elemento
> activo, tira de tópicos con ícono + rótulo, radios cortos (4–10 dp en vez de 14–16), una
> sola familia tipográfica (**Poppins**, empaquetada en `res/font/`) y acento cian
> `#3EA7FF → #42D1FF`. Además la rejilla por fin tiene detrás una **fuente configurable**
> (código FG o URL M3U/JSON), que es lo que esa app hace con su "CHANGE THE SOURCE".
> Sin `blur` ni fondo de póster difuminado detrás de la rejilla: se pidió explícitamente.

Piel **oscura cinematográfica** unificada para las tres secciones (Películas/Series,
Canales en vivo, Agenda deportiva), el reproductor, la intro y el drawer. Antes de esto
cada pantalla traía sus HEX a mano, así que el catálogo era negro `#242424`, la pantalla
de canales era **blanca** (`#f5f5f5` + `#333333`), la hoja de opciones de canal era blanca
con texto negro, y la cabecera del drawer era un bloque blanco con un logo de 150 dp.

## 1. De dónde sale cada cosa

| Archivo | Qué define |
|---|---|
| `values/colors.xml` | Paleta completa (superficies, acentos, texto, veladuras) |
| `values/dimens.xml` | Espaciado (múltiplos de 4), radios, tamaños de texto, alturas de control |
| `res/font/poppins.xml` | Familia tipográfica: agrupa los 4 pesos TTF empaquetados |
| `FuenteCanales.java` | De dónde se descarga la lista (prefs + HTTP + elección de parser) |
| `M3uParser.java` | M3U → `Canal` (con `tvg-logo`, `group-title` y servidores fusionados) |
| `values/styles.xml` | `TextAppearance.Blabongo.*` y `Widget.Blabongo.*` |
| `values/themes.xml` | Tema global (ya no hay `values-night/`, ver §4) |
| `color/*.xml` | Listas de estado: texto de chips, relleno del drawer, tinte de botones |
| `drawable/bg_*.xml` | Fondos, veladuras (scrim), pastillas, ripples |
| `animator/raise_on_press.xml` | Botón primario: sube y se contrae al pulsar |
| `animator/lift_on_press.xml` | Cards: se levantan al pulsar |

**Regla de oro:** un layout nuevo no vuelve a escribir un `#RRGGBB`. Se elige token y
estilo. Si falta un token, se añade a `values/` —no se hardcodea en la pantalla.

## 2. Paleta

```
pelis_bg          #0D0F14   fondo de pantalla (negro azulado, no negro puro)
pelis_bg_dark     #07080C   barra de estado, cabeceras, banda del banner
pelis_surface     #14171E   nivel 1: campos, chips, hoja
pelis_card        #1A1E27   nivel 2: cards y filas
pelis_card_high   #232837   nivel 3: pulsado / popups
pelis_border      #2E3444   filete de 1 dp
pelis_border_soft #222834   filete en cards (menos ruido visual)

pelis_primary     #3EA7FF   relleno (botón, badge); inicio del degradado de marca
pelis_accent      #42D1FF   fin del degradado, texto/íconos de acento, ANILLO de selección
pelis_primary_dark#1C7FD1   pressed
pelis_primary_deep#0B4C86   finales de degradado suaves
pelis_accent_soft #2A6E8C   bordes "casi activos"
pelis_cyan_tint   #1A42D1FF relleno del item seleccionado (10 % de opacidad)
pelis_cyan_ring   #8042D1FF filetes de acento
pelis_ink         #06121B   texto SOBRE cian (botón Play, badges)
pelis_red         #E23B45   solo cerrar/error
pelis_live        #FF4757   "EN VIVO", cerrar, error
pelis_sport       #22C55E   agenda deportiva
pelis_gold        #FFC93C   rating, nada más

pelis_text            #F3F5FA  (blanco roto: blanco puro sobre negro puro cansa)
pelis_text_secondary  #B7C0D0
pelis_muted           #8B94A6
pelis_text_disabled   #5B6373
```

**Contraste (regla que manda).** El cian `#42D1FF` sobre `#0D0F14` da ~11:1: vale como
línea, texto, anillo y seekbar. Pero **blanco sobre cian no**: `#FFFFFF` sobre `#42D1FF`
es 1.7:1 y sobre `#3EA7FF`, 2.4:1. Por eso todo lo que va *encima* de un relleno de marca
usa `pelis_ink` (casi negro): el botón Play, sus `iconTint` y las insignias HD/SERIE.
El rojo queda reservado a "EN VIVO", cerrar y error — como en la referencia.

## 3. Tipografía

**Una sola familia: Poppins** (400/500/600/700), la misma de la referencia. Es TTF real
empaquetado, no un XML de `font-family` apuntando a fuentes del sistema: se descargó de
`google/fonts` (OFL, ver `LICENSE-Poppins.txt`) y `res/font/poppins.xml` agrupa los cuatro
pesos en una familia, así que `@font/poppins` resuelve el peso por `textStyle`.

Detalle que se paga solo: **`android:fontFamily` declarado en el tema NO se hereda a los
`TextView`** (el tema no propaga ese atributo a esas vistas). Por eso el tema fija
`android:textViewStyle` y `android:editTextStyle` a `Widget.Blabongo.TextView/.EditText`,
que sí llevan la familia; y cada `TextAppearance` la repite, porque cuando un layout fija su
`textAppearance` ese gana y anula lo del `textViewStyle`.

(Nota de la que se aprendió: la idea de dejar la familia en un `@string/font_family_app` para
tener un único punto de cambio **no pasa lint** —un `<string>` no puede contener una referencia
`@font/`—, así que los estilos apuntan a `@font/poppins` y el único sitio que agrupa los pesos
es `res/font/poppins.xml`.)

Una escala, tres pesos. Cambias `dimens.xml` y se propaga a toda la app.

**Radios**: 4 dp (insignias), 8 dp (tiles, botones, pósters), 10 dp (cards, campos, chips),
16 dp (hojas). La referencia usa 3 y 8 px; antes de v8 esta app iba a 12–16 dp y con el
acento cian eso parecía "burbuja".

| Estilo | Uso | Tamaño |
|---|---|---|
| `TextAppearance.Blabongo.Display` | Título de pantalla | 24 sp bold |
| `.Title` | Título de detalle sobre el backdrop | 19 sp medium |
| `.Section` | Cabeceras de bloque (Sinopsis, Temporada, Resultados) | 15 sp medium |
| `.Overline` | Antetítulo (`CATÁLOGO`, tipo + géneros) | 11 sp, caps, tracking 0.14 |
| `.Body` | Sinopsis, subtítulos | 14 sp |
| `.Caption` | Metadatos, contadores | 12 sp |
| `.Badge` | HD / SERIE / hora del partido | 10 sp bold, tracking 0.07 |
| `.PosterTitle` | Título dentro de la card del póster | 14 sp medium, 2 líneas |

## 4. Tema

`Theme.MyApplication` (el nombre se mantiene: lo referencian 3 sitios del manifest)
sobre `Theme.MaterialComponents.NoActionBar`, y ahora sí define lo que el tema anterior
dejaba en manos del sistema: `colorSurface/onSurface`, `android:textColorPrimary/
Secondary/Hint`, `colorControl*`, `windowBackground` (viñeta azulada, adiós flash blanco
al abrir), `statusBarColor`/`navigationBarColor` a `pelis_bg_dark`,
`materialButtonStyle`, `toolbarStyle`, `cardViewStyle`, `alertDialogTheme`,
`materialAlertDialogTheme`, `android:alertDialogTheme` y `bottomSheetDialogTheme`.

**Se borró `values-night/themes.xml`**: era idéntico byte a byte al de `values/` (dos
copias del mismo tema que había que mantener sincronizadas). La app es oscura
deliberadamente en los dos modos; si algún día se quiere un tema claro, se crea
`values-night/` entonces, con valores distintos de verdad.

Diálogos y menús emergentes también pasaron por el tema: `ThemeOverlay.PelisLatino.AlertDialog`
ganó radios y estilos de botón, y se añadió `Theme.PelisLatino.FrameworkDialog`, porque
`PelisDetailActivity` abre un `android.app.AlertDialog` del sistema que hasta ahora salía
en modo claro sobre una pantalla negra.

No se activaron transiciones globales de activity a propósito: el repo tiene arreglos
delicados de PiP (`ARREGLOS-2026-09-28.md`) y `windowContentTransitions` interactúa con
`setSourceRectHint()`. Queda como mejora futura a probar en dispositivo.

## 5. Componentes

- **Chips de filtro** (`Widget.Blabongo.Chip`): fondo `bg_pelis_filter` → *selector* con
  `state_selected` / `state_pressed`. `PelisFragment.pintarFiltro()` ya no cambia fondo ni
  color a mano: solo llama `setSelected()`. Un solo estado, ripple y texto sincronizados
  (antes: dos fuentes de verdad y sin feedback al pulsar).
- **Búsqueda** (`Widget.Blabongo.SearchField/SearchInput/SearchAction`): campo de 50 dp,
  borde que se ilumina con `pelis_field_stroke`, botón de envío circular azul.
- **Cards de póster** (`Widget.Blabongo.Card.Poster`): `MaterialCardView` con radio 12 dp,
  filete de 1 dp y `stateListAnimator` de elevación al pulsar. Ojo: **`app:liftOnPress` no
  existe en Material 1.10.0** (AAPT lo rechaza), por eso se usa el animator propio.
- **Rating / año / insignias**: pastillas de vidrio (`bg_pelis_rating`, `bg_pelis_year`,
  `bg_pelis_badge`) sobre una veladura inferior (`bg_scrim_poster`) para que se lean sobre
  cualquier póster.
- **Detalle**: backdrop de 232 dp con doble scrim y título encima, póster de 112×168 dp
  *solapado* sobre el backdrop (margen negativo `-56 dp`, por eso existe el dimen
  `poster_detail_overlap_neg`: Android no acepta `-@dimen/...`), CTA `Widget.Blabongo.Button.Play`,
  bloques en cards y spinner de temporadas con layouts propios
  (`item_spinner_temporada*.xml`) en vez de los del sistema, que eran de tema claro.
- **Rejilla de canales** (`item_canal.xml` + `Widget.Blabongo.Tile`): `MaterialCardView` con
  `ConstraintLayout` y `app:layout_constraintDimensionRatio="1:1"` —tile cuadrado sin alturas
  fijas, así que 3 columnas en un teléfono y hasta 5 en tablet se ven iguales. El nombre va
  en `Widget.Blabongo.TileName` (banda `bg_tile_name_bar` pegada al borde inferior, dentro
  del recorte de la card). El anillo activo se hace con `strokeColor=@color/pelis_tile_stroke`
  + `strokeWidth=@dimen/stroke_ring`: **`strokeWidth` no acepta lista de estados**, por eso el
  grosor es fijo y solo cambia el color, y el adapter propaga el estado con
  `itemView.setActivated(...)` (no se guarda ningún "color anterior" a mano).
- **Tópicos** (`item_tab_canal.xml` + `Widget.Blabongo.Tab/.TabIcon/.TabLabel`): fila
  `selected` = anillo cian (`bg_canal_tab`) + ícono y rótulo en `color/pelis_tab_*`. Nacen de
  los `group-title` del M3U / `category` del JSON, con recuento; si hay un solo grupo la tira
  se oculta. Máximo 12 pestañas.
- **Hoja "Fuente"** (`dialog_fuente_canales.xml` + `FuenteCanales.java`): código FG, URL M3U/JSON
  y endpoint del resolvedor, en `SharedPreferences` (`fuente_canales`). Sin esto la app solo sabe
  leer la lista con la que venía. `M3uParser.java` acepta `tvg-logo` / `group-title` y
  **fusiona nombres repetidos en `options`**, que es el "Servers 1/2/3" de la referencia: el tile
  no se duplica y las calidades aparecen en la hoja de opciones.
- **Bottom sheet de canales**: superficie oscura con radio superior de 16 dp (`@dimen/radius_sheet`), *drag handle*,
  filas con ícono y chevron (`Widget.Blabongo.Row`).
- **Reproductor**: los controles pasan de negro plano `#B3000000` a degradado
  (`bg_player_controls`), botones de 44 dp con ripple circular, play/pausa como control
  principal circular, seekbar de 3 dp con degradado y thumb con halo. Se dejaron
  `use_controller="false"` y el `<include>` de siempre: **no** se puso
  `app:controller_layout_id` en el `PlayerView`, porque duplicaría los `@+id` de los
  controles dentro de la vista y `findViewById(R.id.controls)` podría devolver la copia
  oculta (controles muertos).

## 6. Cómo verificar (v9)

```bash
./gradlew :app:assembleDebug     # BUILD SUCCESSFUL · app-debug.apk ~24 MB
./gradlew :app:lintDebug         # 30 errores / 137 avisos — TODOS en Java, ninguno en res/
./gradlew :app:testDebugUnitTest # 158 tests, 0 fallos
```

Los números de la v8 para lint y tests **estaban viejos**: el repo creció
`DramaSwapDataSource` y `DramaAutoActivity`, que aportan 26 de esos 30 errores.
El desglose real:

| Dónde | Cuántos | Qué |
|---|---|---|
| `DramaSwapDataSource` | 21 | `UnsafeOptInUsageError` de media3 (archivo no tocado) |
| `DramaAutoActivity` | 4 + 1 | media3 + `NewApi` (`ByteArrayOutputStream#toString`, API 33) |
| `PlayerActivity` | 2 | `MissingSuperCall` en `onBackPressed` (intencional: el reproductor gestiona atrás) y `UnspecifiedRegisterReceiverFlag` del PiP |
| `PelisDetailActivity` | 2 | `UnsafeOptInUsageError` de media3 |

**Ninguno es de recursos.** `lintDebug` *aborta* el build al encontrar errores: es
configuración del repo, no un fallo de esta piel.

Comprobación dura del APK (sin emulador): `aapt2 dump resources` muestra los tokens
de la paleta (`pelis_wall_mid #ff02aec4`, `pelis_primary #ff2196f3`,
`pelis_tile_bg #b3023a44`) y `cardBackgroundColor=@color/pelis_tile_bg` en
`Widget.Blabongo.Tile`; `aapt2 dump xmltree` sobre `res/drawable/` confirma que
`bg_window_cinematic` es un `<shape>` y que los `pathData` del confeti llevan los
siete parámetros del arco.

### Guarda contra el fallo que no ve ni el build ni lint

`RecursosLayoutTest` valida el `android:pathData` de **todos** los vectores del
módulo. Existe porque `aapt2` guarda `pathData` como cadena y no la parsea: un
arco con un parámetro de menos compila en verde y revienta al inflar el drawable.
En la v9 eso pasó y, como el vector colgaba del `windowBackground`, la app **se
cerraba antes de abrir**. El mismo parser está en Python para poder lanzarlo
fuera de Gradle:

```powershell
python recon-dramalive/10-validar-vectores.py     # 77 vectores, 0 errores
```

Lo que **no** se puede verificar aquí: el render final. Hace falta una pasada en un móvil,
sobre todo (1) las 3-5 columnas de la rejilla con logos de proporciones raras, (2) la banda
de nombre sobre logos muy oscuros, (3) el solape del póster en el detalle, (4) el drawer con
`fitsSystemWindows`. Y (5) la hoja «Fuente» contra una lista real: el endpoint por defecto
`fgcode.store/link/api.php/get?code=` respondió **HTTP 500** (base de datos caída) el día de
esta entrega, así que el camino probado fue el del error, no el del éxito.

A esto se suma, en la v9, que el emulador de este equipo **no se puede crear** (pide
7,4 GB de partición de datos y quedaban 5 GB libres), así que la comprobación fue
estática: build + tokens en el APK + validación de vectores + la suite de tests.

## 7. Pendientes de diseño (no hechos a propósito)

- **Chips circulares de "Servers"** dentro del reproductor (en la referencia son ①②③ con anillo).
  Hoy los servidores se eligen en la hoja de opciones del tile; moverlos al reproductor toca
  `PlayerActivity` y su estado, y se prefiere en una pasada aparte.
- Sin `blur` de fondo detrás de la rejilla: se pidió explícitamente y además `RenderEffect`
  exige cuidar el rendimiento en gama baja.
- El tema del catálogo sigue siendo `Theme.MaterialComponents` (no Material 3): cambiarlo
  invalida los `ThemeOverlay` y el `alertDialogTheme` propios.

## 8. Carga automática de canales (`AutoCanales`)

Estilo no es solo color: la rejilla de DramaLive no existiría sin canales, y la app no
puede pedirle a nadie que pegue una URL para ver algo. Así que el camino por defecto es
automático y el manual (código FG / M3U) queda como override.

**Cadena de fuentes** (se recorre hasta que una dé ≥ 1 canal):

| # | Fuente | Qué es |
|---|---|---|
| 1 | `Deportes (lista de la app)` | el JSON con el que la app ya venía (214 canales) |
| 2 | `Deportes · iptv-org` | `iptv-org.github.io/iptv/categories/sports.m3u` (445 entradas) |
| 3 | `País · iptv-org <cc>` | `countries/<cc>.m3u` con `<cc>` = `Locale.getCountry()` del dispositivo (`pe` → 192 entradas, todas con logo) |
| 4 | `TDT · Free-TV` | índice explícito de televisión abierta |
| 5 | `Global · iptv-org` | `index.m3u`, **apagada por defecto** (varios MB); casilla en la hoja |

Tres detalles que valen la pena:
- **El ganador se recuerda** (`auto_ganador_url`) y pasa al primer puesto en el arranque
  siguiente: no se vuelve a recorrer toda la cadena en cada apertura.
- **Caché en disco** del cuerpo descargado en `cacheDir/fuentes/<hash>.txt` con TTL de 6 h
  (máx. 8 MB por lista). Con caché fresco no se toca la red; con caché *viejo* y red caída
  se pinta el viejo antes que una pantalla vacía. El botón ⟳ fuerza la red y salta el TTL.
- **Tope de 600 tiles**: una lista global de 10 000 entradas no es una rejilla usable.

**Lo que el parser aprendió del formato real** (`M3uParser`, cubierto por `M3uParserTest`
con 40 entradas reales commiteadas en `app/src/test/resources/pe-mini.m3u`):
- `36 TV Huacho (720p)` → el tile se llama **"36 TV Huacho"** y `720p` pasa a ser la
  **etiqueta del servidor**. Sin esto la rejilla se llena de nombres con calidad y del mismo
  canal repetido una vez por resolución.
- `group-title="Outdoor;Sports"` → la pestaña usa el **primer** grupo (el principal, en la
  semántica de iptv-org) y se descartan `Undefined`/`Other`; si no, los tópicos serían
  "Kids;Sports", "News;Sports", "Outdoor;Sports"…
- Varias líneas para el mismo nombre → una sola card con varias `options` (los *Servers* de
  la referencia).

**Honestidad sobre la reproducción.** iptv-org es un índice comunitario de señales publicadas
por el propio canal (retiran lo que le reportan); que la lista cargue no significa que el
stream abra. Muestreando 25 entradas al azar desde este entorno: el manifiesto respondió 200
en **20/25** (Perú) y **17/25** (deportes); el resto fueron 403 (geobloqueo/operador) o 404
(señal muerta). Desde una IP peruana la proporción de Perú sube; desde fuera, baja. La app
pinta el tile y deja que el WebView falle visibly al abrirlo —escondiendo el fallo no se
gana nada.

---

## TV (v9.7)

La app es la **misma** para teléfono y para Android TV. Lo que hubo que añadir, y por qué:

**1. Declararse.** En el manifiesto, `android.software.leanback` y
`android.hardware.touchscreen` van con `required="false"`: así el mismo APK entra en un móvil
y en un televisor, y la Play Store no lo restringe a uno. Y la actividad principal lleva un
segundo `intent-filter` con `LEANBACK_LAUNCHER` — sin ese category la app se instala en el
televisor pero **no aparece en la pantalla de inicio**, o sea que no hay forma de abrirla con
el mando. Comprobado en el APK:

```
leanback-launchable-activity: name='dza.fut.paipo.Main' banner='res/drawable-nodpi-v4/tv_banner.png'
uses-feature-not-required: name='android.hardware.touchscreen'
uses-feature-not-required: name='android.software.leanback'
```

**2. Banner.** El lanzador de TV pinta una tarjeta de 320×180. La genera
`tools/generar-banner-tv.py` (degradado teal medido + el anillo de "Todos" + Poppins), en
`drawable-nodpi` para que no se reescale. Un `VectorDrawable` no sirve: no lleva tipografía.

**3. El foco, que es lo que de verdad hace falta.** En un mando no hay dedo que señale. Sin
estados de foco la app es inusable en un televisor: no se ve dónde estás. Se añadió
`state_focused` a:

| Dónde | Cómo se ve |
|---|---|
| Tile de canal (`color/pelis_tile_stroke`) | el filete pasa a blanco de tinta |
| Categoría (`drawable/bg_canal_tab`) | anillo blanco de 2 dp y fondo cian tenue |
| Icono de la categoría (`color/pelis_tab_icon`) | el glifo se enciende a blanco |

La regla `state_selected` **y** `state_focused` va primero en el selector, para que la
categoría activa enfocada conserve el azul **y** muestre el anillo.

**4. El arranque.** En un televisor nadie tiene el foco al abrir la app, así que las flechas
del mando no hacen **nada** hasta que algo lo pide. `Ui.esTelevision()` (modo de interfaz
`UI_MODE_TYPE_TELEVISION`, con el feature `LEANBACK` de respaldo) se usa para pedirle el foco
a la rejilla una sola vez, cuando ya hay canales. `RecyclerView` lleva `android:focusable`.

**5. El tamaño se adapta solo.** Las medidas están en dp y la rejilla calcula las columnas por
ancho (`columnasPara`). Verificado simulando un televisor con `wm size 1920x1080` + `wm
density 320` (= 960×540 dp, lo que reporta un TV): la rejilla pasa de 3 a **6 columnas** y la
tira muestra 12 categorías de golpe. Nada más hubo que tocar.

**Verificado en el Galaxy A07** (que no es un TV): con `input keyevent DPAD_DOWN/RIGHT` el foco
entra en la tira (anillo blanco en "Estados") y baja a la rejilla (anillo blanco en un tile),
que es el recorrido real de un mando.

**Lo que un TV de verdad todavía pediría** y no está: un raíl de navegación lateral en vez del
drawer, foco explícito en los controles del reproductor, y márgenes de overscan en los
televisores que recortan los bordes.

