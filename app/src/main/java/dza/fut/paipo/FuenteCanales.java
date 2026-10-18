package dza.fut.paipo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Map;

/**
 * La fuente de canales, configurable por el usuario.
 *
 * Igual que DramaLive (que es solo un reproductor y lo dice en su web: "users are
 * required to provide their own content"), la app no trae canales "propios": hay
 * una fuente por defecto y el usuario puede cambiarla por:
 *   - un código FG, resuelto contra un endpoint ({@code …/get?code=XXXXX}), o
 *   - una URL directa a una lista M3U o al JSON que ya usaba la app.
 *
 * Nada de esto inventa contenido: solo cambia DE DÓNDE se descarga la lista.
 */
public final class FuenteCanales {

    public static final String URL_POR_DEFECTO = "https://belkaperu.github.io/belkafut/data1.json";
    public static final String ENDPOINT_FG_POR_DEFECTO = "https://fgcode.store/link/api.php/get?code=";

    private static final String PREFS = "fuente_canales";
    private static final String CLAVE_CODIGO = "codigo_fg";
    private static final String CLAVE_URL = "url_lista";
    private static final String CLAVE_ENDPOINT = "endpoint_fg";
    /** Texto de lista o URL de stream que el usuario pegó a mano (ver {@link ListaPegada}). */
    private static final String CLAVE_PEGADA = "lista_pegada";
    /** Cabeceras que el usuario manda al leer la fuente y al abrir el canal (ver {@link Encabezados}). */
    private static final String CLAVE_CABECERAS = "encabezados";
    /** Clave del player de Drama (`webPlayerSecret` del config) para los enlaces cifrados. */
    private static final String CLAVE_DRAMA = "drama_clave";

    /** Resultado entregado siempre en el hilo principal. */
    public interface Oyente {
        void onCanales(@NonNull List<Canal> canales);

        /** `mensaje` es lo suficientemente explícito para mostrarse tal cual. */
        void onError(@NonNull String mensaje, boolean esHttp, int codigoHttp);
    }

    private static OkHttpClient cliente;

    public final String codigo;
    public final String url;
    public final String endpoint;
    /** Lista o URL pegada a mano; si viene rellena, manda sobre código y URL. */
    public final String pegada;
    /** Texto de cabeceras HTTP ("Referer: https://…"): se usan al leer la fuente y al abrir. */
    public final String cabeceras;
    /** `webPlayerSecret` guardado por el usuario; vacio = solo se prueban las claves publicas. */
    public final String dramaKey;

    private FuenteCanales(String codigo, String url, String endpoint) {
        this(codigo, url, endpoint, "", "");
    }

    private FuenteCanales(String codigo, String url, String endpoint, String pegada) {
        this(codigo, url, endpoint, pegada, "");
    }

    private FuenteCanales(String codigo, String url, String endpoint, String pegada, String cabeceras) {
        this(codigo, url, endpoint, pegada, cabeceras, "");
    }

    private FuenteCanales(String codigo, String url, String endpoint, String pegada,
                          String cabeceras, String dramaKey) {
        this.codigo = codigo;
        this.url = url;
        this.endpoint = endpoint;
        this.pegada = pegada == null ? "" : pegada;
        this.cabeceras = cabeceras == null ? "" : cabeceras;
        this.dramaKey = dramaKey == null ? "" : dramaKey.trim();
    }

    /** La clave del player guardada, tal cual (la usa {@link EnlaceDrama}). */
    @NonNull
    public static String dramaKey(@NonNull Context contexto) {
        String v = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE_DRAMA, "");
        return v == null ? "" : v;
    }

    /** Cabeceras ya analizadas, listas para aplicar a una petición o a ExoPlayer. */
    @NonNull
    public Encabezados encabezados() {
        return Encabezados.fromTexto(cabeceras);
    }

    public static FuenteCanales desdePreferencias(@NonNull Context contexto) {
        SharedPreferences prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new FuenteCanales(
                prefs.getString(CLAVE_CODIGO, ""),
                prefs.getString(CLAVE_URL, ""),
                prefs.getString(CLAVE_ENDPOINT, ENDPOINT_FG_POR_DEFECTO),
                prefs.getString(CLAVE_PEGADA, ""),
                prefs.getString(CLAVE_CABECERAS, ""),
                prefs.getString(CLAVE_DRAMA, ""));
    }

    /** v3.2.24: ultimo codigo/atajo FG del usuario — el captador lo usa para sembrar el
     *  wizard de web.dramalive.cc y conectar solo. */
    @NonNull
    public static String codigoFg(@NonNull Context contexto) {
        String v = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE_CODIGO, "");
        return v == null ? "" : v;
    }

    /** v3.2.24: el sitio nos enseño su codigo (trafico o source_config): guardarlo SIN tocar
     *  el resto de la fuente. */
    public static void guardarSoloCodigo(@NonNull Context contexto, @Nullable String codigo) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_CODIGO, limpiar(codigo)).apply();
    }

    public static void guardar(@NonNull Context contexto, @Nullable String codigo,
                               @Nullable String url, @Nullable String endpoint) {
        SharedPreferences p = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        guardar(contexto, codigo, url, endpoint, p.getString(CLAVE_CABECERAS, ""));
    }

    public static void guardar(@NonNull Context contexto, @Nullable String codigo,
                               @Nullable String url, @Nullable String endpoint,
                               @Nullable String cabeceras) {
        SharedPreferences p = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        guardar(contexto, codigo, url, endpoint, cabeceras, p.getString(CLAVE_DRAMA, ""));
    }

    public static void guardar(@NonNull Context contexto, @Nullable String codigo,
                               @Nullable String url, @Nullable String endpoint,
                               @Nullable String cabeceras, @Nullable String claveDrama) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_CODIGO, limpiar(codigo))
                .putString(CLAVE_URL, limpiar(url))
                .putString(CLAVE_ENDPOINT, TextUtils.isEmpty(limpiar(endpoint))
                        ? ENDPOINT_FG_POR_DEFECTO : limpiar(endpoint))
                .putString(CLAVE_CABECERAS, cabeceras == null ? "" : cabeceras.trim())
                .putString(CLAVE_DRAMA, claveDrama == null ? "" : claveDrama.trim())
                .apply();
    }

    /** Cabeceras guardadas, tal cual las escribió el usuario. */
    @NonNull
    public static String cabecerasEnBruto(@NonNull Context contexto) {
        String v = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CLAVE_CABECERAS, "");
        return v == null ? "" : v;
    }

    /** Aplica a una petición de OkHttp las cabeceras del usuario que correspondan a esa URL. */
    @NonNull
    public static Request conCabecerasDelUsuario(@NonNull Request.Builder builder,
                                                 @Nullable Map<String, String> cabeceras) {
        if (cabeceras != null) {
            for (Map.Entry<String, String> e : cabeceras.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }
        return builder.build();
    }

    /** Deja la app como venía: la fuente por defecto, sin código ni lista propia. */
    public static void restablecer(@NonNull Context contexto) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** true si el usuario puso algo (no se está usando la fuente por defecto). */
    public boolean esPersonalizada() {
        return !vacio(codigo) || !vacio(url) || !vacio(pegada);
    }

    /** Lo que el usuario pegó, tal cual. */
    @NonNull
    public static String pegada(@NonNull Context contexto) {
        String v = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CLAVE_PEGADA, "");
        return v == null ? "" : v;
    }

    /** M3U resultado de la extraccion DramaLive in-app (ver DramaSesion); va fusionado en la rejilla. */
    private static final String CLAVE_LISTA_DRAMA = "lista_drama_m3u";
    /** La plantilla de sesion pegada (plantillas.json), para reintentar sin volver a copiar. */
    private static final String CLAVE_SESION_DRAMA = "sesion_drama_json";

    @NonNull
    public static String dramaExtraIdo(@NonNull Context contexto) {
        String v = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE_LISTA_DRAMA, "");
        // v3.2.9: auto-cura los suenos update.mp4 que v3.2.6 escribio en la lista.
        return v == null ? "" : DramaSesion.sanearSuenos(v);
    }

    /** v3.2.6: cuando el reproductor resuelve un canal dramalive:// con exito, la lista
     * guardada se reescribe con la url real: el proximo tap no vuelve a resolver. */
    public static void fijarUrlResuelta(@NonNull Context contexto, @NonNull String canalUrl,
                                       @NonNull String urlFinal) {
        // v3.2.10: el clip de relleno update.mp4 JAMAS se cachea en la rejilla — quien lo
        // guardaba aqui fue el envenenamiento que sanearSuenos luego descubria (logcat
        // 15:44: «se van todas las canales»). Solo se guarda un stream que parece real.
        if (urlFinal.contains("update.mp4") || urlFinal.contains("twoapistack")) return;
        String m3u = dramaExtraIdo(contexto);
        if (m3u.isEmpty() || !m3u.contains(canalUrl)) return;
        guardarDramaExtra(contexto, m3u.replace(canalUrl, urlFinal));
    }

    /** v3.2.10: numero de bloques #EXTINF de una rejilla (puro, testeable en JVM). */
    public static int contarBloques(@Nullable String m3u) {
        if (m3u == null) return 0;
        int n = 0;
        for (String l : m3u.split("\n")) if (l.startsWith("#EXTINF")) n++;
        return n;
    }

    public static void guardarDramaExtra(@NonNull Context contexto, @Nullable String m3u) {
        // v3.2.10: una entrega vacia o sin canales NUNCA pisa una rejilla que si tenia.
        // (El boton «quitar canales» usa limpiarDramaExtra, que si puede vaciar.)
        int nuevos = contarBloques(m3u);
        int viejos = contarBloques(contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CLAVE_LISTA_DRAMA, ""));
        if (nuevos == 0 && viejos > 0) {
            android.util.Log.w("DramaAuto", "rejilla NO actualizada: la entrega venia sin canales");
            return;
        }
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_LISTA_DRAMA, m3u == null ? "" : m3u.trim()).apply();
    }

    /** Unica via para vaciar la rejilla a proposito (boton quitar). */
    public static void limpiarDramaExtra(@NonNull Context contexto) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_LISTA_DRAMA, "").apply();
    }

    @NonNull
    public static String sesionDrama(@NonNull Context contexto) {
        String v = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE_SESION_DRAMA, "");
        return v == null ? "" : v;
    }

    public static void guardarSesionDrama(@NonNull Context contexto, @Nullable String json) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_SESION_DRAMA, json == null ? "" : json.trim()).apply();
        // v9.8: con la sesión recién capturada se bajan las cookies a disco y se
        // programa el refresco en segundo plano, que a partir de aquí ya no
        // necesita WebView.
        DramaRefresco.alGuardarSesion(contexto);
    }

    private static final String CLAVE_DRAMA_TS = "drama_refresco_ts";
    private static final String CLAVE_DRAMA_ESTADO = "drama_ultimo_estado";

    /** Momento (ms) del ultimo refresco/entrega DramaLive; 0 si nunca. */
    public static long dramaTs(@NonNull Context contexto) {
        return contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CLAVE_DRAMA_TS, 0L);
    }

    public static void guardarDramaTs(@NonNull Context contexto, long ms) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(CLAVE_DRAMA_TS, ms).apply();
    }

    /** Ultima frase que devolvio el captador WebView (exito o caducidad), para el sheet. */
    @NonNull
    public static String dramaEstado(@NonNull Context contexto) {
        String v = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE_DRAMA_ESTADO, "");
        return v == null ? "" : v;
    }

    public static void guardarDramaEstado(@NonNull Context contexto, @Nullable String s) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_DRAMA_ESTADO, s == null ? "" : s).apply();
    }

    private static final String CLAVE_LOGOS = "logos_reducidos";

    /** Pedir los logos recortados por el proxy (el truco del portal, ver {@link Logos}). */
    public static boolean logosReducidos(@NonNull Context contexto) {
        return contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE_LOGOS, false);
    }

    public static void setLogosReducidos(@NonNull Context contexto, boolean reducir) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(CLAVE_LOGOS, reducir).apply();
    }

    public static void guardarPegada(@NonNull Context contexto, @Nullable String texto) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLAVE_PEGADA, texto == null ? "" : texto.trim()).apply();
    }

    /**
     * URL final de la que se va a leer la lista. Con código FG se concatena sobre el
     * endpoint; si no hay nada guardado, se usa {@link #URL_POR_DEFECTO}.
     */
    @NonNull
    public String urlResuelta() {
        return resolver(url, codigo, endpoint, dramaKey);
    }

    /**
     * Armado de la URL, separado del objeto para poder probarlo sin Context.
     */
    @NonNull
    public static String resolver(@Nullable String url, @Nullable String codigo,
                                  @Nullable String endpoint) {
        return resolver(url, codigo, endpoint, null);
    }

    public static String resolver(@Nullable String url, @Nullable String codigo,
                                  @Nullable String endpoint, @Nullable String claveDrama) {
        if (!vacio(url)) {
            String u = url.trim();
            // Un enlace `player.html#<hash>` de Drama apunta a una pagina que cifra la URL
            // real; pero la URL interior responde un #EXTM3U vivo, asi que como FUENTE de
            // un solo canal vale: se desenrolla y se lee normal.
            String atajo = normalizarAtajoFg(u);
            if (atajo != null) return atajo;   // v3.2.17: «fgcode.org/232425» -> endpoint de la API
            String drama = EnlaceDrama.desenrollar(u, claveDrama);
            return drama != null ? drama : u;
        }
        if (!vacio(codigo)) {
            String c = codigo.trim();
            // Muchos proveedores dan la URL ya montada; que se pueda pegar ahí mismo.
            if (c.startsWith("http://") || c.startsWith("https://")) {
                String at = normalizarAtajoFg(c);          // v3.2.17
                return at != null ? at : c;
            }
            // `232425@fgcode.store` -> endpoint de ese host con el código.
            int arroba = c.indexOf('@');
            if (arroba > 0 && arroba < c.length() - 1) {
                String host = c.substring(arroba + 1).replaceFirst("^https?://", "").replaceAll("/+$", "");
                return "https://" + host + "/link/api.php/get?code=" + c.substring(0, arroba);
            }
            String base = vacio(endpoint) ? ENDPOINT_FG_POR_DEFECTO : endpoint.trim();
            // Un endpoint que termina en "=" ya trae el "code=" escrito: el valor va pegado.
            return base.endsWith("=") ? base + c : base + "?code=" + c;
        }
        return URL_POR_DEFECTO;
    }

    /**
     * v3.2.17: los proveedores tipo fgcode reparten «fgcode.org/232425», que es la pagina
     * bonita para humanos; lo que responde con la lista es el endpoint de la API del MISMO
     * host: /link/api.php/get?code=NNNN. Si el host huele a esa familia (contiene "fg") y el
     * path es solo el numero, se normaliza; cualquier otra URL queda intacta. Puro.
     */
    @Nullable
    static String normalizarAtajoFg(@NonNull String u) {
        String t = u.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^https?://([^/\\s]+?)/0*(\\d{3,12})/?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (!m.matches()) return null;
        String host = m.group(1).toLowerCase();
        if (!host.contains("fg")) return null;
        return "https://" + m.group(1) + "/link/api.php/get?code=" + m.group(2);
    }

    /** isEmpty de android.text.TextUtils no vale en los tests de JVM (está stubbeado). */
    static boolean vacio(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Texto corto para mostrar "de dónde vienen estos canales". */
    @NonNull
    public String comoTexto() {
        if (!vacio(pegada)) {
            int n = ListaPegada.contar(pegada);
            return "lista pegada" + (n > 0 ? " (" + n + ")" : "");
        }
        if (!vacio(codigo)) return "FG " + codigo.trim() + sufijoCabeceras();
        if (!vacio(url)) return url.trim() + sufijoCabeceras();
        return "por defecto" + sufijoCabeceras();
    }

    private String sufijoCabeceras() {
        int n = Encabezados.fromTexto(cabeceras).cuantas();
        return n > 0 ? "  ·  " + n + " cabecera" + (n == 1 ? "" : "s") : "";
    }

    // ─────────────────────────── descarga ───────────────────────────

    /** Descarga y parsea en un hilo de red; el resultado llega al hilo principal. */
    public void cargarAsync(@NonNull Oyente oyente) {
        final String destino = urlResuelta();
        if (TextUtils.isEmpty(destino)) {
            enMain(() -> oyente.onError("No hay fuente configurada.", false, 0));
            return;
        }
        Request.Builder rb = new Request.Builder()
                .url(destino)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Blabongo");
        Request peticion = conCabecerasDelUsuario(rb, encabezados().para(destino));
        cargarIntento(1, peticion, oyente);
    }

    /**
     * v3.2.17: la entrada por codigo FG moria al PRIMER timeout (3 s por salto / 25 s de
     * ventana en el reproductor y 12/25 s aqui, red movil lenta = «no me deja entrar, el
     * tiempo es muy corto»). Ahora hay 3 intentos con espera de 5 s y 15 s entre ellos,
     * tambien para respuestas 5xx del servidor (el store de fgcode suelta 500 transitorios
     * medidos 2026-10-10 desde el sandbox).
     */
    private void cargarIntento(final int intento, @NonNull final Request peticion,
                               @NonNull final Oyente oyente) {
        cliente().newCall(peticion).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (intento < 3) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> cargarIntento(intento + 1, peticion, oyente),
                            intento == 1 ? 5_000L : 15_000L);
                    return;
                }
                final String detalle = e.getMessage() == null ? e.toString() : e.getMessage();
                enMain(() -> oyente.onError("No se pudo conectar: " + detalle + " (3 intentos, ~1 min)", false, 0));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String cuerpo = response.body() != null ? response.body().string() : "";
                int codigoHttp = response.code();
                if (!response.isSuccessful()) {
                    if (codigoHttp >= 500 && intento < 3) {   // v3.2.17: 5xx = reintentar, no morir
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> cargarIntento(intento + 1, peticion, oyente),
                                intento == 1 ? 5_000L : 15_000L);
                        return;
                    }
                    // Se reenvía el código real: sin él, un 500 del servidor ajeno
                    // parece un fallo de la app.
                    enMain(() -> oyente.onError("El servidor respondió " + codigoHttp
                            + " — la fuente está caída o el código no existe.", true, codigoHttp));
                    return;
                }
                List<Canal> canales;
                String error = null;
                try {
                    canales = parsearCuerpo(cuerpo);
                } catch (Exception e) {
                    canales = new ArrayList<>();
                    error = "La respuesta no es ni JSON de canales ni M3U (" + e.getMessage() + ").";
                }
                final List<Canal> finales = canales;
                final String errorFinal = error;
                if (errorFinal != null) {
                    enMain(() -> oyente.onError(errorFinal, false, 0));
                    return;
                }
                if (finales.isEmpty()) {
                    enMain(() -> oyente.onError("La fuente no devolvió canales.", false, 0));
                    return;
                }
                enMain(() -> oyente.onCanales(finales));
            }
        });
    }

    /** Resultado de {@link #probarAsync}: lo que hay que ver antes de culpar a la app. */
    public static final class Prueba {
        public final int http;
        public final int bytes;
        public final int canales;
        @NonNull public final String detalle;

        Prueba(int http, int bytes, int canales, @NonNull String detalle) {
            this.http = http;
            this.bytes = bytes;
            this.canales = canales;
            this.detalle = detalle;
        }

        public boolean sirvio() {
            return canales > 0;
        }
    }

    public interface OyentePrueba {
        void resultado(@NonNull Prueba prueba);
    }

    /**
     * Descarga la fuente y dice QUÉ pasó, con el cuerpo de por medio: sirve para distinguir
     * "el código no existe" de "el servidor de códigos está caído" (ahorita mismo
     * fgcode.store responde 500 con su propia base de datos rechazando conexiones).
     */
    public static void probarAsync(@NonNull Context contexto, @NonNull final OyentePrueba oyente) {
        final String destino = desdePreferencias(contexto).urlResuelta();
        new Thread(() -> {
            int http = 0;
            String cuerpo = "";
            try {
                Request peticion = conCabecerasDelUsuario(new Request.Builder().url(destino)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) Blabongo"),
                        desdePreferencias(contexto).encabezados().para(destino));
                try (Response response = cliente().newCall(peticion).execute()) {
                    http = response.code();
                    cuerpo = response.body() != null ? response.body().string() : "";
                }
            } catch (Exception e) {
                final Prueba p = new Prueba(0, 0, 0, "no hay conexión: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
                enMain(() -> oyente.resultado(p));
                return;
            }
            int canales = 0;
            try {
                canales = parsearCuerpo(cuerpo).size();
            } catch (Exception ignorado) {
                // El diagnóstico se lo queda clasificarRespuesta().
            }
            final int total = canales;
            final String detalle = clasificarRespuesta(http, cuerpo);
            final Prueba p = new Prueba(http, cuerpo.length(), total, detalle);
            enMain(() -> oyente.resultado(p));
        }, "fuente-probar").start();
    }

    /** Valor de "Clave: ..." en un texto con etiquetas HTML dentro (los errores de PHP/Slim). */
    @NonNull
    private static String extractoDe(@NonNull String textoPlano, @NonNull String clave) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(clave) + "\\s*(.{1,90}?)(?=\\s+[A-Z]\\w*:\\s|$)",
                        java.util.regex.Pattern.DOTALL)
                .matcher(textoPlano);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * Describe el cuerpo de la respuesta, sin depender de la red (así se prueba en la JVM).
     * El orden importa: una lista buena puede llevar "404" dentro de una URL, así que M3U y
     * JSON se reconocen antes que los errores.
     */
    @NonNull
    public static String clasificarRespuesta(int http, @Nullable String cuerpo) {
        String c = cuerpo == null ? "" : cuerpo;
        String recortado = c.trim();
        if (recortado.isEmpty()) {
            return http == 0 ? "respuesta vacía" : "HTTP " + http + " sin cuerpo";
        }
        if (recortado.startsWith("#EXTM3U") || recortado.startsWith("#EXTINF")) return "M3U leída bien";
        if (recortado.startsWith("{") || recortado.startsWith("[")) return "JSON de canales";
        String plano = recortado.replaceAll("<[^>]*>", " ").trim();
        String bajo = plano.toLowerCase(java.util.Locale.ROOT);
        if (bajo.contains("slim application error") || bajo.contains("mysqli")
                || bajo.contains("connection refused") || bajo.contains("pdoexception")) {
            String mensaje = extractoDe(plano, "Message:");
            return "el servidor de códigos tiene su base de datos caída"
                    + (mensaje.isEmpty() ? "" : " (" + mensaje + ")") + "; no es un problema de la app";
        }
        if (bajo.startsWith("not found") || bajo.contains("404") || bajo.contains("403")) {
            return "el endpoint no existe o está cerrado para nosotros (" + http + ")";
        }
        if (recortado.startsWith("<")) {
            return "respondió HTML, no una lista (HTTP " + http + ")"
                    + (plano.isEmpty() ? "" : ": " + plano.substring(0, Math.min(60, plano.length())));
        }
        return "no parece una lista; empieza por \""
                + recortado.substring(0, Math.min(40, recortado.length())) + "\"";
    }

    /**
     * `https://host/x` -> `http://host/x` y al revés. Hay servidores de códigos FG que solo
     * atienden por http (fgcode.org, que además redirige a fgcode.store, que es https); si la
     * app pide el esquema equivocado el fallo parece "no conecta" con el endpoint bien puesto.
     */
    @Nullable
    public static String otroEsquema(@Nullable String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("https://")) return "http://" + u.substring(8);
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        return null;
    }

    /** true si el fallo es de conexión/TLS (merece probar el otro esquema), no de aplicación. */
    public static boolean esFalloDeConexion(@Nullable Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof javax.net.ssl.SSLException) return true;
            if (c instanceof java.net.ConnectException) return true;
            if (c instanceof java.net.SocketTimeoutException) return true;
            if (c instanceof java.net.NoRouteToHostException) return true;
            if (c instanceof java.io.EOFException) return true;
            if (c instanceof java.net.UnknownHostException) return false;   // el host no existe
            String m = c.getMessage();
            if (m != null && (m.startsWith("HTTP ") || m.contains("cleartext"))) return false;
        }
        return false;
    }

    /** Acepta el JSON histórico de la app (objeto o array) y M3U. */
    @NonNull
    public static List<Canal> parsearCuerpo(@Nullable String cuerpo) {
        List<Canal> vacio = new ArrayList<>();
        if (cuerpo == null) return vacio;
        String texto = cuerpo.trim();
        if (texto.isEmpty()) return vacio;

        if (M3uParser.pareceM3u(texto)) return M3uParser.parsear(texto);

        Gson gson = new Gson();
        try {
            Canal.RespuestaCanales respuesta = gson.fromJson(texto, Canal.RespuestaCanales.class);
            if (respuesta != null && respuesta.canales != null) {
                sanear(respuesta.canales);
                return respuesta.canales;
            }
        } catch (Exception ignorado) {
            // sigue el intento como array directo
        }
        List<Canal> canales = gson.fromJson(texto, new TypeToken<List<Canal>>() {}.getType());
        if (canales == null) return vacio;
        sanear(canales);
        return canales;
    }

    /** Gson puede dejar nulas las listas: el adapter asume que existen. */
    private static void sanear(@NonNull List<Canal> canales) {
        for (Canal canal : canales) {
            if (canal == null) continue;
            if (canal.options == null) canal.options = new ArrayList<>();
            if (canal.title == null) canal.title = "Canal";
            for (int i = 0; i < canal.options.size(); i++) {
                Canal.OpcionCanal opcion = canal.options.get(i);
                if (opcion != null && TextUtils.isEmpty(opcion.label)) {
                    opcion.label = canal.options.size() > 1 ? "Servidor " + (i + 1) : "Ver";
                }
            }
        }
    }

    static synchronized OkHttpClient cliente() {
        if (cliente == null) {
            // v3.2.17 (queja «el tiempo es muy corto», fgcode.org/232425): redes lentas del
            // movil no cabian en 12/25 s; se amplia y ademas cargarAsync reintenta 3x.
            cliente = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(75, TimeUnit.SECONDS)   // v3.2.18: ventana total >2 min por diseño
                    .followRedirects(true)
                    .build();
        }
        return cliente;
    }

    static void enMain(@NonNull Runnable accion) {
        if (Looper.myLooper() == Looper.getMainLooper()) accion.run();
        else new Handler(Looper.getMainLooper()).post(accion);
    }

    @NonNull
    private static String limpiar(@Nullable String valor) {
        return valor == null ? "" : valor.trim();
    }
}
