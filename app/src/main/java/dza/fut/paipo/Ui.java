package dza.fut.paipo;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * En qué tipo de aparato está corriendo la app.
 *
 * <p>La misma app va a teléfono y a Android TV. Casi todo se adapta solo porque
 * las medidas están en dp y la rejilla calcula las columnas según el ancho, pero
 * hay dos cosas que no: el FOCO (en un mando no hay dedo que señale) y el arranque
 * (en TV nadie tiene el foco hasta que algo lo pide, así que el mando no hace
 * nada).
 */
final class Ui {

    private Ui() {
    }

    /**
     * Permite probar el modo TV en un teléfono, sin un televisor a mano.
     *
     * <p>Lo enciende {@code Main} con el extra de intent {@code modo_tv}:
     * <pre>adb shell am start -n dza.fut.paipo/.Main --ez modo_tv true</pre>
     * Un lanzador normal nunca manda ese extra, así que en producción no hace nada.
     */
    private static boolean forzarTelevision;

    static void forzarTelevision(boolean si) {
        forzarTelevision = si;
    }

    /**
     * true en Android TV / Google TV.
     *
     * <p>Se mira el modo de interfaz, que es lo que informa el sistema en un
     * aparato sin pantalla táctil que se maneja con mando y a distancia. El
     * feature LEANBACK queda como respaldo para los televisores que no lo
     * declaran en el modo.
     */
    static boolean esTelevision(@Nullable Context contexto) {
        if (forzarTelevision) return true;
        if (contexto == null) return false;
        try {
            Configuration cfg = contexto.getResources().getConfiguration();
            int modo = cfg.uiMode & Configuration.UI_MODE_TYPE_MASK;
            if (modo == Configuration.UI_MODE_TYPE_TELEVISION) return true;
            return contexto.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Nombre del aparato para los mensajes de diagnóstico. */
    @NonNull
    static String tipoDeAparato(@Nullable Context contexto) {
        return esTelevision(contexto) ? "television" : "telefono";
    }

    /**
     * Bandera del país como par de indicadores regionales: {@code "PE"} → 🇵🇪.
     *
     * <p>Se usa el emoji y no una imagen por tres razones: ya viene en la fuente
     * del sistema (no hay que empaquetar 250 SVG ni pedirlas por red), se ve igual
     * en teléfono y en TV, y no necesita permisos ni red. Si el aparato no tuviera
     * fuente de emoji, el peor caso es que se vean las dos letras.
     *
     * <p>Devuelve "" si el código no son dos letras, para no pintar basura.
     */
    @NonNull
    static String banderaEmoji(@Nullable String iso) {
        if (iso == null || iso.length() != 2) return "";
        String codigo = iso.toUpperCase(Locale.ROOT);
        char a = codigo.charAt(0);
        char b = codigo.charAt(1);
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "";
        return new String(Character.toChars(0x1F1E6 + (a - 'A')))
                + new String(Character.toChars(0x1F1E6 + (b - 'A')));
    }
}
