package dza.fut.paipo;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.navigation.NavigationView;
import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Main extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private static final String URL_CONFIG = "https://raw.githubusercontent.com/belkaperu/json/main/config_app.json";

    private long downloadID;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    /** Sección visible: la barra lleva su nombre, como el Drama Live real. */
    private int seccionId = R.id.nav_sports_agenda;
    private static final String CLAVE_SECCION = "seccion_visible";

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15,    java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final ActivityResultLauncher<String> permisoNotifLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this,
                            "Sin permiso de notificaciones no recibirás alertas de partidos",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v9.4: la publicidad se quitó entera (Start.io: inicialización, banner y
        // los MREC intercalados en Canales y Agenda). Ya no hay SDK que inicializar.

        // v9.7: modo TV. En un televisor de verdad lo detecta Ui por el modo de
        // interfaz; este extra solo sirve para poder probarlo en un teléfono.
        Ui.forzarTelevision(getIntent() != null
                && getIntent().getBooleanExtra("modo_tv", false));

        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        Toolbar toolbar = findViewById(R.id.toolbar);

        setSupportActionBar(toolbar);
        // v9.6: la barra lleva el NOMBRE DE LA SECCIÓN, como el Drama Live real
        // (☰ Sports / Kids / Most watched + 🔍 + ⟳), no la marca. Antes decía
        // "PelisLatinoHD" y debajo había otra barra con la pantalla de turno: dos
        // barras donde Drama gasta una. El título lo pone loadFragment().

        if (Ui.esTelevision(this)) {
            // v9.7 (TV): sin drawer y sin hamburguesa. El ActionBarDrawerToggle es
            // justo lo que vuelve a pintar la hamburguesa, así que en TV ni se crea.
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }
        } else {
            ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                    this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
            drawerLayout.addDrawerListener(toggle);
            toggle.syncState();
        }

        navigationView.setNavigationItemSelectedListener(this);

        // En TV hay raíl; el drawer y la hamburguesa sobran.
        montarRailTv();
        aplicarOverscanTv();

        // v9.8: si hay una sesión de Drama guardada, que el refresco de la lista
        // siga programado (idempotente: si ya lo está, no hace nada).
        DramaRefresco.programar(this);

        // v9.6: la sección sobrevive a la recreación (girar el teléfono, cambiar de
        // tema). Sin esto la barra perdía el nombre y volvía al label de la app.
        if (savedInstanceState != null) {
            seccionId = savedInstanceState.getInt(CLAVE_SECCION, R.id.nav_sports_agenda);
        }
        aplicarTitulo();

        if (savedInstanceState == null) {
            loadFragment(new AgendaDeportivaFragment(), R.id.nav_sports_agenda);
        }

        solicitarPermisoNotificaciones();
        hideSystemUI();
        verificarActualizacion();   // <-- verificación de actualización unificada
    }

    /**
     * Monta el raíl de navegación de Android TV (v9.7).
     *
     * <p>En un televisor, abrir un drawer a pantalla completa se siente mal y
     * obliga a un viaje de foco de ida y vuelta. Se pone un raíl fijo a la
     * izquierda y se quitan el drawer y la hamburguesa.
     *
     * <p>Las filas salen del MISMO menú que el drawer
     * ({@code res/menu/activity_main_drawer.xml}), para que no haya dos listas que
     * se puedan desincronizar. En un teléfono esto no hace nada: el raíl se queda
     * GONE y todo sigue igual.
     */
    private void montarRailTv() {
        if (!Ui.esTelevision(this)) return;
        LinearLayout destino = findViewById(R.id.rail_opciones);
        if (destino == null) return;

        View rail = findViewById(R.id.rail_tv);
        if (rail != null) rail.setVisibility(View.VISIBLE);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
        if (drawerLayout != null) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < navigationView.getMenu().size(); i++) {
            MenuItem item = navigationView.getMenu().getItem(i);
            if (!item.isVisible()) continue;
            View fila = inflater.inflate(R.layout.item_rail_tv, destino, false);
            ImageView icono = fila.findViewById(R.id.imgRailIcono);
            TextView titulo = fila.findViewById(R.id.txtRailTitulo);
            if (item.getIcon() != null) icono.setImageDrawable(item.getIcon());
            titulo.setText(item.getTitle());
            fila.setTag(item.getItemId());
            fila.setOnClickListener(v -> {
                Object id = v.getTag();
                if (!(id instanceof Integer)) return;
                MenuItem elegido = navigationView.getMenu().findItem((Integer) id);
                if (elegido != null) onNavigationItemSelected(elegido);
            });
            destino.addView(fila);
        }
        marcarSeccionEnRail();
    }

    /** Marca en el raíl la sección activa (setSelected → bloque azul). */
    private void marcarSeccionEnRail() {
        LinearLayout destino = findViewById(R.id.rail_opciones);
        if (destino == null) return;
        for (int i = 0; i < destino.getChildCount(); i++) {
            View fila = destino.getChildAt(i);
            Object tag = fila.getTag();
            fila.setSelected(tag instanceof Integer && (Integer) tag == seccionId);
        }
    }

    /**
     * Aire en los bordes en TV.
     *
     * <p>Muchos televisores recortan la señal (overscan) y se comen los extremos:
     * sin este margen, la barra y los rótulos del borde se cortan. En el teléfono
     * no se toca nada.
     */
    private void aplicarOverscanTv() {
        if (!Ui.esTelevision(this)) return;
        // OJO: se aplica a la vista INTERNA, no al DrawerLayout. El DrawerLayout
        // lleva fitsSystemWindows y ponerle padding a mano pelea con el reparto de
        // insets: se vio en la captura tv-rail2, con todo desplazado a la izquierda
        // y el título "Agenda deportiva" cortado por la "A".
        ViewGroup raiz = findViewById(R.id.raiz_tv);
        if (raiz == null) return;
        int margen = Math.round(24 * getResources().getDisplayMetrics().density);
        raiz.setPadding(margen, margen, margen, margen);
    }

    /**
     * Pinta en la barra el nombre de la sección visible.
     *
     * <p>v9.6: hacía falta en DOS caminos. Si solo se pusiera al navegar, al girar
     * el teléfono la actividad se recrea, {@code loadFragment} no corre (hay
     * savedInstanceState) y la barra volvía a decir "BLABONGO" — el label de la
     * app. Se vio en la captura en horizontal.
     */
    private void aplicarTitulo() {
        if (getSupportActionBar() == null) return;
        CharSequence titulo = null;
        if (navigationView != null && navigationView.getMenu() != null) {
            MenuItem item = navigationView.getMenu().findItem(seccionId);
            if (item != null) titulo = item.getTitle();
        }
        getSupportActionBar().setTitle(titulo != null ? titulo : getString(R.string.app_name));
        marcarSeccionEnRail();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CLAVE_SECCION, seccionId);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_live_channels) {
            loadFragment(new CanalesEnVivoFragment(), id);
        } else if (id == R.id.nav_sports_agenda) {
            loadFragment(new AgendaDeportivaFragment(), id);
        } else if (id == R.id.nav_pelis) {
            loadFragment(new PelisFragment(), id);
        }
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    private void loadFragment(Fragment fragment, int menuItemId) {
        // El título de la barra es el de la sección elegida en el menú lateral.
        seccionId = menuItemId;
        aplicarTitulo();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        // commit() lanza IllegalStateException si el toque del menú llega justo cuando la
        // activity ya guardó estado (bajar la cortina, bloquear la pantalla con el drawer
        // abierto): se cerraba la app al entrar en Canales.
        transaction.commitAllowingStateLoss();
        navigationView.setCheckedItem(menuItemId);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    // ======================================================================
    // ACTUALIZACIÓN (ESTRATEGIA EXACTA DEL PRIMER MAIN)
    // ======================================================================
    private void verificarActualizacion() {
        Request request = new Request.Builder()
                .url(URL_CONFIG)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // Si falla el internet, no hacemos nada
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    ConfigApp config = new Gson().fromJson(json, ConfigApp.class);

                    runOnUiThread(() -> {
                        if (config != null) {
                            int versionActual = obtenerVersionCode();
                            if (config.version_android > versionActual) {
                                evaluarForzadoYMostrar(config);
                            }
                        }
                    });
                }
            }
        });
    }

    private int obtenerVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1; // Valor por defecto
        }
    }

    private void evaluarForzadoYMostrar(ConfigApp config) {
        SharedPreferences prefs = getSharedPreferences("AppConfig", MODE_PRIVATE);
        int versionGuardada = prefs.getInt("version_detectada", -1);
        long tiempoPrimeraDeteccion = prefs.getLong("tiempo_primera_deteccion", 0);
        long tiempoActual = System.currentTimeMillis();

        if (versionGuardada != config.version_android) {
            prefs.edit()
                    .putInt("version_detectada", config.version_android)
                    .putLong("tiempo_primera_deteccion", tiempoActual)
                    .apply();
            tiempoPrimeraDeteccion = tiempoActual;
        }

        long tiempoTranscurrido = tiempoActual - tiempoPrimeraDeteccion;
        long veinticuatroHoras = 24 * 60 * 60 * 1000L;

        boolean esForzado = tiempoTranscurrido >= veinticuatroHoras;
        mostrarDialogoUpdate(config.link_actualizacion, esForzado);
    }

    private void mostrarDialogoUpdate(String urlApk, boolean esForzado) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        if (esForzado) {
            builder.setTitle("¡Actualización Obligatoria!");
            builder.setMessage("El período de gracia de 24 horas ha terminado. Es necesario instalar la nueva versión para continuar.");
            builder.setCancelable(false);
        } else {
            builder.setTitle("Nueva Actualización Disponible");
            builder.setMessage("Hay mejoras listas para la aplicación. Se volverá obligatoria en un plazo de 24 horas.");
            builder.setCancelable(true);
            builder.setNegativeButton("Más tarde", (dialog, which) -> dialog.dismiss());
        }

        builder.setPositiveButton("Actualizar Ahora", (dialog, which) -> {
            descargarEInstalarApk(urlApk, esForzado);
        });

        builder.show();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void descargarEInstalarApk(String urlApk, boolean esForzado) {
        Toast.makeText(this, "Iniciando descarga en segundo plano...", Toast.LENGTH_LONG).show();

        String nombreArchivo = "actualizacion_agenda.apk";

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(urlApk));
        request.setTitle("Actualizando App");
        request.setDescription("Descargando el archivo de instalación...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombreArchivo);

        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            downloadID = manager.enqueue(request);
        }

        BroadcastReceiver onDownloadComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (downloadID == id) {
                    ejecutarInstalador(nombreArchivo);
                    unregisterReceiver(this);

                    if (esForzado) {
                        finish();  // Cierra la app para forzar la instalación
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void ejecutarInstalador(String nombreArchivo) {
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), nombreArchivo);

        if (file.exists()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");

            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo abrir el instalador.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ──────────────────── PERMISOS Y UI ────────────────────
    private void solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permisoNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctrl =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
        ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ──────────────────── MODELO CONFIG APP ────────────────────
    public static class ConfigApp {
        public int    version_android;
        public String link_actualizacion;
    }
}