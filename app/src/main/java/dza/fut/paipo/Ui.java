package dza.fut.paipo;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
}
