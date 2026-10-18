package dza.fut.paipo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.drawable.Drawable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;

public class CanalAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<Object> items;   // solo Canal: la publicidad se quitó entera (v9.4)
    private final Context context;

    /**
     * Índice del tile seleccionado. DramaLive no pinta el tile activo con un relleno
     * fuerte: le dibuja un anillo de acento, así que aquí basta con propagar el estado
     * android:stateActivated al MaterialCardView (ver color/pelis_tile_stroke.xml).
     */
    private int posicionActiva = RecyclerView.NO_POSITION;

    /**
     * Con catálogos enormes, pedir el logo crudo (PNG de 200 KB para un tile de 80 dp)
     * es lo que hace que la rejilla tarde una eternidad. {@link Logos} hace lo mismo que
     * el cliente web de DramaLive: lo pide recortado y en WebP por images.weserv.nl.
     * Apagado salvo que el usuario lo encienda en la hoja "Fuente".
     */
    private boolean reducirLogos;

    public void setReducirLogos(boolean reducir) {
        if (reducirLogos == reducir) return;
        reducirLogos = reducir;
        notifyDataSetChanged();
    }

    public CanalAdapter(List<Object> items, Context context) {
        this.items = items;
        this.context = context;
    }

    /**
     * El fragmento la usa al filtrar por tópico o al escribir en la búsqueda. Un
     * re-filtro cambia la lista entera (y la posición de los anuncios), así que
     * aquí notifyItemRangeChanged sería exactamente lo mismo pero más verboso.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void actualizarLista(List<Object> nuevosItems) {
        this.items = nuevosItems;
        this.posicionActiva = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_canal, parent, false);
        return new CanalViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof CanalViewHolder) {
            Canal canal = (Canal) items.get(position);
            CanalViewHolder cHolder = (CanalViewHolder) holder;
            cHolder.txtNombre.setText(canal.title);
            cHolder.itemView.setActivated(position == posicionActiva);
            if (TextUtils.isEmpty(canal.image)) {
                // v3.2.32: adios al hueco gris — losa de iniciales con gradiente (DramaLive
                // nunca muestra un tile vacio; el icono pálido vivia muriendo de vergüenza).
                cHolder.imgCanal.setImageDrawable(Logos.avatar(context, canal.title));
            } else {
                String logo = reducirLogos ? Logos.reducido(canal.image, Logos.ANCHO_TILE) : canal.image;
                // v3.2.33: SIN centerCropTransform — la imagen entra ENTERA (fitCenter en la
                // vista, downsampling de Glide al tamano real del tile). El avatar hace de
                // placeholder/error: mientras carga o si el logo muere, la losa sigue llena.
                Drawable av = Logos.avatar(context, canal.title);
                Glide.with(context).load(logo).apply(new RequestOptions()
                        .placeholder(av).error(av)).into(cHolder.imgCanal);
            }
            cHolder.itemView.setOnClickListener(v -> {
                marcarComoActivo(holder.getBindingAdapterPosition());
                mostrarOpcionesCanal(canal);
            });
        }
    }

    private void marcarComoActivo(int posicion) {
        if (posicion == posicionActiva || posicion == RecyclerView.NO_POSITION) return;
        int anterior = posicionActiva;
        posicionActiva = posicion;
        if (anterior != RecyclerView.NO_POSITION) notifyItemChanged(anterior);
        notifyItemChanged(posicionActiva);
    }

    private void mostrarOpcionesCanal(Canal canal) {
        if (canal.options == null || canal.options.isEmpty()) {
            Toast.makeText(context, "No hay opciones disponibles", Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetOpcionesFragment bottomSheet = BottomSheetOpcionesFragment.newInstance(canal);

        // El contexto debe ser una FragmentActivity para obtener el FragmentManager
        if (context instanceof androidx.appcompat.app.AppCompatActivity) {
            bottomSheet.show(((androidx.appcompat.app.AppCompatActivity) context).getSupportFragmentManager(),
                    "opciones_canal");
        } else if (context instanceof androidx.fragment.app.FragmentActivity) {
            bottomSheet.show(((androidx.fragment.app.FragmentActivity) context).getSupportFragmentManager(),
                    "opciones_canal");
        } else {
            Toast.makeText(context, "Error al mostrar opciones", Toast.LENGTH_SHORT).show();
        }
    }

    @OptIn(markerClass = UnstableApi.class) private void lanzarReproductor(String url) {
        String urlFinal = normalizarUrlCanal(url);
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra("url_iframe_inicial", urlFinal);
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private String extraerToken(String url) {
        if (url.contains("?r=")) return url.substring(url.indexOf("?r=") + 3);
        return url;
    }

    /**
     * El JSON de canales no entrega URLs completas, sino rutas como esta:
     *
     *   /p/foxrepro1.html?r=https://belkaperu.github.io/belkafut/repron.html?r=<embed>
     *
     * Dos problemas:
     *   1) es una ruta RELATIVA: ni el WebView ni OkHttp saben abrirla.
     *   2) el envoltorio "/p/*.html" YA NO EXISTE en el servidor del proveedor
     *      (responde 404 aunque se le ponga la base delante), asi que tampoco
     *      vale con completarla.
     *
     * Lo que si responde 200 es saltarse el envoltorio y abrir directamente la
     * URL interna que va despues de "?r=", que siempre es absoluta.
     */
    String normalizarUrlCanal(String url) {
        return normalizarUrlCanal(url, FuenteCanales.dramaKey(context));
    }

    /** Version pura (sin Android) para el test JVM. */
    static String normalizarUrlCanal(String url, @Nullable String claveDrama) {
        if (url == null) return null;
        String u = url.trim();
        // v3.2.5: el esquema propio del app NUNCA lleva prefijos: es la instruccion de
        // resolver glbr al reproducir (PlayerActivity lo entiende). Antes caia en el
        // "ultimo recurso" y salia corrupto con https://belkaperu.github.io delante.
        if (u.startsWith("dramalive://")) return u;
        if (u.startsWith("http")) {
            // enlace cifrado tipo Drama -> la URL de adentro, si se puede abrir
            String drama = EnlaceDrama.desenrollar(u, claveDrama);
            return drama != null ? drama : u;
        }
        int i = u.indexOf("?r=");
        if (i >= 0) {
            String interna = u.substring(i + 3).trim();
            if (interna.startsWith("http")) return interna;
        }
        // Ultimo recurso: se le pone la base del sitio del listado.
        return "https://belkaperu.github.io" + u;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ─── ViewHolders ───
    public static class CanalViewHolder extends RecyclerView.ViewHolder {
        ImageView imgCanal;
        TextView txtNombre;
        public CanalViewHolder(View itemView) {
            super(itemView);
            imgCanal = itemView.findViewById(R.id.imgCanal);
            txtNombre = itemView.findViewById(R.id.txtNombreCanal);
        }
    }
}