package dza.fut.paipo;

import java.io.Serializable;
import java.util.List;

public class Canal implements Serializable {
    // Se recomienda agregar serialVersionUID para evitar advertencias
    private static final long serialVersionUID = 1L;

    public String title;
    public String image;
    public String category;
    /**
     * País del canal, en código ISO de dos letras mayúsculas (``PE``).
     *
     * <p>Sale del {@code tvg-id} de los índices públicos, que lo lleva pegado al
     * nombre: {@code tvg-id="36TVHuacho.pe@SD"} → {@code PE}. Drama Live organiza
     * su tira con géneros <b>y países</b>, y el {@code group-title} solo da el
     * género; sin esto no había forma de agrupar por país. Nulo si la lista no
     * lo declara.
     */
    public String pais;
    /**
     * Calidad declarada en el propio nombre de la lista (``36 TV Huacho (720p)``).
     * La sacamos del título para que el tile no se llame "X (720p)" y la usamos como
     * etiqueta del servidor. No viene en el JSON de la app: se queda nula ahí.
     */
    public String calidad;
    public List<OpcionCanal> options;

    public static class OpcionCanal implements Serializable {
        private static final long serialVersionUID = 1L;
        public String label;
        public String url;
    }

    public static class RespuestaCanales {
        public List<Canal> canales;
    }
}