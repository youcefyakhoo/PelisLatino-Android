package dza.fut.paipo;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;

/**
 * Refresco silencioso de la lista de Drama (v9.8).
 *
 * <p><b>Por qué se puede hacer en segundo plano.</b> El captador del WebView
 * ({@code DramaAutoActivity}) solo hace falta UNA vez: de ahí salen la plantilla
 * ({@code glt} con su {@code user_id}) y las cookies. A partir de ahí
 * {@link DramaSesion#extraer} hace todo el flujo glt→glbt con
 * {@code HttpURLConnection} en Java puro, reusando las cookies del WebView. No
 * hace falta ni WebView ni pantalla.
 *
 * <p><b>Qué NO puede hacer.</b> Cuando la sesión caduca (Drama contesta que no
 * reconoce el {@code user_id}) no hay forma de renovarla sin abrir la web otra
 * vez: no se puede resolver un login desde un job. En ese caso el servicio deja
 * el motivo escrito en {@code drama_ultimo_estado} y avisa con una notificación.
 *
 * <p>Se usa {@link JobScheduler}, que viene en el sistema desde API 21: nada de
 * añadir WorkManager ni otra dependencia.
 */
final class DramaRefresco {

    private static final String TAG = "DramaRefresco";

    /** Job periódico. El id es fijo: uno solo por app. */
    static final int JOB_PERIODICO = 8721;
    /** Job suelto, para "actualizar ahora". */
    static final int JOB_AHORA = 8722;
    /** Job suelto que reintenta tras un fallo. */
    static final int JOB_REINTENTO = 8723;

    /**
     * Cada cuánto se refresca solo. 6 h es de sobra: la lista de canales de un
     * servicio de TV no cambia cada minuto, y cada refresco son ~20 peticiones.
     */
    private static final long CADA_MS = 6L * 60L * 60L * 1000L;

    private DramaRefresco() {
    }

    /** true si hay una plantilla guardada con la que intentar el refresco. */
    static boolean haySesion(@Nullable Context contexto) {
        return contexto != null && !FuenteCanales.sesionDrama(contexto).isEmpty();
    }

    /**
     * Se llama justo después de guardar una sesión nueva.
     *
     * <p>Hace DOS cosas que no son obvias:
     * <ol>
     *   <li>{@code CookieManager.flush()}: el WebView guarda las cookies en
     *       memoria y solo las baja a disco al hacer flush. Sin esto, el job —que
     *       corre en otro momento, incluso tras reiniciar el teléfono— no vería
     *       ninguna cookie y todas las peticiones irían sin sesión.</li>
     *   <li>Programa el periódico, si no lo estaba ya.</li>
     * </ol>
     */
    static void alGuardarSesion(@Nullable Context contexto) {
        if (contexto == null) return;
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable t) {
            Log.w(TAG, "no se pudieron bajar las cookies a disco", t);
        }
        programar(contexto);
    }

    /** Deja programado el refresco periódico. Es idempotente. */
    static void programar(@Nullable Context contexto) {
        if (contexto == null) return;
        Context ctx = contexto.getApplicationContext();
        if (!haySesion(ctx)) return;
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_PERIODICO) != null) return;
            JobInfo job = new JobInfo.Builder(JOB_PERIODICO,
                    new ComponentName(ctx, DramaRefrescoJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(CADA_MS)
                    // setPersisted sobrevive a un reinicio del teléfono; exige el
                    // permiso RECEIVE_BOOT_COMPLETED en el manifiesto.
                    .setPersisted(true)
                    .build();
            js.schedule(job);
            Log.i(TAG, "refresco periódico programado cada " + (CADA_MS / 3600000L) + " h");
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo programar el refresco", t);
        }
    }

    /** Pide un refresco ya, una sola vez. */
    static void ahora(@Nullable Context contexto, int idJob) {
        if (contexto == null) return;
        Context ctx = contexto.getApplicationContext();
        if (!haySesion(ctx)) return;
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(idJob,
                    new ComponentName(ctx, DramaRefrescoJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    // Corre en cuanto el sistema tenga red, sin esperar.
                    .setOverrideDeadline(1000)
                    .build();
            js.schedule(job);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo pedir el refresco inmediato", t);
        }
    }

    static void cancelar(@Nullable Context contexto) {
        if (contexto == null) return;
        try {
            JobScheduler js = (JobScheduler) contexto.getApplicationContext()
                    .getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancelAll();
        } catch (Throwable ignored) {
        }
    }
}
