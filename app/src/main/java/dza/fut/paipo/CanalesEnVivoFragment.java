package dza.fut.paipo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;   // (ya estaba: vistas opcionales del layout)
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canales en vivo.
 *
 * La rejilla sigue el lenguaje de componentes de DramaLive: tiles cuadrados con el
 * nombre en una banda inferior, anillo de acento en el canal activo, tira de tópicos
 * con ícono + rótulo derivada de {@code Canal.category}, y una hoja "Fuente" para
 * cambiar de dónde se descarga la lista (código FG o URL M3U/JSON).
 */
public class CanalesEnVivoFragment extends Fragment {

    private static final String TAG = "CanalesEnVivo";

    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private CanalAdapter adapter;

    private TextView txtConteo;
    private View grupoVacio;
    private TextView txtVacio;
    private View bloqueTabs;
    private LinearLayout contenedorTabs;
    /** v9.6: el buscador vive en la barra (res/menu/menu_canales.xml), no en el layout. */
    private SearchView buscador;

    private final List<Canal> canales = new ArrayList<>();
    private final Map<String, Integer> conteoTopicos = new LinkedHashMap<>();
    /** Conteo por país ISO ("PE") sacado del tvg-id; Drama agrupa también por país. */
    private final Map<String, Integer> conteoPaises = new LinkedHashMap<>();
    /** Rótulos de la tira que son países: al filtrar se comparan con canal.pais. */
    private final Set<String> clavesPais = new HashSet<>();
    private String topicoActivo = "";
    private String busqueda = "";
    private boolean cargando = false;
    /** En TV el foco se pide una sola vez: si se pidiera en cada filtro, el mando
     *  saltaría de vuelta a la rejilla cada vez que se cambia de categoría. */
    private boolean focoPedido = false;
    private String mensajeEstado;
    private String origenActivo;
    private FuenteCanales fuente;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view;
        try {
            view = inflater.inflate(R.layout.fragment_canales_en_vivo, container, false);
        } catch (Throwable t) {
            // Mejor una pantalla con el error que un cierre silencioso: sin logcat no hay
            // forma de saber qué falló, y así el usuario puede contarlo.
            Log.e(TAG, "no se pudo inflar la pantalla de canales", t);
            return vistaDeFallo(t);
        }

        progressBar = view.findViewById(R.id.progressBarCanales);
        recyclerView = view.findViewById(R.id.recyclerViewCanales);
        // txtConteo se queda en null: v9.4 quitó el contador de la cabecera porque
        // se comía el ancho del buscador. Todo el código que lo usa ya va con
        // "if (txtConteo != null)", y el conteo sigue apareciendo en el mensaje
        // de "sin resultados".
        grupoVacio = view.findViewById(R.id.grupoVacioCanales);
        txtVacio = view.findViewById(R.id.txtVacioCanales);
        bloqueTabs = view.findViewById(R.id.bloqueTabsCanales);
        contenedorTabs = view.findViewById(R.id.contenedorTabsCanales);

        if (recyclerView != null) {
            // Toda la rejilla son tiles de una columna; sin anuncios que ocupen la
            // fila entera ya no hace falta SpanSizeLookup.
            recyclerView.setLayoutManager(
                    new GridLayoutManager(getContext(), calcularNumeroDeColumnas(requireContext())));
        }

        // v9.6: el buscador y las dos acciones se enganchan en la barra de la
        // actividad (onCreateOptionsMenu), no en esta pantalla.

        try {
            cargarCanales();
        } catch (Throwable t) {
            Log.e(TAG, "no se pudo lanzar la carga", t);
            return vistaDeFallo(t);
        }
        return view;
    }

    @Override
    public void onDestroyView() {
        // Las vistas viven menos que el fragmento: se sueltan para no filtrar el contexto.
        recyclerView = null;
        adapter = null;
        progressBar = null;
        txtConteo = null;
        grupoVacio = null;
        txtVacio = null;
        bloqueTabs = null;
        contenedorTabs = null;
        buscador = null;
        conteoPaises.clear();
        clavesPais.clear();
        super.onDestroyView();
    }

    // ─────────────────────────── carga ───────────────────────────

    private long vistoTsDrama;

    /**
     * Cuántas columnas de tiles según el ancho.
     *
     * <p>Las capturas del Drama Live real (`cur-00.png`, `nav-rt.png`) muestran
     * <b>3 columnas</b> en un teléfono. El paso útil por columna es de ~121 dp:
     * tile de 112 dp más un hueco de 9.
     */
    private int calcularNumeroDeColumnas(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return columnasPara(metrics.widthPixels / metrics.density);
    }

    /** Parte pura y testeable de {@link #calcularNumeroDeColumnas(Context)}. */
    static int columnasPara(float anchoDp) {
        int c = Math.round(anchoDp / 121f);
        if (c < 2) c = 2;
        if (c > 6) c = 6;
        return c;
    }

    /**
     * Dos cosas al volver a la pantalla: si DramaAutoActivity guardo una entrega
     * nueva (dramaTs cambio), refrescar la rejilla; y si la sesion guardada esta
     * vieja (>6 h), lanzar el auto-refresco headless — un WebView invisible que
     * reabre el sitio (que ya tiene el login en su DomStorage) y deja que el
     * captador haga el trabajo. Si el sitio no responde en plazo, la lista vieja
     * se conserva intacta y el aviso queda para la proxima vez que se abra el sheet.
     */
    @Override
    public void onResume() {
        super.onResume();
        final Context contexto = getContext();
        if (contexto == null) return;
        long t = FuenteCanales.dramaTs(contexto);
        if (t != vistoTsDrama) {
            vistoTsDrama = t;
            if (t > 0) cargarCanales(true);
            return;
        }
        // v3.2.34: LA CONEXION Y LA EXTRACCION SON AUTOMATICAS DE VERDAD. Con la cadena
        // resuelta en Java (glt/glbt/glasbi/glbr) ya no hace falta ni el WebView para el
        // primer arranque: sesion vacia -> auto-conectar (glt empotrado) + extraer() en un
        // hilo y la rejilla se llena sola. Si lo nativo no puede (edge blaqueado), cae al
        // captador headless de siempre. Con sesion vieja (>6 h) sigue el headless, que
        // trae las cookies reales del sitio.
        String ses = FuenteCanales.sesionDrama(contexto);
        boolean vieja = System.currentTimeMillis() - t > 6L * 3600_000L;
        if (ses.trim().isEmpty() || vieja) {
            long ahora = System.currentTimeMillis();
            FuenteCanales.guardarDramaTs(contexto, ahora);   // marca el intento: sin re-lazo hoy
            vistoTsDrama = ahora;
            if (ses.trim().isEmpty()) conectarYExtraerDrama(contexto);
            else startActivity(new android.content.Intent(contexto, DramaAutoActivity.class)
                    .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
        }
    }

    /** Hilo de auto-conexion: DramaSesion.conectarYSembrar -> (si hay plantillas)
     *  DramaSesion.extraer nativo -> guardarDramaExtra (fusion) -> rejilla sola. */
    private void conectarYExtraerDrama(final Context ctx) {
        final androidx.fragment.app.FragmentActivity act = getActivity();
        if (act == null) return;
        new Thread(() -> {
            String sem = DramaSesion.conectarYSembrar(ctx);
            DramaSesion.Resultado r = sem == null ? DramaSesion.Resultado.fallo("auto-conexion no cuajo")
                    : DramaSesion.extraer(sem, null);
            final DramaSesion.Resultado rTxt = r;
            final String semTxt = sem;
            if (act.isFinishing()) return;
            act.runOnUiThread(() -> {
                if (semTxt != null) FuenteCanales.guardarSesionDrama(ctx, semTxt);
                if (semTxt != null && rTxt != null && rTxt.canales > 0 && rTxt.m3u != null) {
                    FuenteCanales.guardarDramaExtra(ctx, rTxt.m3u);
                    FuenteCanales.guardarDramaTs(ctx, System.currentTimeMillis());
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion nativa: " + rTxt.canales + " canales");
                    android.widget.Toast.makeText(ctx, "Conectado a Drama: " + rTxt.canales + " canales (sin abrir nada)",
                            Toast.LENGTH_LONG).show();
                    cargarCanales(true);
                } else if (semTxt != null) {
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion: plantillas OK, extraccion floja ("
                            + (rTxt != null ? rTxt.error : "?") + ") — pruebo el captador");
                    startActivity(new android.content.Intent(ctx, DramaAutoActivity.class)
                            .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
                } else {
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion fallo (red/edge) — plan B captador");
                    startActivity(new android.content.Intent(ctx, DramaAutoActivity.class)
                            .putExtra(DramaAutoActivity.EXTRA_HEADLESS, true));
                }
            });
        }, "drama-auto-conexion").start();
    }

    private void cargarCanales() {
        cargarCanales(false);
    }

    /**
     * Hay dos caminos y el usuario no tiene que elegir ninguno:
     *
     *   - si guardó un código FG o una URL, se respeta lo suyo ( {@code FuenteCanales} );
     *   - si no, entra el modo automático ( {@link AutoCanales} ), que recorre una cadena
     *     de fuentes públicas hasta que alguna responde y recuerda cuál fue.
     *
     * `forzarRed` es lo que hace el botón ⟳: salta el caché de la lista. La carga del
     * arranque NO lo fuerza, que es exactamente para lo que sirve el caché: pintar la
     * rejilla aunque el proveedor esté caído o el teléfono sin red.
     */
    private void cargarCanales(boolean forzarRed) {
        final Context contexto = getContext();
        if (contexto == null) return;

        cargando = true;
        mostrarCarga(true);
        if (grupoVacio != null) grupoVacio.setVisibility(View.GONE);

        fuente = FuenteCanales.desdePreferencias(contexto);

        // Con el automático APAGADO se usa exactamente lo que el usuario escribió, sin cadena
        // ni caché. Con el automático encendido la fuente manual es la primera candidata de
        // AutoCanales (ahí arriba), lo que le añade caché y lista de respaldo.
        if (!AutoCanales.estaActivado(contexto)) {
            FuenteCanales.Oyente oyenteManual = new FuenteCanales.Oyente() {
                @Override
                public void onCanales(@NonNull List<Canal> nuevos) {
                    aplicarCanales(nuevos, recortar(fuente.comoTexto()));
                }

                @Override
                public void onError(@NonNull String mensaje, boolean esHttp, int codigoHttp) {
                    Log.e(TAG, "Fuente " + fuente.comoTexto() + ": " + mensaje);
                    errorCanales(mensaje);
                    captadorVisibleSiCaeLaFuente(mensaje);   // v3.2.19
                }
            };
            fuente.cargarAsync(oyenteManual);
            return;
        }

        AutoCanales.cargarAsync(contexto, forzarRed, new AutoCanales.Oyente() {
            @Override
            public void onCanales(@NonNull List<Canal> nuevos, @NonNull String etiqueta, boolean desdeCache) {
                if (!isAdded()) return;
                aplicarCanales(nuevos, recortar(etiqueta));
            }

            @Override
            public void onError(@NonNull String resumen) {
                if (!isAdded()) return;
                Log.w(TAG, "automático: " + resumen);
                errorCanales(getString(R.string.auto_fallo, resumen));
                captadorVisibleSiCaeLaFuente(resumen);       // v3.2.19
            }
        });
    }

    /** Canales extraidos de DramaLive (si los hay), pegados al final sin duplicar URLs. */
    @NonNull
    private List<Canal> conLaListaDrama(@NonNull List<Canal> base) {
        Context contexto = getContext();
        if (contexto == null) return base;
        String m3u = FuenteCanales.dramaExtraIdo(contexto);
        if (m3u.isEmpty()) return base;
        try {
            List<Canal> extras = ListaPegada.desdeTexto(m3u, FuenteCanales.dramaKey(contexto));
            if (extras == null || extras.isEmpty()) return base;
            java.util.HashSet<String> urls = new java.util.HashSet<>();
            for (Canal c : base) if (c != null && c.options != null)
                for (Canal.OpcionCanal o : c.options) if (o != null && o.url != null) urls.add(o.url);
            List<Canal> merged = new java.util.ArrayList<>(base);
            for (Canal c : extras) {
                if (c == null || c.options == null || c.options.isEmpty()) continue;
                Canal.OpcionCanal primera = c.options.get(0);
                if (primera != null && primera.url != null && urls.add(primera.url)) merged.add(c);
            }
            return merged;
        } catch (Throwable t) {
            Log.w(TAG, "fusion lista drama", t);
            return base;
        }
    }

    /** Punto único donde la rejilla acepta datos nuevos. */
    private void aplicarCanales(@NonNull List<Canal> nuevos, @Nullable String origen) {
        if (!isAdded()) return;
        try {
            aplicarCanalesSeguro(conLaListaDrama(nuevos), origen);
        } catch (Throwable t) {
            Log.e(TAG, "pintando canales", t);
            mostrarVacio(describir(t));
        }
    }

    private void aplicarCanalesSeguro(@NonNull List<Canal> nuevos, @Nullable String origen) {
        canales.clear();
        canales.addAll(nuevos);
        mensajeEstado = null;
        origenActivo = origen;
        cargando = false;
        contarTopicos();
        construirTabs();
        aplicarFiltro();
        mostrarCarga(false);
    }

    private boolean captadorVisibleLanzado;   // v3.2.19: un autolanzamiento por visita, sin loops

    /**
     * v3.2.19 («tampoco, regresa a la version donde se podia conectar y extraer»): cuando la
     * fuente de codigo FG muere por cosas del server ajeno (500 del store, timeouts, base de
     * datos caida) el usuario se quedaba SOLO con la banda roja. Antes de la era headless el
     * camino que SI funcionaba era el captador visible de DramaLive (el boton «Conectar
     * DramaLive (automatico)» — medido en el log 16:07 del usuario: ahi el sitio contesta
     * datos reales y la entrega fusiona, sin depender de fgcode). Ahora, si la carga falla
     * con aspecto de server-caido y no hay canales en pantalla, la app abre ESE captador
     * visible sola. Una vez por visita del fragmento; el resto es como siempre.
     */
    private void captadorVisibleSiCaeLaFuente(@NonNull String mensaje) {
        if (captadorVisibleLanzado || !isAdded()) return;
        if (!canales.isEmpty()) return;   // con rejilla llena no hay porque interrumpir
        String m = mensaje.toLowerCase(java.util.Locale.ROOT);
        boolean serverCaido = m.contains("no se pudo conectar") || m.contains("respondió 5")
                || m.contains("respondio 5") || m.contains("500") || m.contains("502")
                || m.contains("503") || m.contains("base de datos") || m.contains("timeout")
                || m.contains("intento");
        if (!serverCaido) return;
        captadorVisibleLanzado = true;
        android.util.Log.d("BLABONGO_DEBUG", "fuente FG caída: abro el captador visible automáticamente");
        Context c = getContext();
        if (c != null) {
            startActivity(new android.content.Intent(c, DramaAutoActivity.class));
        }
    }

    private void errorCanales(@NonNull String mensaje) {
        if (!isAdded()) return;
        canales.clear();
        mensajeEstado = mensaje;
        cargando = false;
        contarTopicos();
        construirTabs();
        aplicarFiltro();
        mostrarCarga(false);
    }

    /** Una URL entera en la píldora del contador no cabe; se acorta y ya. */
    @NonNull
    private static String recortar(@Nullable String texto) {
        if (texto == null) return "";
        return texto.length() <= 28 ? texto : texto.substring(0, 27) + "…";
    }

    // ─────────────────────────── tópicos ───────────────────────────

    /**
     * Tópico al que pertenece un canal.
     *
     * <p>web.dramalive.cc organiza TODOS los canales: los que no traen grupo caen
     * en una categoría llamada "unsorted", que se ve en la tira como una más.
     * Aquí {@code M3uParser.primerGrupo()} devuelve cadena vacía para
     * {@code group-title="Undefined"} (que es como iptv-org marca lo que no
     * clasificó), y una categoría vacía no genera pestaña: esos canales solo
     * aparecían bajo "Todos" y quedaban fuera de la organización.
     */
    @NonNull
    private String topicoDe(@Nullable Canal canal) {
        return topicoDe(canal, getString(R.string.canales_tab_sin_clasificar));
    }

    /** Parte pura de {@link #topicoDe(Canal)}: sin Android de por medio, testeable. */
    @NonNull
    static String topicoDe(@Nullable Canal canal, @NonNull String sinClasificar) {
        if (canal == null || TextUtils.isEmpty(canal.category)) return sinClasificar;
        String topico = canal.category.trim();
        return topico.isEmpty() ? sinClasificar : topico;
    }

    /**
     * Rótulo legible para una categoría.
     *
     * <p>Los {@code group-title} de los índices públicos son técnicos:
     * {@code live_matches}, {@code arabic_sports}, {@code ar_2}. En la captura del
     * Galaxy A07 se veían cortados ("live_matc", "arabic_sp") porque no tienen
     * espacios y no pueden partirse en dos líneas.
     *
     * <p>Cambiar el guion bajo por espacio los hace partirse en el cuadro de
     * 80 dp ("Live" / "matches") y además se leen. La CLAVE de la categoría no
     * cambia: solo el texto que se pinta.
     */
    @NonNull
    static String rotuloDeTopico(@Nullable String topico) {
        if (topico == null) return "";
        String limpio = topico.replace('_', ' ').replace('-', ' ').trim()
                .replaceAll("\\s+", " ");
        if (limpio.isEmpty()) return "";
        return limpio.substring(0, 1).toUpperCase(Locale.ROOT) + limpio.substring(1);
    }

    private void contarTopicos() {
        conteoTopicos.clear();
        conteoPaises.clear();
        for (Canal canal : canales) {
            if (canal == null) continue;
            String topico = topicoDe(canal);
            if (!topico.isEmpty()) {
                Integer actual = conteoTopicos.get(topico);
                conteoTopicos.put(topico, actual == null ? 1 : actual + 1);
            }
            if (!TextUtils.isEmpty(canal.pais)) {
                Integer p = conteoPaises.get(canal.pais);
                conteoPaises.put(canal.pais, p == null ? 1 : p + 1);
            }
        }
    }

    private void construirTabs() {
        if (contenedorTabs == null) return;
        contenedorTabs.removeAllViews();
        clavesPais.clear();
        // Con un solo tópico (y ningún país) la tira sobra.
        if (conteoTopicos.size() < 2 && conteoPaises.size() < 2) {
            bloqueTabs.setVisibility(View.GONE);
            return;
        }
        bloqueTabs.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        // "Todos" es el ANILLO blanco del Drama Live real (ddd.JPG: va el primero,
        // antes de Favourites). Antes llevaba un ojo en un bloque azul.
        anadirTab(inflater, "", getString(R.string.canales_tab_todo), canales.size(),
                R.drawable.ic_topic_todos);

        // 1. TU PAÍS PRIMERO.
        // Medido en el Drama Live real instalado en el teléfono (captura
        // drama-01.png): su tira es ◎ · Favoritos · Most watched · **Peru** ·
        // Live Matches · … Primero lo tuyo y solo después los tópicos. Antes este
        // país iba al frente del bloque de países, o sea AL FINAL de la tira.
        String miPais = Locale.getDefault().getCountry();
        String isoMio = miPais == null ? null : miPais.toUpperCase(Locale.ROOT);
        boolean paisPropioPintado = false;
        if (isoMio != null && conteoPaises.containsKey(isoMio)) {
            anadirTabPais(inflater, isoMio);
            paisPropioPintado = true;
        }

        // 2. GÉNEROS, de más canales a menos.
        for (Map.Entry<String, Integer> entrada
                : AutoCanales.gruposPrincipales(conteoTopicos, Integer.MAX_VALUE)) {
            String topico = entrada.getKey();
            anadirTab(inflater, topico, rotuloDeTopico(topico), entrada.getValue(), iconoPara(topico));
        }

        // 3. LOS DEMÁS PAÍSES, alfabéticos y al final. El país sale del tvg-id
        //    (ver M3uParser.paisDe), porque el group-title solo da género.
        List<String> isos = new ArrayList<>(conteoPaises.keySet());
        if (paisPropioPintado) isos.remove(isoMio);
        isos.sort((a, b) -> nombreDePais(a).compareToIgnoreCase(nombreDePais(b)));
        for (String iso : isos) anadirTabPais(inflater, iso);
    }

    /** Añade la pestaña de un país y la marca como tal para que el filtro la trate. */
    private void anadirTabPais(@NonNull LayoutInflater inflater, @NonNull String iso) {
        String nombre = nombreDePais(iso);
        if (nombre.isEmpty()) return;
        clavesPais.add(nombre);
        Integer cuantos = conteoPaises.get(iso);
        anadirTab(inflater, nombre, nombre, cuantos == null ? 0 : cuantos, R.drawable.ic_topic_grid);
    }

    /**
     * Código ISO → nombre del país en español: {@code "PE"} → {@code "Perú"}.
     * Si el locale no lo conoce devuelve el código tal cual.
     */
    @NonNull
    static String nombreDePais(@Nullable String iso) {
        if (iso == null || iso.length() != 2) return "";
        String codigo = iso.toUpperCase(Locale.ROOT);
        try {
            String nombre = new Locale("", codigo).getDisplayCountry(new Locale("es"));
            return (nombre == null || nombre.isEmpty()) ? codigo : nombre;
        } catch (Throwable t) {
            return codigo;
        }
    }

    /** El ícono no es decorativo: fútbol se marca con estrella, como en DramaLive. */
    private int iconoPara(String topico) {
        String t = normalizar(topico);
        if (t.contains("fut") || t.contains("deporte") || t.contains("sport")) {
            return R.drawable.ic_topic_star;
        }
        return R.drawable.ic_topic_grid;
    }

    private void anadirTab(LayoutInflater inflater, String clave, String rotulo,
                           int cantidad, int icono) {
        View tab = inflater.inflate(R.layout.item_tab_canal, contenedorTabs, false);
        TextView txt = tab.findViewById(R.id.txtTabCanal);
        ImageView img = tab.findViewById(R.id.imgTabCanal);
        txt.setText(rotulo);
        txt.setContentDescription(getString(R.string.canales_tab_desc, rotulo, cantidad));
        img.setImageResource(icono);
        tab.setTag(clave);
        tab.setSelected(clave.equals(topicoActivo));
        tab.setOnClickListener(v -> {
            topicoActivo = (String) v.getTag();
            pintarSeleccionTabs();
            aplicarFiltro();
        });
        contenedorTabs.addView(tab);
    }

    private void pintarSeleccionTabs() {
        if (contenedorTabs == null) return;
        for (int i = 0; i < contenedorTabs.getChildCount(); i++) {
            View tab = contenedorTabs.getChildAt(i);
            tab.setSelected(tab.getTag() != null && tab.getTag().equals(topicoActivo));
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v9.6: una sola barra, como el Drama Live real. El buscador y las dos
        // acciones son ítems del menú de la actividad; esta pantalla ya no pinta
        // su propia cabecera.
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_canales, menu);
        MenuItem itemBuscar = menu.findItem(R.id.accion_buscar_canales);
        if (itemBuscar != null && itemBuscar.getActionView() instanceof SearchView) {
            buscador = (SearchView) itemBuscar.getActionView();
            configurarBuscador(buscador);
        }
        MenuItem itemRefrescar = menu.findItem(R.id.accion_refrescar_canales);
        if (itemRefrescar != null) {
            itemRefrescar.setOnMenuItemClickListener(item -> {
                cargarCanales(true);
                return true;
            });
        }
        MenuItem itemFuente = menu.findItem(R.id.accion_fuente_canales);
        if (itemFuente != null) {
            itemFuente.setOnMenuItemClickListener(item -> {
                mostrarHojaFuente();
                return true;
            });
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    // ─────────────────────────── búsqueda ───────────────────────────

    /**
     * Buscador de la barra. Se ve como icono y al pulsarlo se despliega la
     * píldora: es el comportamiento de Drama (captura `probe-search`, donde el
     * título se recorta a "Sp…" y aparece el campo).
     */
    private void configurarBuscador(@NonNull SearchView sv) {
        sv.setQueryHint(getString(R.string.canales_buscar_hint));
        sv.setIconifiedByDefault(true);
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String q) {
                busqueda = normalizar(q);
                ocultarTeclado();
                aplicarFiltro();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String q) {
                busqueda = normalizar(q);
                aplicarFiltro();
                return true;
            }
        });
        sv.setOnCloseListener(() -> {
            // Al plegar la píldora se limpia el filtro: si no, la rejilla queda
            // filtrada y no se ve por qué.
            if (!busqueda.isEmpty()) {
                busqueda = "";
                aplicarFiltro();
            }
            return false;
        });
    }

    private void ocultarTeclado() {
        Context contexto = getContext();
        if (contexto == null || buscador == null) return;
        InputMethodManager inm = (InputMethodManager) contexto.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inm != null) inm.hideSoftInputFromWindow(buscador.getWindowToken(), 0);
    }

    // ─────────────────────────── filtrado ───────────────────────────

    private void aplicarFiltro() {
        try {
            aplicarFiltroInterno();
        } catch (Throwable t) {
            Log.e(TAG, "filtrando canales", t);
            mostrarVacio(describir(t));
        }
    }

    private void aplicarFiltroInterno() {
        List<Canal> visibles = new ArrayList<>();
        String topicoNormal = normalizar(topicoActivo);
        // Si el tópico activo es un país se compara con canal.pais; si no, con la
        // categoría. Drama deja que un canal esté a la vez en su género y en su país.
        boolean filtrandoPais = clavesPais.contains(topicoActivo);
        for (Canal canal : canales) {
            if (canal == null) continue;
            if (!topicoActivo.isEmpty()) {
                String valor = filtrandoPais ? nombreDePais(canal.pais) : topicoDe(canal);
                if (!topicoNormal.equals(normalizar(valor))) continue;
            }
            if (!busqueda.isEmpty() && !normalizar(canal.title).contains(busqueda)) continue;
            visibles.add(canal);
        }

        boolean hayFiltro = !busqueda.isEmpty() || !topicoActivo.isEmpty();
        // v9.4: sin publicidad. Antes se intercalaba un "ANUNCIO" cada 30 canales.
        List<Object> items = new ArrayList<>(visibles);

        if (adapter == null) {
            adapter = new CanalAdapter(items, requireContext());
            if (recyclerView != null) recyclerView.setAdapter(adapter);
        } else if (recyclerView != null && recyclerView.isComputingLayout()) {
            // Cambiar datos mientras se está midiendo la lista lanza
            // IllegalStateException: se pospone un frame.
            recyclerView.post(() -> {
                if (adapter != null) adapter.actualizarLista(items);
            });
        } else {
            adapter.actualizarLista(items);
        }

        if (txtConteo != null) {
            if (hayFiltro) {
                txtConteo.setText(getString(R.string.canales_conteo_filtrado, visibles.size(), canales.size()));
            } else if (TextUtils.isEmpty(origenActivo)) {
                txtConteo.setText(getString(R.string.canales_conteo, canales.size()));
            } else {
                txtConteo.setText(getString(R.string.auto_ok, origenActivo, canales.size()));
            }
        }

        pintarEstadoVacio(items.isEmpty());

        // v9.7 (TV): en un televisor nadie tiene el foco al arrancar, así que el
        // mando no hace NADA hasta que algo lo pide. Se le da a la rejilla una vez,
        // cuando ya hay canales que enfocar.
        if (!focoPedido && !items.isEmpty() && recyclerView != null
                && Ui.esTelevision(getContext())) {
            focoPedido = true;
            recyclerView.post(recyclerView::requestFocus);
        }
    }

    /** Pinta un mensaje en el hueco de la rejilla (sin tocar el adaptador). */
    private void mostrarVacio(@NonNull String mensaje) {
        if (grupoVacio == null) return;
        if (txtVacio != null) txtVacio.setText(mensaje);
        grupoVacio.setVisibility(View.VISIBLE);
    }

    /** Vista mínima, sin recursos del layout, para no desaparecer en silencio. */
    @NonNull
    private View vistaDeFallo(@NonNull Throwable error) {
        android.widget.ScrollView hoja = new android.widget.ScrollView(requireContext());
        TextView texto = new TextView(requireContext());
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        texto.setPadding(p, p, p, p);
        texto.setText(describir(error));
        // La pantalla de fallo se pinta con los TOKENS, no con literales: si el
        // tema cambia (v9 pasó de negro a teal), este cartel cambia con él.
        texto.setTextColor(ContextCompat.getColor(requireContext(), R.color.pelis_text));
        texto.setTextSize(12f);
        texto.setTextIsSelectable(true);
        hoja.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.pelis_bg));
        hoja.addView(texto);
        return hoja;
    }

    @NonNull
    private static String describir(@NonNull Throwable e) {
        StringBuilder sb = new StringBuilder("Canales no pudo abrirse.\n");
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        Throwable causa = e.getCause();
        if (causa != null) {
            sb.append("causa: ").append(causa.getClass().getName())
              .append(": ").append(causa.getMessage()).append("\n");
        }
        StackTraceElement[] traza = (causa != null ? causa : e).getStackTrace();
        int propias = 0;
        for (StackTraceElement el : traza) {
            String s = el.toString();
            if (s.startsWith("dza.fut") && propias < 5) {
                sb.append("  en ").append(s).append("\n");
                propias++;
            }
        }
        if (propias == 0) {
            for (int i = 0; i < Math.min(4, traza.length); i++) sb.append("  en ").append(traza[i]).append("\n");
        }
        sb.append("\nManda esta captura y se arregla en un paso.");
        return sb.toString();
    }

    /**
     * Un solo sitio decide si hay que decir algo: cargando nunca se interrumpe, un
     * fallo de fuente se explica, y un filtro sin resultados lo dice en su idioma.
     */
    private void pintarEstadoVacio(boolean nadaQueMostrar) {
        if (grupoVacio == null) return;
        if (cargando || !nadaQueMostrar) {
            grupoVacio.setVisibility(View.GONE);
            return;
        }
        String mensaje = mensajeEstado;
        if (mensaje == null) {
            mensaje = (busqueda.isEmpty() && topicoActivo.isEmpty())
                    ? getString(R.string.canales_vacio_mensaje)
                    : getString(R.string.canales_error_vacio);
        }
        if (txtVacio != null) txtVacio.setText(mensaje);
        grupoVacio.setVisibility(View.VISIBLE);
    }

    private void mostrarCarga(boolean mostrar) {
        if (progressBar != null) progressBar.setVisibility(mostrar ? View.VISIBLE : View.GONE);
        if (mostrar && grupoVacio != null) grupoVacio.setVisibility(View.GONE);
    }

    // ─────────────────────────── fuente ───────────────────────────

    /**
     * Hoja inferior para cambiar la fuente. No es un simple adorno: sin esto la app
     * solo sabe leer la lista con la que viene configurada. Los errores reales del
     * servidor (p. ej. un 500 del endpoint que resuelve el código) se muestran aquí
     * en texto, no se tragan en un Toast genérico.
     */
    @SuppressLint("InflateParams")   // la hoja aporta sus propios LayoutParams
    private void mostrarHojaFuente() {
        Context contexto = getContext();
        if (contexto == null) return;
        View hoja = LayoutInflater.from(contexto).inflate(R.layout.dialog_fuente_canales, null);
        EditText campoCodigo = hoja.findViewById(R.id.editCodigoFuente);
        EditText campoUrl = hoja.findViewById(R.id.editUrlFuente);
        EditText campoEndpoint = hoja.findViewById(R.id.editEndpointFuente);
        EditText campoCabeceras = hoja.findViewById(R.id.editEncabezados);
        EditText campoClaveDrama = hoja.findViewById(R.id.editClaveDrama);
        TextView estado = hoja.findViewById(R.id.txtEstadoFuente);
        MaterialButton guardar = hoja.findViewById(R.id.btnGuardarFuente);
        MaterialButton porDefecto = hoja.findViewById(R.id.btnFuentePorDefecto);

        FuenteCanales actual = FuenteCanales.desdePreferencias(contexto);
        campoCodigo.setText(actual.codigo);
        campoUrl.setText(actual.url);
        campoEndpoint.setText(actual.endpoint);
        campoCabeceras.setText(actual.cabeceras);
        campoClaveDrama.setText(actual.dramaKey);
        estado.setText(getString(R.string.fuente_estado,
                getString(R.string.canales_conteo, canales.size()), actual.comoTexto()));

        BottomSheetDialog dialog = new BottomSheetDialog(contexto);
        dialog.setContentView(hoja);

        // ── automático: un interruptor y el estado de la última fuente que funcionó ──
        SwitchCompat cambio = hoja.findViewById(R.id.cambioAutoCanales);
        TextView chipCorto = hoja.findViewById(R.id.chipAlcanceCorto);
        TextView chipLatam = hoja.findViewById(R.id.chipAlcanceLatam);
        TextView chipGlobal = hoja.findViewById(R.id.chipAlcanceGlobal);
        TextView estadoAuto = hoja.findViewById(R.id.txtAutoEstado);
        MaterialButton forzar = hoja.findViewById(R.id.btnAutoForzar);
        MaterialButton vaciar = hoja.findViewById(R.id.btnAutoVaciar);

        // ── panel Xtream: host + usuario + contraseña, el API que usa DramaLive ──
        EditText campoHost = hoja.findViewById(R.id.editXtreamHost);
        EditText campoUsuario = hoja.findViewById(R.id.editXtreamUsuario);
        EditText campoClave = hoja.findViewById(R.id.editXtreamClave);
        SwitchCompat cambioPanel = hoja.findViewById(R.id.cambioXtreamActivo);
        MaterialButton probarPanel = hoja.findViewById(R.id.btnXtreamGuardar);

        Xtream.Panel guardado = Xtream.desdePreferencias(contexto);
        campoHost.setText(guardado.base);
        campoUsuario.setText(guardado.usuario);
        campoClave.setText(guardado.clave);
        cambioPanel.setChecked(Xtream.estaActivo(contexto));

        // ── logos: el mismo reductor que usa el portal, a elección del usuario ──
        SwitchCompat cambioLogos = hoja.findViewById(R.id.cambioLogosReducidos);
        cambioLogos.setChecked(FuenteCanales.logosReducidos(contexto));
        cambioLogos.setOnCheckedChangeListener((v, marcado) -> {
            FuenteCanales.setLogosReducidos(contexto, marcado);
            CanalAdapter rejilla = adapter;
            if (rejilla != null) rejilla.setReducirLogos(marcado);
        });

        cambiarPanelAlVolar(cambioPanel, contexto, campoHost, campoUsuario, campoClave);
        probarPanel.setOnClickListener(v -> {
            Xtream.guardar(contexto, campoHost.getText().toString(), campoUsuario.getText().toString(),
                    campoClave.getText().toString(), true);
            if (!Xtream.desdePreferencias(contexto).completa()) {
                Toast.makeText(contexto, R.string.xtream_incompleto, Toast.LENGTH_LONG).show();
                return;
            }
            // El panel entra por la cadena automática: si estaba apagada, se enciende. La
            // preferencia se escribe a mano, sin mover el interruptor, para no disparar su
            // listener y acabar cargando dos veces.
            AutoCanales.setActivado(contexto, true);
            String etiqueta = Xtream.desdePreferencias(contexto).etiqueta();
            estado.setText(getString(R.string.xtream_cargando, etiqueta));
            guardar.setEnabled(false);
            Xtream.probarAsync(contexto,
                    (total, grupos) -> {
                        guardar.setEnabled(true);
                        estado.setText(getString(R.string.xtream_ok, etiqueta, total, grupos));
                        cargarCanales(true);
                    },
                    problema -> {
                        guardar.setEnabled(true);
                        estado.setText(getString(R.string.xtream_fallo, problema));
                    });
        });

        cambio.setChecked(AutoCanales.estaActivado(contexto));
        pintarAlcance(chipCorto, chipLatam, chipGlobal, AutoCanales.alcance(contexto));
        pintarEstadoAuto(estadoAuto, contexto);

        cambio.setOnCheckedChangeListener((v, marcado) -> {
            AutoCanales.setActivado(contexto, marcado);
            // Solo tiene sentido recargar si el usuario no dejó una lista propia.
            if (!FuenteCanales.desdePreferencias(contexto).esPersonalizada()) cargarCanales();
        });
        chipCorto.setOnClickListener(v ->
                elegirAlcance(contexto, AutoCanales.ALCANCE_CORTO, chipCorto, chipLatam, chipGlobal, estadoAuto));
        chipLatam.setOnClickListener(v ->
                elegirAlcance(contexto, AutoCanales.ALCANCE_LATAM, chipCorto, chipLatam, chipGlobal, estadoAuto));
        chipGlobal.setOnClickListener(v ->
                elegirAlcance(contexto, AutoCanales.ALCANCE_GLOBAL, chipCorto, chipLatam, chipGlobal, estadoAuto));
        forzar.setOnClickListener(v -> {
            dialog.dismiss();
            cargarCanales(true);
        });
        vaciar.setOnClickListener(v -> {
            AutoCanales.vaciarCache(contexto);
            Toast.makeText(contexto, R.string.auto_cache_vaciada, Toast.LENGTH_SHORT).show();
            pintarEstadoAuto(estadoAuto, contexto);
        });

        MaterialButton probar = hoja.findViewById(R.id.btnProbarFuente);
        probar.setOnClickListener(v -> {
            String codigo = campoCodigo.getText().toString().trim();
            String url = campoUrl.getText().toString().trim();
            String endpoint = campoEndpoint.getText().toString().trim();
            if (codigo.isEmpty() && url.isEmpty()) {
                estado.setText(R.string.fuente_error_vacio);
                return;
            }
            FuenteCanales.guardar(contexto, codigo, url, endpoint, campoCabeceras.getText().toString(),
                    campoClaveDrama.getText().toString());
            estado.setText(getString(R.string.fuente_prueba_cargando,
                    recortar(FuenteCanales.resolver(url, codigo, endpoint))));
            probar.setEnabled(false);
            FuenteCanales.probarAsync(contexto, pueba -> {
                probar.setEnabled(true);
                estado.setText(pueba.sirvio()
                        ? getString(R.string.fuente_prueba_ok, pueba.canales, pueba.detalle)
                        : getString(R.string.fuente_prueba_fallo, pueba.http, pueba.detalle));
            });
        });

        EditText campoPegada = hoja.findViewById(R.id.editListaPegada);
        MaterialButton usarPegada = hoja.findViewById(R.id.btnUsarPegada);
        MaterialButton quitarPegada = hoja.findViewById(R.id.btnQuitarPegada);
        campoPegada.setText(FuenteCanales.pegada(contexto));
        usarPegada.setOnClickListener(v -> {
            String texto = campoPegada.getText().toString();
            int encontrados = ListaPegada.contar(texto);
            if (encontrados == 0) {
                estado.setText(R.string.pegada_no_entendida);
                return;
            }
            FuenteCanales.guardarPegada(contexto, texto);
            estado.setText(getString(R.string.pegada_ok, encontrados));
            AutoCanales.setActivado(contexto, true);
            cargarCanales(true);
        });
        quitarPegada.setOnClickListener(v -> {
            FuenteCanales.guardarPegada(contexto, "");
            campoPegada.setText("");
            estado.setText(R.string.pegada_quitada);
            cargarCanales(true);
        });

        // --- Extraccion DramaLive con la sesion del navegador del usuario ---
        // El JSON de DramaExport.plantillas() se guarda tal cual para reintentar;
        // DramaSesion habla con el panel con el MISMO alambre que el bundle
        // (AES-128-CBC "43fc9b1a3f854049", b64(ct):b64(iv)) y devuelve un m3u que
        // se fusiona en la rejilla. Si la sesion caduco, el panel responde su
        // catch-all de 32 B y el boton lo dice tal cual en el estado.
        EditText campoSesionDrama = hoja.findViewById(R.id.editSesionDrama);
        MaterialButton extraerDrama = hoja.findViewById(R.id.btnExtraerDrama);
        MaterialButton quitarDrama = hoja.findViewById(R.id.btnQuitarDrama);

        // El boton AUTOMATICO: abre el sitio dentro de la app (DramaAutoActivity +
        // assets/drama_auto.js). El usuario entra su codigo UNA vez ahi; la
        // enumeracion, la plantilla y el guardado corren solos. Cero consola.
        MaterialButton autoDrama = hoja.findViewById(R.id.btnAutoDrama);
        if (autoDrama != null) {
            String ultimoAuto = FuenteCanales.dramaEstado(contexto);
            if (!ultimoAuto.isEmpty()) estado.setText(ultimoAuto);
            autoDrama.setOnClickListener(v -> {
                estado.setText(R.string.drama_auto_esperando);
                startActivity(new android.content.Intent(contexto, DramaAutoActivity.class));
            });
        }
        if (campoSesionDrama != null && extraerDrama != null) {
            campoSesionDrama.setText(FuenteCanales.sesionDrama(contexto));
            if (quitarDrama != null) quitarDrama.setOnClickListener(v -> {
                FuenteCanales.limpiarDramaExtra(contexto);
                estado.setText(R.string.drama_extra_quitado);
                cargarCanales(true);
            });
            extraerDrama.setOnClickListener(v -> {
                String sesion = campoSesionDrama.getText().toString().trim();
                if (sesion.isEmpty()) {
                    estado.setText(R.string.drama_sesion_vacia);
                    Toast.makeText(contexto, R.string.drama_sesion_vacia, Toast.LENGTH_SHORT).show();
                    return;
                }
                // "pegue algo y no paso nada" no puede existir: si lo pegado es un
                // codigo FG, un enlace de un canal o una M3U, se dice AHIA y con
                // Toast (el estado del sheet se ve facil que se queda detras).
                String diag = DramaSesion.diagnosticoEntrada(sesion);
                if (diag != null) {
                    int msg = "FG".equals(diag) ? R.string.drama_ses_fg
                            : "ENLACE".equals(diag) ? R.string.drama_ses_enlace
                            : "M3U".equals(diag) ? R.string.drama_ses_m3u : R.string.drama_ses_json;
                    estado.setText(msg);
                    Toast.makeText(contexto, msg, Toast.LENGTH_LONG).show();
                    return;
                }
                String errPlantilla = DramaSesion.errorPlantilla(sesion);
                if (errPlantilla != null) {
                    String m = getString(R.string.drama_extra_fallo, errPlantilla);
                    estado.setText(m);
                    Toast.makeText(contexto, m, Toast.LENGTH_LONG).show();
                    return;
                }
                FuenteCanales.guardarSesionDrama(contexto, sesion);
                extraerDrama.setEnabled(false);
                estado.setText(R.string.drama_extraer_cargando);
                new Thread(() -> {
                    DramaSesion.Resultado r;
                    try {
                        r = DramaSesion.extraer(sesion, null); // null = auto: glt tal cual -> topicos -> glbt (medido; el csv legado devolvia "no data")
                    } catch (Exception e) {
                        r = DramaSesion.Resultado.fallo("red: " + e);
                    }
                    final DramaSesion.Resultado rr = r;
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        extraerDrama.setEnabled(true);
                        if (rr.ok()) {
                            FuenteCanales.guardarDramaExtra(contexto, rr.m3u);
                            String ok = getString(R.string.drama_extra_ok, rr.canales);
                            estado.setText(ok);
                            Toast.makeText(contexto, ok, Toast.LENGTH_SHORT).show();
                            cargarCanales(true);
                        } else {
                            String mal = getString(R.string.drama_extra_fallo, rr.error);
                            estado.setText(mal);
                            Toast.makeText(contexto, mal, Toast.LENGTH_LONG).show();
                        }
                    });
                }).start();
            });
        }

        guardar.setOnClickListener(v -> {
            String codigo = campoCodigo.getText().toString().trim();
            String url = campoUrl.getText().toString().trim();
            String endpoint = campoEndpoint.getText().toString().trim();
            if (codigo.isEmpty() && url.isEmpty()) {
                estado.setText(R.string.fuente_error_vacio);
                return;
            }
            if (!url.isEmpty() && !url.startsWith("http") && !url.startsWith("dramalive://")) {
                estado.setText(R.string.fuente_uri_invalida);
                return;
            }
            String textoCabeceras = campoCabeceras.getText().toString();
            FuenteCanales.guardar(contexto, codigo, url, endpoint, textoCabeceras,
                    campoClaveDrama.getText().toString());
            // Que vea qué se entendió (y qué se tiró por restringida): si no, un 403
            // posterior no se puede diagnosticar.
            {
                Encabezados cab = Encabezados.fromTexto(textoCabeceras);
                StringBuilder msg = new StringBuilder(contexto.getString(R.string.fuente_guardado));
                if (!cab.estaVacio()) msg.append("  ·  ").append(cab.cuantas()).append(" cabecera(s): ").append(cab.resumen());
                if (!cab.descartadas().isEmpty()) {
                    String d = String.join(", ", cab.descartadas());
                    msg.append("  ·  ").append(contexto.getString(R.string.cabeceras_descartadas, d));
                }
                String texto = msg.toString();
                Toast.makeText(contexto, texto,
                        texto.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
            cargarCanales();
        });

        porDefecto.setOnClickListener(v -> {
            FuenteCanales.restablecer(contexto);
            dialog.dismiss();
            cargarCanales();
        });

        dialog.show();
    }

    /** El interruptor del panel guarda en cuanto se mueve (con lo que haya en los campos). */
    private void cambiarPanelAlVolar(@NonNull SwitchCompat cambio, @NonNull final Context contexto,
                                      @NonNull final EditText host, @NonNull final EditText usuario,
                                      @NonNull final EditText clave) {
        cambio.setOnCheckedChangeListener((v, marcado) -> {
            Xtream.guardar(contexto, host.getText().toString(), usuario.getText().toString(),
                    clave.getText().toString(), marcado);
            if (marcado && !Xtream.desdePreferencias(contexto).completa()) {
                Toast.makeText(contexto, R.string.xtream_incompleto, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void elegirAlcance(@NonNull Context contexto, @NonNull String alcance,
                               @NonNull TextView corto, @NonNull TextView latam, @NonNull TextView global,
                               @NonNull TextView estadoAuto) {
        AutoCanales.setAlcance(contexto, alcance);
        pintarAlcance(corto, latam, global, alcance);
        pintarEstadoAuto(estadoAuto, contexto);
        cargarCanales(true);
    }

    private void pintarAlcance(@NonNull TextView corto, @NonNull TextView latam,
                               @NonNull TextView global, @NonNull String alcance) {
        corto.setSelected(AutoCanales.ALCANCE_CORTO.equals(alcance));
        latam.setSelected(AutoCanales.ALCANCE_LATAM.equals(alcance));
        global.setSelected(AutoCanales.ALCANCE_GLOBAL.equals(alcance));
    }

    /** "Deportes · iptv-org (caché hace 12 min)" para no dejar al usuario a ciegas. */
    private void pintarEstadoAuto(@NonNull TextView destino, @NonNull Context contexto) {
        String ganadora = AutoCanales.ultimoGanador(contexto);
        if (TextUtils.isEmpty(ganadora)) {
            destino.setText(R.string.auto_sin_guardar);
            return;
        }
        String alcance = AutoCanales.alcance(contexto);
        StringBuilder linea = new StringBuilder(getString(R.string.auto_ganador, ganadora)).append("\n")
                .append(getString(R.string.auto_alcance_ok,
                        AutoCanales.etiquetaAlcance(alcance), AutoCanales.tope(alcance)));
        String hace = AutoCanales.estadoCache(contexto);
        if (!TextUtils.isEmpty(hace)) {
            linea.append(" (").append(hace).append(")");
        }
        destino.setText(linea);
    }

    @NonNull
    private static String normalizar(@Nullable String texto) {
        if (texto == null) return "";
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return sinAcentos.toLowerCase(Locale.ROOT).trim();
    }
}
