package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El parser tiene que aguantar el formato real de iptv-org, que es lo que alimenta el
 * modo automático: calidades escritas dentro del nombre, grupos compuestos con ';' y
 * el mismo canal repetido una vez por cada resolución.
 */
public class M3uParserTest {

    private static final String M3U =
            "#EXTM3U x-tvg-url=\"https://example.com/epg.xml\"\n"
            + "#EXTINF:-1 tvg-id=\"36TVHuacho.pe@SD\" tvg-logo=\"https://ex.it/36.png\" group-title=\"General\",36 TV Huacho (720p)\n"
            + "https://cdn.example.pe/36tv/playlist.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"36TVHuacho.pe@HD\" tvg-logo=\"https://ex.it/36.png\" group-title=\"General\",36 TV Huacho (1080p)\n"
            + "https://cdn.example.pe/36tv-hd/playlist.m3u8\n"
            + "#EXTINF:-1 tvg-logo=\"https://ex.it/g.png\" group-title=\"Kids;Sports\",Golden\n"
            + "https://cdn.example.pe/golden/index.m3u8\n"
            + "#EXTINF:-1 group-title=\"Undefined\",Sin Clasificar\n"
            + "https://cdn.example.pe/sin/index.m3u8\n"
            + "#EXTVLCOPT:network-caching=1000\n"
            + "#EXTINF:-1,no-url-after-me\n"
            + "esto-no-es-una-url\n";

    @Test
    public void detectaListasYGuardaSoloEntradasConUrl() {
        assertTrue(M3uParser.pareceM3u(M3U));
        assertFalse(M3uParser.pareceM3u("{\"canales\":[]}"));

        List<Canal> canales = M3uParser.parsear(M3U);
        // 3 tiles: las dos resoluciones de 36 TV Huacho se fusionan, y la entrada sin
        // enlace se descarta en vez de inventarse un canal vacío.
        assertEquals(3, canales.size());
    }

    @Test
    public void laCalidadPasaASerEtiquetaDelServidorYNoParteElNombre() {
        List<Canal> canales = M3uParser.parsear(M3U);
        Canal primero = canales.get(0);

        assertEquals("36 TV Huacho", primero.title);
        // Las dos resoluciones del mismo canal son dos servidores de un solo tile,
        // no dos tiles idénticos (así lo muestra DramaLive con "Servers").
        assertEquals(2, primero.options.size());
        assertEquals("720p", primero.options.get(0).label);
        assertEquals("1080p", primero.options.get(1).label);
        assertTrue(primero.options.get(0).url.endsWith("/36tv/playlist.m3u8"));
    }

    @Test
    public void tomaElPrimerGrupoRealYdescartaRelleno() {
        List<Canal> canales = M3uParser.parsear(M3U);
        assertEquals("General", canales.get(0).category);
        assertEquals("Kids", canales.get(1).category);
        assertEquals("", canales.get(2).category);

        // iptv-org pone el grupo PRINCIPAL primero (Outdoor;Sports => canal de "Outdoor"
        // que además aparece en el índice de deportes): el tab usa el primero.
        assertEquals("Outdoor", M3uParser.primerGrupo("Outdoor;Sports"));
        assertEquals("", M3uParser.primerGrupo("Undefined"));
        assertEquals("", M3uParser.primerGrupo(null));
    }

    @Test
    public void usaElNombreDelArchivoCuandoNoHayExtinf() {
        List<Canal> canales = M3uParser.parsear(
                "#EXTM3U\nhttps://cdn.example.pe/otro-canal.m3u8\n");
        assertEquals(1, canales.size());
        assertEquals("otro canal", canales.get(0).title);
    }

    /**
     * Mismo parser, pero contra 40 entradas REALES de la lista de país de iptv-org
     * (recorte commiteado en {@code src/test/resources/pe-mini.m3u}, sin red en el test).
     * Es la forma de asegurar que el modo automático no pinta una rejilla con nombres
     * tipo "Señal XYZ (720p)" ni pestañas "Kids;Sports".
     */
    @Test
    public void parseaLaListaRealDePaisSinBasuraEnNombresYNiGruposCompuestos() throws Exception {
        String m3u;
        try (java.io.InputStream in = getClass().getResourceAsStream("/pe-mini.m3u")) {
            assertNotNull("falta el fixture /pe-mini.m3u", in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            m3u = out.toString("UTF-8");
        }

        java.util.List<Canal> canales = M3uParser.parsear(m3u);
        assertTrue("la lista real debería dar decenas de canales, no " + canales.size(),
                canales.size() >= 35);

        for (Canal canal : canales) {
            assertFalse("el título no debería conservar la calidad: " + canal.title,
                    canal.title.matches(".*\\(\\s*\\d{3,4}p\\s*\\)\\s*$"));
            assertTrue("sin enlace no hay canal: " + canal.title,
                    canal.options != null && !canal.options.isEmpty());
            assertTrue("URL rara: " + canal.options.get(0).url,
                    canal.options.get(0).url.startsWith("http"));
            assertFalse("grupo compuesto sin partir: " + canal.category,
                    canal.category.contains(";"));
        }

        boolean hayCalidadComoServidor = false;
        for (Canal canal : canales) {
            if (canal.options.get(0).label.matches("\\d{3,4}p")) hayCalidadComoServidor = true;
        }
        assertTrue("la calidad debería aterrizar como etiqueta del servidor", hayCalidadComoServidor);
    }

    // ── pestañas con catálogos gigantes ─────────────────────────────────────

    @Test
    public void lasPestanasSonLosGruposMasPobladosNoLosPrimeros() {
        java.util.Map<String, Integer> conteo = new java.util.LinkedHashMap<>();
        conteo.put("Legislative", 3);
        conteo.put("General", 900);
        conteo.put("Religious", 7);
        conteo.put("Music", 120);
        conteo.put("News", 340);

        java.util.List<String> nombres = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : AutoCanales.gruposPrincipales(conteo, 3)) {
            nombres.add(e.getKey());
        }
        assertEquals(java.util.Arrays.asList("General", "News", "Music"), nombres);
    }

    /**
     * v9.1: los grupos primero (de más canales a menos) y los PAÍSES al final, en
     * alfabético. Es la estructura de la tira de web.dramalive.cc: sus grupos son
     * Alwan, Shahid, Sports, Arabic Entertainment… y luego vienen Syria, Lebanon,
     * Saudi Arabia, Egypt, Jordan, Qatar, Iraq, Kuwait, ordenados por nombre.
     */
    @Test
    public void losPaisesVanAlFinalYAlfabeticos() {
        Map<String, Integer> conteo = new LinkedHashMap<>();
        conteo.put("Peru", 50);
        conteo.put("News", 300);
        conteo.put("Algeria", 10);
        conteo.put("Sports", 200);
        conteo.put("Syria", 5);

        java.util.List<String> orden = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : AutoCanales.gruposPrincipales(conteo, Integer.MAX_VALUE)) {
            orden.add(e.getKey());
        }
        assertEquals(java.util.Arrays.asList("News", "Sports", "Algeria", "Peru", "Syria"), orden);
    }

    @Test
    public void reconoceNombresDePaisYNoGeneros() {
        assertTrue(AutoCanales.esPais("Peru"));
        assertTrue(AutoCanales.esPais("Algeria"));
        assertTrue(AutoCanales.esPais("Saudi Arabia"));
        assertTrue(AutoCanales.esPais("  peru  "));   // con espacios y en minúsculas
        assertFalse(AutoCanales.esPais("News"));
        assertFalse(AutoCanales.esPais("Sports"));
        assertFalse(AutoCanales.esPais("Documentary"));
        assertFalse(AutoCanales.esPais(""));
        assertFalse(AutoCanales.esPais(null));
    }

    /**
     * v9.2: los `group-title` técnicos se pintan legibles.
     *
     * <p>En la captura del Galaxy A07 las categorías salían "live_matc" y
     * "arabic_sp": sin espacios no pueden partirse en el cuadro de 80 dp.
     */
    @Test
    public void losTopicosSeMuestranLegibles() {
        assertEquals("Live matches", CanalesEnVivoFragment.rotuloDeTopico("live_matches"));
        assertEquals("Arabic sports", CanalesEnVivoFragment.rotuloDeTopico("arabic-sports"));
        assertEquals("Ar 2", CanalesEnVivoFragment.rotuloDeTopico("ar_2"));
        assertEquals("News", CanalesEnVivoFragment.rotuloDeTopico("news"));
        assertEquals("Sin clasificar", CanalesEnVivoFragment.rotuloDeTopico("sin clasificar"));
        assertEquals("", CanalesEnVivoFragment.rotuloDeTopico(""));
        assertEquals("", CanalesEnVivoFragment.rotuloDeTopico(null));
    }

    /**
     * v9.4: el país sale del {@code tvg-id}, que es lo que permite agrupar los
     * canales por país como hace Drama Live (su tira acaba con Syria, Lebanon,
     * Saudi Arabia…). El {@code group-title} solo da el género.
     */
    @Test
    public void elPaisSaleDelTvgId() {
        assertEquals("PE", M3uParser.paisDe("36TVHuacho.pe@SD"));
        assertEquals("PE", M3uParser.paisDe("BellaAbanquinaTV.pe"));
        assertEquals("MX", M3uParser.paisDe("Canal.mx@HD"));
        assertNull("sin punto no hay país", M3uParser.paisDe("sinpais"));
        assertNull(M3uParser.paisDe(null));
        assertNull(M3uParser.paisDe("Algo.xx@SD"));        // marcador de desconocido
        assertNull("no es un código ISO", M3uParser.paisDe("Canal.zz@SD"));
    }

    @Test
    public void elCanalGuardaSuPais() {
        java.util.List<Canal> cs = M3uParser.parsear(M3U);
        assertEquals("PE", cs.get(0).pais);
    }

    @Test
    public void elPaisSeMuestraEnEspanol() {
        assertEquals("Perú", CanalesEnVivoFragment.nombreDePais("PE"));
        assertEquals("México", CanalesEnVivoFragment.nombreDePais("MX"));
        assertEquals("", CanalesEnVivoFragment.nombreDePais(null));
        assertEquals("", CanalesEnVivoFragment.nombreDePais("P"));
    }

    @Test
    public void siHayPocosGruposSeMuestranTodos() {
        java.util.Map<String, Integer> conteo = new java.util.LinkedHashMap<>();
        conteo.put("General", 90);
        conteo.put("Music", 20);
        assertEquals(2, AutoCanales.gruposPrincipales(conteo, 11).size());
    }

    /**
     * v9.1: la tira lleva TODAS las categorías, no las 12 más pobladas.
     *
     * <p>Medido en web.dramalive.cc, su tira muestra 46 categorías y sigue con los
     * países. La v8 recortaba a 12; el tope se quitó.
     */
    @Test
    public void laTiraLlevaTodasLasCategorias() {
        Map<String, Integer> conteo = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) conteo.put("grupo" + i, 40 - i);
        assertEquals(40, AutoCanales.gruposPrincipales(conteo, Integer.MAX_VALUE).size());
        // Y el orden sigue siendo de más canales a menos, como en el sitio.
        assertEquals("grupo0", AutoCanales.gruposPrincipales(conteo, Integer.MAX_VALUE).get(0).getKey());
        assertEquals("grupo39", AutoCanales.gruposPrincipales(conteo, Integer.MAX_VALUE).get(39).getKey());
    }

    /**
     * v9.1: ningún canal se queda fuera de la organización.
     *
     * <p>web.dramalive.cc tiene una categoría "unsorted" para lo que no trae grupo.
     * Aquí, hasta la v9.1, un canal con {@code group-title="Undefined"} quedaba con
     * categoría vacía: no generaba pestaña y solo se veía bajo "Todos".
     */
    @Test
    public void todoCanalCaeEnUnaCategoria() {
        Canal conGrupo = new Canal();
        conGrupo.category = "Deportes";
        assertEquals("Deportes", CanalesEnVivoFragment.topicoDe(conGrupo, "Sin clasificar"));

        Canal sinGrupo = new Canal();
        sinGrupo.category = "";
        assertEquals("Sin clasificar", CanalesEnVivoFragment.topicoDe(sinGrupo, "Sin clasificar"));

        Canal enBlanco = new Canal();
        enBlanco.category = "   ";
        assertEquals("Sin clasificar", CanalesEnVivoFragment.topicoDe(enBlanco, "Sin clasificar"));

        assertEquals("Sin clasificar", CanalesEnVivoFragment.topicoDe(null, "Sin clasificar"));
    }


    /** v3.2.5: la rejilla debe CONSOLIDAR los canales dramalive:// del extraido (el
     * filtro vieja los botaba: el m3u guardado tenia 9002 y la rejilla 0). */
    @Test
    public void parsear_conservaCanalesConEsquemaDramaLive() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Bein S. 2\" group-title=\"hot_now\",Bein S. 2\n"
                + "dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect&t=hot_now\n"
                + "#EXTINF:-1 tvg-name=\"Directo\" group-title=\"g\",Directo\n"
                + "https://example.com/a/index.m3u8\n";
        java.util.List<Canal> cs = M3uParser.parsear(m3u);
        assertEquals(2, cs.size());
        assertEquals("dramalive://play?id_live=live_tv_beinsport2&u=1&a=all_streams_redirect&t=hot_now",
                cs.get(0).options.get(0).url);
        assertEquals("https://example.com/a/index.m3u8", cs.get(1).options.get(0).url);
    }
}
