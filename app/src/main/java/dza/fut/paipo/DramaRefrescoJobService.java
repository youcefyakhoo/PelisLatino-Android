package dza.fut.paipo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Refresco de la lista de Drama sin abrir nada (v9.8).
 *
 * <p>Corre en el proceso de la app, en un hilo aparte, sin WebView y sin ventana:
 * lee la plantilla guardada y llama a {@link DramaSesion#extraer}, que habla con
 * la API con {@code HttpURLConnection} y las cookies del WebView.
 *
 * <p>El resultado se guarda con {@link FuenteCanales#guardarDramaExtra}, que tiene
 * una guarda importante: una entrega vacía NO pisa una lista que sí tenía canales.
 *
 * <p>Si falla, se deja el motivo en {@code drama_ultimo_estado} (lo enseña la hoja
 * "Fuente") y se avisa con una notificación, porque si no el usuario no se entera
 * de que la lista se quedó vieja. El caso típico es la sesión caducada, y eso no
 * se puede resolver desde un job: hay que volver a entrar a Drama a mano.
 */
public class DramaRefrescoJobService extends JobService {

    private static final String TAG = "DramaRefresco";
    private static final String CANAL_AVISOS = "drama_refresco";
    private static final int ID_AVISO = 7301;

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean ok = false;
            String estado;
            try {
                String sesion = FuenteCanales.sesionDrama(this);
                if (sesion.isEmpty()) {
                    estado = "sin sesión de Drama guardada";
                } else {
                    DramaSesion.Resultado r = DramaSesion.extraer(sesion, null);
                    if (r.ok() && r.m3u != null) {
                        FuenteCanales.guardarDramaExtra(this, r.m3u);
                        FuenteCanales.guardarDramaTs(this, System.currentTimeMillis());
                        estado = "refresco en segundo plano: " + r.canales + " canales";
                        ok = true;
                    } else {
                        estado = "no se pudo refrescar: " + (r.error == null ? "motivo desconocido" : r.error);
                    }
                }
            } catch (Throwable t) {
                estado = "no se pudo refrescar: " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " " + t.getMessage());
            }

            FuenteCanales.guardarDramaEstado(this, estado);
            Log.i(TAG, estado);

            if (!ok) {
                // Con la sesión caducada la lista se queda como estaba: es mejor
                // vieja que vacía. Se avisa para que el usuario vuelva a entrar.
                avisar(estado);
            }
            // Si falló, se le pide al sistema un reintento.
            jobFinished(params, !ok);
        }, "drama-refresco").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // true: si el sistema corta el job (sin red, poca batería), que lo repita.
        return true;
    }

    private void avisar(String texto) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        CANAL_AVISOS, "Refresco de canales", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification n = new NotificationCompat.Builder(this, CANAL_AVISOS)
                    .setSmallIcon(R.drawable.ic_live_tv)
                    .setContentTitle("La lista de Drama no se pudo refrescar")
                    .setContentText(texto)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID_AVISO, n);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo avisar", t);
        }
    }
}
