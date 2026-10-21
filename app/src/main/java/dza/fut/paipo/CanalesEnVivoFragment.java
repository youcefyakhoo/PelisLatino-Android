package dza.fut.paipo;

import android.content.Context;
import android.util.Log;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import androidx.annotation.Nullable;   // (ya estaba: vistas opcionales del layout)
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private View grupoVacio;
    private TextView txtVacio;
    private View bloqueTabs;
    private LinearLayout contenedorTabs;

    private final List<Canal> canales = new ArrayList<>();
    private final Map<String, Integer> conteoTopicos = new LinkedHashMap<>();
    private String topicoActivo = "";
    private boolean hayPartidosEnVivo;   // v3.2.43: el chip 🔥, si el feed lo justifica
    private String busqueda = "";
    private boolean cargando = false;
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
        grupoVacio = view.findViewById(R.id.grupoVacioCanales);
        txtVacio = view.findViewById(R.id.txtVacioCanales);
        bloqueTabs = view.findViewById(R.id.bloqueTabsCanales);
        contenedorTabs = view.findViewById(R.id.contenedorTabsCanales);

        if (recyclerView != null) {
            int columnas = calcularNumeroDeColumnas(requireContext());
            GridLayoutManager gestor = new GridLayoutManager(getContext(), columnas);
            // v3.2.41: adiós al span-lookup de las filas publicitarias — los tiles
            // miden todos igual (y se fue con él una carrera del LayoutManager).
            recyclerView.setLayoutManager(gestor);
        }

        // v3.2.50: refrescar y buscar ya no viven en un header propio: son items del
        // menu de la barra (ver onCreateOptionsMenu), como en el repo v9.6.
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
        grupoVacio = null;
        txtVacio = null;
        bloqueTabs = null;
        contenedorTabs = null;
        super.onDestroyView();
    }

    /**
     * Cuántas columnas mostrar según el ancho. Los tiles de DramaLive son cuadrados y
     * caben 3 en un teléfono normal, así que el mínimo sube de 2 a 3 cuando hay sitio.
     */
    private int calcularNumeroDeColumnas(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return columnasPara(metrics.widthPixels / metrics.density);
    }

    /** v3.2.32: losas GRANDES como DramaLive (o mejores). Antes: minimo 3 columnas de
     *  112dp — en un telefono de 360dp el tile media ~104dp, la mitad del tile web del
     *  sitio (~170px). Ahora el minimo por columna es 150dp y el piso son 2 columnas
     *  (tile ~166dp); techo 4 para tablets. Puro y testeable. */
    static int columnasPara(float anchoDp) {
        int c = (int) (anchoDp / 150f);
        if (c < 2) c = 2;
        if (c > 4) c = 4;
        return c;
    }

    // ─────────────────────────── carga ───────────────────────────

    private long vistoTsDrama;

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
        // v3.2.44: LA CONEXION Y LA EXTRACCION SON AUTOMATICAS DE VERDAD, EN SEGUNDO
        // PLANO y SIN WEBVIEW POR DEFECTO. AutoDrama.plan (puro, testeado) decide: sin
        // sesion -> glt empotrado (el FG 232425 viaja DENTRO de la plantilla; el usuario
        // no agrega nada) + extraer + fusionar; con sesion vieja (>6 h) -> refresco
        // NATIVO con la misma identidad, que es como el edge perdona (repide y blaquea).
        // El captador headless queda como plan B real, no como el camino de cada dia.
        String ses = FuenteCanales.sesionDrama(contexto);
        int plan = AutoDrama.plan(System.currentTimeMillis(), t, ses.trim().isEmpty());
        if (plan != AutoDrama.NADA) {
            long ahora = System.currentTimeMillis();
            FuenteCanales.guardarDramaTs(contexto, ahora);   // marca el intento: sin re-lazo hoy
            vistoTsDrama = ahora;
            if (plan == AutoDrama.CONEXION) conectarYExtraerDrama(contexto);
            else refrescarDramaNativo(contexto);
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
                    // v3.2.44: fusionar, no pisar (y avisar al player: espera una fila curada).
                    FuenteCanales.guardarDramaExtra(ctx, AutoDrama.fusionarEntrega(
                            FuenteCanales.dramaExtraIdo(ctx), rTxt.m3u));
                    PlayerActivity.notificarEntrega();
                    FuenteCanales.guardarDramaTs(ctx, System.currentTimeMillis());
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion nativa: " + rTxt.canales + " canales");
                    android.widget.Toast.makeText(ctx, "Conectado: " + rTxt.canales + " canales",
                            Toast.LENGTH_LONG).show();
                    cargarCanales(true);
                } else {
                    // v3.2.46: NADA de saltar al captador en primer plano (queja del
                    // usuario). El intento ya quedó marcado en el ts; vencida la ventana,
                    // AutoDrama.plan re-intenta solo, en segundo plano, con la misma
                    // identidad (que es lo que el edge perdona).
                    FuenteCanales.guardarDramaEstado(ctx, "auto-conexion no cuajo ("
                            + (semTxt == null ? "red/edge" : "extracción floja: "
                                    + (rTxt != null ? rTxt.error : "?")) + ") — reintento en la próxima ventana");
                }
            });
        }, "drama-auto-conexion").start();
    }

    /**
     * v3.2.44 — refresco EN SEGUNDO PLANO con la sesion guardada: extraer() nativo y
     * FUSION (nunca pisa crudo: las urls curadas del player sobreviven). Si el edge no
     * contesta, nada salta a primer plano (v3.2.46): el reintento llega solo al vencer
     * la ventana de AutoDrama.
     */
    private void refrescarDramaNativo(final Context ctx) {
        final androidx.fragment.app.FragmentActivity act = getActivity();
        if (act == null) return;
        final String ses = FuenteCanales.sesionDrama(ctx);
        if (ses.trim().isEmpty()) return;
        new Thread(() -> {
            DramaSesion.Resultado r;
            try {
                r = DramaSesion.extraer(ses, null);
            } catch (Exception e) {
                r = DramaSesion.Resultado.fallo("red: " + e);
            }
            final DramaSesion.Resultado rr = r;
            if (act.isFinishing()) return;
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (rr != null && rr.ok() && rr.canales > 0 && rr.m3u != null) {
                    FuenteCanales.guardarDramaExtra(ctx, AutoDrama.fusionarEntrega(
                            FuenteCanales.dramaExtraIdo(ctx), rr.m3u));
                    FuenteCanales.guardarDramaEstado(ctx,
                            "auto: rejilla refrescada (" + rr.canales + " canales)");
                    PlayerActivity.notificarEntrega();
                    cargarCanales(true);
                } else {
                    FuenteCanales.guardarDramaEstado(ctx, "auto: refresco no cuajo ("
                            + (rr != null ? rr.error : "?") + ") — reintento en la próxima ventana");
                }
            });
        }, "drama-auto-refresco").start();
    }

    private void cargarCanales() {
        cargarCanales(false);
        asegurarCatalogoVivo(null);   // v3.2.60: catalogo tibio junto a la fuente
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

    /**
     * v3.2.42: los canales de Drama van DELANTE y en su propio orden (como en el sitio);
     * la lista de la fuente solo rellena huecos. Antes se pegaban al final con dedupe por
     * URL, que nunca coincidía — de ahí el «revuelto» y los duplicados. Ver {@link
     * FusionRejilla}, que es pura y la prueba la suite.
     */
    @NonNull
    private String ultimaRejillaParseada;
    private List<Canal> canalesDramaCache;

    private List<Canal> conLaListaDrama(@NonNull List<Canal> base) {
        Context contexto = getContext();
        if (contexto == null) return base;
        String m3u = FuenteCanales.dramaExtraIdo(contexto);
        if (m3u.isEmpty()) return base;
        try {
            // v3.2.63: la rejilla en cache es la MISMA instancia mientras no cambie;
            // si es la misma, se reutiliza el parseo anterior en vez de re-parsear
            // miles de filas en el hilo principal en cada aplicarCanales.
            List<Canal> drama;
            if (m3u == ultimaRejillaParseada && canalesDramaCache != null) {
                drama = canalesDramaCache;
            } else {
                drama = ListaPegada.desdeTexto(m3u, FuenteCanales.dramaKey(contexto));
                ultimaRejillaParseada = m3u;
                canalesDramaCache = drama;
            }
            return FusionRejilla.conDramaPrimero(drama, base);
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
        // v3.2.64: el usuario pidio fuera Twitch e Israel — no entran a la rejilla.
        canales.removeIf(c -> c != null && FusionRejilla.topicBloqueado(c.category));
        reconstruirIndices();   // claves de filtro listas (normalizar/etiqueta son caras)
        mensajeEstado = null;
        origenActivo = origen;
        cargando = false;
        contarTopicos();
        construirTabs();
        aplicarFiltro();
        mostrarCarga(false);
    }

    private boolean captadorVisibleLanzado;

    /** v3.2.50: el buscador vive en la barra de la actividad (menu_canales, SearchView). */
    private androidx.appcompat.widget.SearchView buscador;
    private BottomSheetDialog thisSheet;

    /** v3.2.55 (captura del usuario: paises en blanco): la imagen del topico se carga
     *  DIRECTA del CDN de Drama — el proxy con &output=webp de Logos destruia los PNG
     *  palette de 4 bits de las banderas (96x64 en 164 B = blanco solido, medido con
     *  curl contra logo.twoapistack.work; el original son 399 B perfectos). Y la base
     *  (emoji o glifo) queda visible hasta que Glide confirma la descarga: si la red
     *  falla o el CDN se cae, la celda NUNCA se queda en blanco. */
    /** v3.2.57: id del drawable flag_XX.png embebido para este topic, o 0. */
    private int banderaLocalRes(@Nullable String clave, @Nullable String rotulo,
                                @Nullable String urlLogo) {
        String iso = FusionRejilla.isoDeUrlTopic(urlLogo);
        if (iso.isEmpty())
            iso = FusionRejilla.isoDeBanderaEmoji(FusionRejilla.banderaDeTopic(clave));
        if (iso.isEmpty() && rotulo != null)
            iso = FusionRejilla.isoDeBanderaEmoji(FusionRejilla.banderaDeTopic(rotulo));
        if (iso.isEmpty()) return 0;
        try {
            return getResources().getIdentifier("flag_" + iso, "drawable",
                    requireContext().getPackageName());
        } catch (Exception ignore) {
            return 0;
        }
    }

    /** v3.2.58 (la causa real del «siempre blanco», con número): el img de celdas y
     *  tabs nace con el tinte del tema (pelis_text_secondary = #DCEDF1 y el tinte del
     *  style del tab) porque los glifos son plantillas monocromas. Aplicado a una
     *  bandera real la aplanea a ese gris-casi-blanco: el cuadro blanco de las
     *  capturas. Para imagen real: tinte fuera y padding minimo, como en Drama. */
    private static void pintarImagenReal(android.widget.ImageView img, int res) {
        img.setImageTintList(null);
        int p = (int) (3 * img.getResources().getDisplayMetrics().density + 0.5f);
        img.setPadding(p, p, p, p);
        img.setImageResource(res);
        img.setVisibility(View.VISIBLE);
    }

    private void cargarIconoDeDrama(final android.widget.ImageView img, final View band,
                                    String url, final int glifo) {
        try {
            Glide.with(this)
                    .load(Logos.iconoTopico(url))
                    .apply(new RequestOptions().dontAnimate())
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                    Object model,
                                                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                    boolean isFirstResource) {
                            return false;   // la base (emoji/glifo) se queda donde estaba
                        }
                        @Override
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                       com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                       com.bumptech.glide.load.DataSource dataSource,
                                                       boolean isFirstResource) {
                            // v3.2.58: la imagen descargada tampoco pasa por el tinte
                            img.setImageTintList(null);
                            int pp = (int) (3 * img.getResources().getDisplayMetrics().density + 0.5f);
                            img.setPadding(pp, pp, pp, pp);
                            img.setVisibility(View.VISIBLE);
                            if (band != null) band.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(img);
        } catch (Exception ignoreGlide) { }
    }

    /** v3.2.54: {id o nombre del topic -> img_url_topic del glt de Drama}. */
    private java.util.Map<String, String> logosTopicos = java.util.Collections.emptyMap();

    /** Lee el prefs (o el puente en vivo de la ultima extraccion) y lo deja en memoria. */
    /** v3.2.59: {id_topic -> nombre} de los 265 topics del glt (el catalogo que el
     *  tope-40 de la extraccion no cubre). prefs drama_seed/topic_nombres. */
    private java.util.Map<String, String> catalogoTopicos = java.util.Collections.emptyMap();
    private final java.util.Set<String> topicsPrensando =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private void recargarLogosTopicos() {
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences("drama_seed", 0);
            String guardado = p.getString("topic_logos", "");
            String fresco = DramaSesion.ultimosLogosTopicos;
            if (fresco != null && !fresco.isEmpty() && !fresco.equals(guardado)) {
                p.edit().putString("topic_logos", fresco).apply();
                guardado = fresco;
            }
            logosTopicos = DramaSesion.logosDesdeLineas(guardado);
            String frescN = DramaSesion.ultimosNombresTopicos;
            String guardaN = p.getString("topic_nombres", "");
            if (frescN != null && !frescN.isEmpty() && !frescN.equals(guardaN)) {
                p.edit().putString("topic_nombres", frescN).apply();
                guardaN = frescN;
            }
            catalogoTopicos = DramaSesion.logosDesdeLineas(guardaN);
        } catch (Exception ignore) {
            logosTopicos = java.util.Collections.emptyMap();
            catalogoTopicos = java.util.Collections.emptyMap();
        }
    }

    /** v3.2.59: cuantos canales locales hay bajo el group-title de un topic. */
    private int canalesDelTopic(@Nullable String clave) {
        if (clave == null || clave.isEmpty()) return 0;
        Integer n = conteoTopicos.get(clave);
        return n == null ? 0 : n;
    }

    /** v3.2.59: el pais que el panel muestra pero la extraccion no trajo se carga
     *  AHORA, con el glbt de ese topic — el comportamiento del sitio: catalogo entero
     *  a la vista, canales al pedido. Fusiona en la fuente y re-pinta filtrado. */
    private void cargarTopicoPrensa(@NonNull final String idTopico) {
        final Context ctx = getContext();
        final androidx.fragment.app.FragmentActivity act = getActivity();
        if (ctx == null || act == null || !topicsPrensando.add(idTopico)) return;
        final String ses = FuenteCanales.sesionDrama(ctx);
        if (ses == null || ses.trim().isEmpty()) {
            topicsPrensando.remove(idTopico);
            android.widget.Toast.makeText(ctx,
                    "Sin sesión: conecta primero para poder pedir países sueltos",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.Toast.makeText(ctx,
                "Cargando " + FusionRejilla.etiquetaTopic(catalogoTopicos.getOrDefault(idTopico, idTopico)) + "…",
                android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.util.List<DramaSesion.CanalDrama> cs = DramaSesion.canalesDeTopic(ses, idTopico);
            final String m3u = (cs == null || cs.isEmpty()) ? null : DramaSesion.aM3u(cs);
            if (act.isFinishing()) { topicsPrensando.remove(idTopico); return; }
            act.runOnUiThread(() -> {
                topicsPrensando.remove(idTopico);
                if (m3u == null) {
                    android.widget.Toast.makeText(ctx,
                            "Ese país no contestó (red o límite del servidor) — reintenta",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                // v3.2.64: fusionar — los países ya cargados se quedan en la rejilla.
                FuenteCanales.guardarDramaExtra(ctx,
                        AutoDrama.fusionarEntrega(FuenteCanales.dramaExtraIdo(ctx), m3u));
                vistoTsDrama = FuenteCanales.dramaTs(ctx);
                recargarLogosTopicos();
                cargarCanales(true);
                topicoActivo = idTopico;
                aplicarFiltro();
                android.widget.Toast.makeText(ctx,
                            countCanalesMsg(idTopico),
                            android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "prensa-" + idTopico).start();
    }

    @Nullable
    /** v3.2.61 («cuando click se muestra los canales del pais»): una fila del
     *  catalogo llega con clave id_topic ("963", "pe") mientras el feed agrupa por
     *  nombre ("Syria", "Peru"). Dos topics son EL MISMO pais si coinciden crudos,
     *  normalizados, por rotulo canonico (los ids telefonicos caen ahi via MARCA-
     *  DORES) o por la URL que Drama manda para ambos (los ISO de 2 letras del glt
     *  no viven en la tabla, pero "pe" y "Peru" apuntan al mismo ic_flag_pe.png). */
    private boolean mismoTopic(@Nullable String a1, @Nullable String b1) {
        if (a1 == null || b1 == null) return false;
        if (a1.equals(b1)) return true;
        if (normalizar(a1).equals(normalizar(b1))) return true;
        try {
            if (FusionRejilla.etiquetaTopic(a1).equals(FusionRejilla.etiquetaTopic(b1)))
                return true;
        } catch (Exception ignoreE) { }
        String ua = logosTopicos.get(a1), ub = logosTopicos.get(b1);
        if (ua == null) ua = logosTopicos.get(normalizar(a1));
        if (ub == null) ub = logosTopicos.get(normalizar(b1));
        return ua != null && ua.equals(ub);
    }

    /** v3.2.61: mensaje honesto post-carga — cuenta por identidad, no por clave. */
    @androidx.annotation.NonNull
    private String countCanalesMsg(@NonNull String idTopico) {
        int n = 0;
        for (Canal cc : canales)
            if (cc != null && mismoTopic(cc.category, idTopico)) n++;
        if (n == 0) n = conteoTopicos.getOrDefault(idTopico, 0);
        return FusionRejilla.etiquetaTopic(catalogoTopicos.getOrDefault(idTopico, idTopico))
                + ": " + n + " canales";
    }

    private String urlLogoTopico(@Nullable String clave, @Nullable String rotulo) {
        if (logosTopicos.isEmpty()) return null;
        String u = clave == null ? null : logosTopicos.get(clave);
        if (u == null && rotulo != null) u = logosTopicos.get(rotulo);
        if (u == null && clave != null)
            u = logosTopicos.get(clave.trim().toLowerCase(java.util.Locale.ROOT));
        if (u == null && rotulo != null)
            u = logosTopicos.get(rotulo.trim().toLowerCase(java.util.Locale.ROOT));
        if (u == null) {   // "Live TV Syria" / "TV Syria" / "syria" — normalizamos ambos lados
            String buscado = normalizar(clave == null ? "" : clave);
            for (java.util.Map.Entry<String, String> en : logosTopicos.entrySet()) {
                if (normalizar(en.getKey()).equals(buscado)) { u = en.getValue(); break; }
            }
        }
        return u;
    }   // v3.2.19: un autolanzamiento por visita, sin loops

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
        // v3.2.46: antes abríamos el captador VISIBLE cuando la fuente moría. Con la
        // cadena automática eso cortaba al usuario por nada: el aviso queda en el estado
        // y el reintento silencioso lo trae AutoDrama al vencer la ventana.
        android.util.Log.w("BLABONGO", "fuente caída (sin salto a primer plano): " + mensaje);
        Context c = getContext();
        if (c != null) FuenteCanales.guardarDramaEstado(c, "fuente caída: " + mensaje);
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

    private void contarTopicos() {
        conteoTopicos.clear();
        hayPartidosEnVivo = false;
        for (Canal canal : canales) {
            if (canal == null) continue;
            if (!hayPartidosEnVivo && canal.options != null && !canal.options.isEmpty()
                    && canal.options.get(0) != null
                    && TiraCanales.esPartidoEnVivo(canal.options.get(0).url)) {
                hayPartidosEnVivo = true;   // el "Live Matches" de las capturas
            }
            if (TextUtils.isEmpty(canal.category)) continue;
            String topico = canal.category.trim();
            if (topico.isEmpty()) continue;
            Integer actual = conteoTopicos.get(topico);
            conteoTopicos.put(topico, actual == null ? 1 : actual + 1);
        }
    }

    private String ultimaFirmaTabs;

    private void construirTabs() {
        if (contenedorTabs == null) return;
        recargarLogosTopicos();   // v3.2.54: los iconos que manda el propio Drama
        // v3.2.43: la tira la arma TiraCanales (◎ Todos, ♥ Favoritos, ★ Los más vistos,
        // país del aparato clavado, tópicos en SU orden, 🔥 Partidos en vivo). Con un solo
        // tópico y nada guardado la tira sobra: se oculta en vez de dejar una fila vacía.
        if (conteoTopicos.size() < 2 && !hayPartidosEnVivo
                && UsoCanales.favoritos(requireContext()).isEmpty()
                && UsoCanales.vistas(requireContext()).isEmpty()
                && catalogoTopicos.isEmpty()) {
            contenedorTabs.removeAllViews();
            ultimaFirmaTabs = null;
            bloqueTabs.setVisibility(View.GONE);
            return;
        }
        bloqueTabs.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        java.util.List<String[]> tiras = TiraCanales.construir(conteoTopicos,
                TiraCanales.paisDelAparato(),
                UsoCanales.favoritos(requireContext()).size(),
                UsoCanales.vistas(requireContext()).size(),
                hayPartidosEnVivo);
        java.util.List<String[]> filasCat = filasDelPanel();
        // v3.2.64: si nada cambio, la tira no se reinfla (cientos de vistas por
        // pintado eran parte de la lentitud) — solo se repinta la selección.
        StringBuilder firmaB = new StringBuilder();
        for (String[] t : tiras) firmaB.append(t[0]).append('#').append(t[2]).append(';');
        for (String[] f : filasCat)
            if ("pais".equals(f[0]) && f[4].isEmpty()) firmaB.append('@').append(f[1]).append(';');
        String firma = firmaB.toString();
        if (firma.equals(ultimaFirmaTabs) && contenedorTabs.getChildCount() > 0) {
            pintarSeleccionTabs();
            return;
        }
        ultimaFirmaTabs = firma;
        contenedorTabs.removeAllViews();
        int tope = tiras.size();   // v3.2.64: sin tope 12 — navegación horizontal completa
        for (int i = 0; i < tope; i++) {
            String[] tira = tiras.get(i);
            String rotulo = TiraCanales.CLAVE_TODO.equals(tira[0])
                    ? getString(R.string.canales_tab_todo) : tira[1];
            int icono = TiraCanales.CLAVE_FAVORITOS.equals(tira[0])
                    || TiraCanales.CLAVE_VISTOS.equals(tira[0])
                    || TiraCanales.CLAVE_VIVOS.equals(tira[0])
                    ? R.drawable.ic_topic_star
                    : TiraCanales.CLAVE_TODO.equals(tira[0])
                    ? R.drawable.ic_topic_todos : iconoPara(tira[0]);   // v3.2.49: icono propio del repo
            int cantidad = tira[2].isEmpty() ? canales.size() : Integer.parseInt(tira[2]);
            // v3.2.48 (repo 82105f5 + capturas de Drama): los paises llevan la BANDERA
            // emoji como icono del tile — arriba del nombre, no metida en el rótulo;
            // el resto de topicos sigue con su glifo.
            String bandera = FusionRejilla.banderaDeTopic(tira[0]);
            if (bandera.isEmpty())
                bandera = FusionRejilla.banderaDeUrlTopic(urlLogoTopico(tira[0], rotulo));
            if (!bandera.isEmpty() && rotulo.startsWith(bandera)) {
                rotulo = rotulo.substring(bandera.length()).trim();
            }
            anadirTab(inflater, tira[0], rotulo, cantidad, icono, bandera);
        }
        // v3.2.64: los países del catálogo también viven en la tira horizontal
        // (sin abrir el círculo); tocar uno sin canales lo carga bajo demanda.
        for (String[] f : filasCat) {
            if (!"pais".equals(f[0]) || !f[4].isEmpty()) continue;
            String rotCat = f[2];
            String bandCat = f[3];
            if (!bandCat.isEmpty() && rotCat.startsWith(bandCat))
                rotCat = rotCat.substring(bandCat.length()).trim();
            anadirTab(inflater, f[1], rotCat, 0, iconoPara(f[1]),
                    bandCat.isEmpty() ? null : bandCat);
        }
    }

    /** El ícono no es decorativo: fútbol se marca con estrella, como en DramaLive. */
    private int iconoPara(String topico) {
        String t = normalizar(topico);
        if (t.contains("fut") || t.contains("deporte") || t.contains("sport")) {
            return R.drawable.ic_topic_star;
        }
        if (FusionRejilla.esPais(topico)) return R.drawable.ic_topic_live;
        return R.drawable.ic_topic_grid;
    }

    private void anadirTab(LayoutInflater inflater, String clave, String rotulo,
                           int cantidad, int icono) {
        anadirTab(inflater, clave, rotulo, cantidad, icono, null);
    }

    /**
     * @param bandera si no es vacio, en vez del glifo se pinta la bandera del pais
     *                (emoji) como icono del tile. Lo usan las pestanas de pais.
     */
    private void anadirTab(LayoutInflater inflater, String clave, String rotulo,
                           int cantidad, int icono, @androidx.annotation.Nullable String bandera) {
        View tab = inflater.inflate(R.layout.item_tab_canal, contenedorTabs, false);
        TextView txt = tab.findViewById(R.id.txtTabCanal);
        ImageView img = tab.findViewById(R.id.imgTabCanal);
        TextView txtBandera = tab.findViewById(R.id.txtTabBandera);
        txt.setText(rotulo);
        txt.setContentDescription(getString(R.string.canales_tab_desc, rotulo, cantidad));
        if (bandera != null && !bandera.isEmpty() && txtBandera != null) {
            img.setVisibility(View.GONE);
            txtBandera.setText(bandera);
            txtBandera.setVisibility(View.VISIBLE);
        } else {
            img.setImageResource(icono);
        }
        String urlLogo = urlLogoTopico(clave, rotulo);
        int banderaLocal = banderaLocalRes(clave, rotulo, urlLogo);
        if (banderaLocal != 0) {
            // v3.2.57: bandera REAL embebida (la misma imagen de Drama) — no toca red
            // ni fuente de emojis; el blanco del usuario ya no tiene de donde salir.
            if (txtBandera != null) txtBandera.setVisibility(View.GONE);
            pintarImagenReal(img, banderaLocal);
        } else if (urlLogo != null) {
            cargarIconoDeDrama(img, txtBandera, urlLogo, icono);
        }
        tab.setTag(clave);
        tab.setSelected(mismoTopic(clave, topicoActivo));   // v3.2.61
        if (TiraCanales.CLAVE_TODO.equals(clave)) {
            // v3.2.52: el circulo de Drama. Un toque abre el panel con TODOS los paises
            // (bandera) y categorias; "Todos" se elige ahi, en la primera fila.
            tab.setOnClickListener(v -> abrirPanelCategorias());
            tab.setOnLongClickListener(v -> {
                topicoActivo = TiraCanales.CLAVE_TODO;
                pintarSeleccionTabs();
                aplicarFiltro();
                return true;
            });
        } else if (cantidad == 0 && catalogoTopicos.containsKey(clave)) {
            // v3.2.64: país del catálogo sin canales — tocarlo lo pide (como el panel).
            tab.setOnClickListener(v -> {
                topicoActivo = clave;
                pintarSeleccionTabs();
                cargarTopicoPrensa(clave);
            });
        } else {
            tab.setOnClickListener(v -> {
                topicoActivo = (String) v.getTag();
                pintarSeleccionTabs();
                aplicarFiltro();
            });
        }
        contenedorTabs.addView(tab);
    }

    /** v3.2.53 — el panel del circulo, calcado de la captura del Drama real: buscador
     *  arriba y REJILLA de celdas (bandera o glifo en cuadro redondeado + nombre), con el
     *  bloque azul en la activa. Tocar una celda filtra la rejilla y cierra el panel. */
    /** v3.2.60: filas del panel, recalculadas sobre el estado actual. */
    @androidx.annotation.NonNull
    private java.util.List<String[]> filasDelPanel() {
        android.content.Context ctx = getContext();
        if (ctx == null) return java.util.Collections.emptyList();
        java.util.List<String[]> filas = new java.util.ArrayList<>(PanelCategorias.construir(
                conteoTopicos, TiraCanales.paisDelAparato(),
                UsoCanales.favoritos(ctx).size(), UsoCanales.vistas(ctx).size(),
                hayPartidosEnVivo, canales.size(), logosTopicos, catalogoTopicos));
        filas.removeIf(f -> FusionRejilla.topicBloqueado(f[1])
                || FusionRejilla.topicBloqueado(f[2]));   // v3.2.64
        return filas;
    }

    private boolean catalogoPidiendo;

    /** v3.2.60: un request al `glt` con la semilla guardada llena el catalogo del
     *  panel sin depender de la extraccion del dia (el tope-40 y el once-al-dia del
     *  plan automatico ya no recortan la lista). Sin sesion, no se genera trafico. */
    private void asegurarCatalogoVivo(@Nullable final Runnable alLlegar) {
        final Context ctx = getContext();
        final androidx.fragment.app.FragmentActivity act = getActivity();
        if (ctx == null || act == null || catalogoPidiendo) return;
        if (alLlegar == null && !catalogoTopicos.isEmpty() && !logosTopicos.isEmpty())
            return;   // arranque con catalogo tibio: nada que pedir
        // v3.2.62 (velocidad): catalogo fresco (<10 min) = el panel abre al
        // instante con lo que hay, sin request ni repintado a mitad de uso.
        long tsCat = 0;
        try { tsCat = ctx.getSharedPreferences("drama_seed", 0).getLong("topic_nombres_ts", 0); } catch (Exception ignore2) { }
        if (!catalogoTopicos.isEmpty() && System.currentTimeMillis() - tsCat < 10 * 60_000L) {
            if (alLlegar != null) alLlegar.run();
            return;
        }
        final String ses;
        try { ses = FuenteCanales.sesionDrama(ctx); } catch (Exception ignore) { return; }
        if (ses == null || ses.trim().isEmpty()) return;
        catalogoPidiendo = true;
        new Thread(() -> {
            final String[] cat = DramaSesion.catalogoDeSesion(ses);
            act.runOnUiThread(() -> {
                catalogoPidiendo = false;
                if (cat == null || !isAdded()) return;
                try {
                    ctx.getSharedPreferences("drama_seed", 0).edit()
                            .putString("topic_nombres", cat[0])
                            .putString("topic_logos", cat[1])
                            .putLong("topic_nombres_ts", System.currentTimeMillis()).apply();
                } catch (Exception ignoreW) { }
                recargarLogosTopicos();
                construirTabs();   // v3.2.64: la tira gana los países apenas llega el catálogo
                if (alLlegar != null) alLlegar.run();
            });
        }, "glt-catalogo").start();
    }

    private void abrirPanelCategorias() {
        if (getContext() == null) return;
        final BottomSheetDialog dlg = new BottomSheetDialog(requireContext());
        final android.view.View root = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_panel_categorias, null);
        final android.widget.LinearLayout cont = root.findViewById(R.id.contenedorPanel);
        final android.widget.EditText buscadorPanel = root.findViewById(R.id.editPanelCategorias);
        buscadorPanel.setHint("Buscar país o categoría");
        final Runnable repintar = new Runnable() {
            @Override
            public void run() {
                if (getContext() == null) return;
                // v3.2.60: las filas se RECALCULAN en cada repintado — si el catalogo
                // vivo llega con el panel abierto (o un pais se carga bajo demanda),
                // la rejilla se actualiza sola en vez de quedarse congelada al abrir.
                pintarCeldasDelPanel(cont, PanelCategorias.filtrar(filasDelPanel(),
                        buscadorPanel.getText().toString()));
            }
        };
        buscadorPanel.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void afterTextChanged(android.text.Editable s) { repintar.run(); }
        });
        repintar.run();
        dlg.setContentView(root);
        thisSheet = dlg;
        dlg.setOnDismissListener(dd -> thisSheet = null);
        dlg.show();
        // v3.2.60: ademas, pide el glt vivo: si el catalogo local estaba vacio o
        // viejo, al llegar la respuesta el panel se re-pinta con los 265 topics.
        asegurarCatalogoVivo(() -> {
            if (dlg.isShowing() && isAdded()) repintar.run();
        });
    }

    /** Una fila de `columnas` celdas con peso 1 — la ultima se rellena con espaciadores
     *  para que las celdas mantengan el mismo tamano (como la rejilla de canales). */
    private void pintarCeldasDelPanel(android.widget.LinearLayout cont, java.util.List<String[]> filas) {
        cont.removeAllViews();
        LayoutInflater in = LayoutInflater.from(cont.getContext());
        final int cols = PanelCategorias.columnasPanel(
                cont.getResources().getDisplayMetrics().widthPixels
                        / cont.getResources().getDisplayMetrics().density);
        android.widget.LinearLayout fila = null;
        int enFila = 0;
        for (String[] f : filas) {
            if (fila == null || enFila == 0) {
                fila = new android.widget.LinearLayout(cont.getContext());
                fila.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                cont.addView(fila, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final String clave = f[1];
            android.view.View celda = in.inflate(R.layout.item_panel_celda, fila, false);
            android.widget.TextView band = celda.findViewById(R.id.txtCeldaBandera);
            android.widget.ImageView img = celda.findViewById(R.id.imgCelda);
            android.widget.TextView nom = celda.findViewById(R.id.txtCeldaNombre);
            String emoji = f[3];
            if ("categoria".equals(f[0])) emoji = PanelCategorias.emojiPara(f[2]);
            if (!emoji.isEmpty()) {
                img.setVisibility(View.GONE);
                band.setText(emoji);
            } else {
                band.setVisibility(View.GONE);
                img.setVisibility(View.VISIBLE);
                img.setImageResource("fijo".equals(f[0]) ? R.drawable.ic_topic_live : iconoPara(clave));
            }
            String urlLogo = urlLogoTopico(clave, f[2]);
            if (emoji.isEmpty() && urlLogo != null)
                emoji = FusionRejilla.banderaDeUrlTopic(urlLogo);
            int banderaLocal = banderaLocalRes(clave, f[2], urlLogo);
            if (banderaLocal != 0) {
                band.setVisibility(View.GONE);
                pintarImagenReal(img, banderaLocal);   // imagen real de Drama, sin red
            } else if (urlLogo != null) {
                cargarIconoDeDrama(img, band, urlLogo,
                        "fijo".equals(f[0]) ? R.drawable.ic_topic_live : iconoPara(clave));
            }
            nom.setText(f[2]);
            celda.setSelected(clave == null ? false
                    : (clave.isEmpty() ? TiraCanales.CLAVE_TODO.equals(topicoActivo)
                            : mismoTopic(clave, topicoActivo)));   // v3.2.61
            celda.setOnClickListener(v -> {
                if (canalesDelTopic(clave) == 0 && catalogoTopicos.containsKey(clave)) {
                    // v3.2.59: pais del catalogo que la extraccion no trajо — carga al pedido
                    BottomSheetDialog d0 = thisSheet;
                    if (d0 != null) d0.dismiss();
                    cargarTopicoPrensa(clave);
                    return;
                }
                topicoActivo = clave;
                pintarSeleccionTabs();
                aplicarFiltro();
                BottomSheetDialog d = thisSheet;
                if (d != null) d.dismiss();
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(3, 3, 3, 3);
            celda.setLayoutParams(lp);
            fila.addView(celda);
            if (++enFila == cols) { fila = null; enFila = 0; }
        }
        if (fila != null && enFila > 0) {
            while (enFila++ < cols) {
                android.view.View pad = new android.view.View(cont.getContext());
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        0, 1, 1f);
                pad.setLayoutParams(lp);
                fila.addView(pad);
            }
        }
    }


    private void pintarSeleccionTabs() {
        if (contenedorTabs == null) return;
        for (int i = 0; i < contenedorTabs.getChildCount(); i++) {
            View tab = contenedorTabs.getChildAt(i);
            tab.setSelected(tab.getTag() != null && tab.getTag().equals(topicoActivo));
        }
    }

    // ─────────────────────────── barra (menu de la actividad) ───────────────────────────
    //
    // v3.2.50 (repo v9.6 "una sola barra"): el buscador y el refrescar son items del
    // setSupportActionBar de Main. El SearchView se comporta como en Drama: icono que al
    // pulsarlo despliega la pildora; al plegarla se limpia el filtro (si no, la rejilla
    // queda filtrada y no se ve por que).

    @Override
    public void onCreate(@androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull android.view.Menu menu, @NonNull android.view.MenuInflater inflater) {
        inflater.inflate(R.menu.menu_canales, menu);
        android.view.MenuItem itemBuscar = menu.findItem(R.id.accion_buscar_canales);
        if (itemBuscar != null && itemBuscar.getActionView() instanceof androidx.appcompat.widget.SearchView) {
            buscador = (androidx.appcompat.widget.SearchView) itemBuscar.getActionView();
            configurarBuscador(buscador);
        }
        android.view.MenuItem itemRefrescar = menu.findItem(R.id.accion_refrescar_canales);
        if (itemRefrescar != null) {
            itemRefrescar.setOnMenuItemClickListener(item -> {
                cargarCanales(true);
                return true;
            });
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    private void configurarBuscador(@NonNull androidx.appcompat.widget.SearchView sv) {
        sv.setQueryHint(getString(R.string.canales_buscar_hint));
        sv.setIconifiedByDefault(true);
        sv.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
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

    /** El aviso discreto (estado del auto, conteos) bajo el titulo de la barra. */
    private void estadoEnBarra(String texto) {
        try {
            if (getActivity() instanceof androidx.appcompat.app.AppCompatActivity) {
                androidx.appcompat.app.ActionBar ab =
                        ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar();
                if (ab != null) ab.setSubtitle(texto);
            }
        } catch (Exception ignore) { }
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

    private String[] idxCatNorm, idxCatEtiqueta, idxCatUrl, idxTituloNorm;

    /** v3.2.64: normalizar (NFD+regex) y etiquetaTopic (escaneo de tablas) son
     *  demasiado caros para correr por canal en cada tecla o cambio de filtro:
     *  se calculan UNA vez al aplicar canales y el filtro solo compara listas. */
    private void reconstruirIndices() {
        int n = canales.size();
        idxCatNorm = new String[n];
        idxCatEtiqueta = new String[n];
        idxCatUrl = new String[n];
        idxTituloNorm = new String[n];
        java.util.Map<String, String[]> porCategoria = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            Canal c = canales.get(i);
            if (c == null) {
                idxCatNorm[i] = idxCatEtiqueta[i] = idxCatUrl[i] = idxTituloNorm[i] = "";
                continue;
            }
            String cat = c.category == null ? "" : c.category;
            String[] trio = porCategoria.get(cat);
            if (trio == null) {
                String norm = normalizar(cat);
                String etiq;
                try { etiq = FusionRejilla.etiquetaTopic(cat); } catch (Exception e) { etiq = ""; }
                String url = logosTopicos.get(cat);
                if (url == null) url = logosTopicos.get(norm);
                trio = new String[]{norm, etiq, url == null ? "" : url};
                porCategoria.put(cat, trio);
            }
            idxCatNorm[i] = trio[0];
            idxCatEtiqueta[i] = trio[1];
            idxCatUrl[i] = trio[2];
            idxTituloNorm[i] = normalizar(c.title);
        }
    }

    private void aplicarFiltroInterno() {
        List<Canal> visibles = new ArrayList<>();
        if (idxCatNorm == null || idxCatNorm.length != canales.size()) reconstruirIndices();
        String topicoNormal = normalizar(topicoActivo);
        // v3.2.43: las claves especiales de la tira filtran por USO (♥ favoritos, ★ más
        // vistos, 🔥 en vivo), no por tópico.
        final java.util.Set<String> favs = TiraCanales.CLAVE_FAVORITOS.equals(topicoActivo)
                ? UsoCanales.favoritos(requireContext()) : null;
        java.util.List<String> top = TiraCanales.CLAVE_VISTOS.equals(topicoActivo)
                ? UsoCanales.topVistas(UsoCanales.vistas(requireContext()), 20) : null;
        final java.util.Set<String> topSet = top == null ? null : new java.util.HashSet<>(top);
        // v3.2.64: la identidad del tópico activo se resuelve UNA vez por filtro.
        String activoEtiqueta = "";
        try { activoEtiqueta = FusionRejilla.etiquetaTopic(topicoActivo); } catch (Exception ignoreE2) { }
        String activoUrl = logosTopicos.get(topicoActivo);
        if (activoUrl == null) activoUrl = logosTopicos.get(topicoNormal);
        if (activoUrl == null) activoUrl = "";
        for (int i = 0; i < canales.size(); i++) {
            Canal canal = canales.get(i);
            if (canal == null) continue;
            if (!topicoActivo.isEmpty()) {
                if (favs != null) {
                    if (!favs.contains(UsoCanales.claveDe(canal))) continue;
                } else if (topSet != null) {
                    if (!topSet.contains(UsoCanales.claveDe(canal))) continue;
                } else if (TiraCanales.CLAVE_VIVOS.equals(topicoActivo)) {
                    Canal.OpcionCanal o0 = canal.options == null || canal.options.isEmpty()
                            ? null : canal.options.get(0);
                    if (o0 == null || !TiraCanales.esPartidoEnVivo(o0.url)) continue;
                } else if (!(topicoNormal.equals(idxCatNorm[i])
                        || (!activoEtiqueta.isEmpty() && activoEtiqueta.equals(idxCatEtiqueta[i]))
                        || (!activoUrl.isEmpty() && activoUrl.equals(idxCatUrl[i])))) continue;   // v3.2.64: identidad v3.2.61, precomputada
            }
            if (!busqueda.isEmpty() && !idxTituloNorm[i].contains(busqueda)) continue;
            visibles.add(canal);
        }

        boolean hayFiltro = !busqueda.isEmpty() || !topicoActivo.isEmpty();
        // v3.2.41: la rejilla lleva solo canales (mismo motivo que la agenda).
        List<Object> items = new ArrayList<>(visibles);

        if (adapter == null) {
            adapter = new CanalAdapter(items, requireContext());
            // v3.2.43: el corazon de la losa. Alternar no abre la hoja de opciones.
            adapter.setFavoritos(UsoCanales.favoritos(requireContext()));
            adapter.setOnFavorito(canal -> {
                if (adapter == null || getContext() == null) return;
                boolean dentro = UsoCanales.alternarFavorito(requireContext(), canal);
                adapter.setFavoritos(UsoCanales.favoritos(requireContext()));
                construirTabs();
                if (TiraCanales.CLAVE_FAVORITOS.equals(topicoActivo)) aplicarFiltro();
                Toast.makeText(requireContext(),
                        dentro ? R.string.canales_fav_si : R.string.canales_fav_no,
                        Toast.LENGTH_SHORT).show();
            });
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

        // v3.2.50: el conteo/estado del header ahora es el subtitulo de la barra.
        if (hayFiltro) {
            estadoEnBarra(getString(R.string.canales_conteo_filtrado, visibles.size(), canales.size()));
        } else if (TextUtils.isEmpty(origenActivo)) {
            estadoEnBarra(getString(R.string.canales_conteo, canales.size()));
        } else {
            estadoEnBarra(getString(R.string.auto_ok, origenActivo, canales.size()));
        }

        pintarEstadoVacio(items.isEmpty());
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
        texto.setTextColor(0xFFF3F5FA);
        texto.setTextSize(12f);
        texto.setTextIsSelectable(true);
        hoja.setBackgroundColor(0xFF0D0F14);
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

    @NonNull
    private static String normalizar(@Nullable String texto) {
        if (texto == null) return "";
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return sinAcentos.toLowerCase(Locale.ROOT).trim();
    }
}
