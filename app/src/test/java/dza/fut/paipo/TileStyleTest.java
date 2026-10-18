package dza.fut.paipo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TileStyleTest {

    /**
     * v9.9: la bandera del país en la tira de categorías.
     *
     * <p>Se usa el emoji de indicadores regionales, así que el código ISO se
     * convierte en dos puntos de código: U+1F1E6 + (letra - 'A'). Las banderas se
     * escriben con escapes unicode para que el test no dependa de cómo se guarde
     * este archivo. (Ojo: no se puede escribir la barra invertida suelta en un
     * comentario, porque Java la interpreta antes de compilar.)
     */
    @Test
    public void lasBanderasSalenDelCodigoIso() {
        // PE = U+1F1F5 U+1F1EA, MX = U+1F1F2 U+1F1FD, AR = U+1F1E6 U+1F1F7
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA", Ui.banderaEmoji("PE"));
        assertEquals("\uD83C\uDDF2\uD83C\uDDFD", Ui.banderaEmoji("mx"));
        assertEquals("\uD83C\uDDE6\uD83C\uDDF7", Ui.banderaEmoji("AR"));
        // Minúsculas y espacios sobrantes no rompen.
        assertEquals("\uD83C\uDDF5\uD83C\uDDEA", Ui.banderaEmoji(" pe".trim().toUpperCase()));
        // Basura: mejor nada que un cuadro vacío.
        assertEquals("", Ui.banderaEmoji(null));
        assertEquals("", Ui.banderaEmoji(""));
        assertEquals("", Ui.banderaEmoji("P"));
        assertEquals("", Ui.banderaEmoji("PER"));
        assertEquals("", Ui.banderaEmoji("12"));
    }

    // v9.3: vuelve la rejilla. Las capturas del Drama Live real (cur-00, nav-rt,
    // sec-tv) muestran 3 columnas de tiles cuadrados con el nombre dentro; la
    // v9.2 la habia cambiado a filas leyendo item_live_list.xml del APK, que no es
    // la pantalla que la app pinta.
    @Test
    public void columnasHacenLosasDeDramaLive() {
        assertEquals(2, CanalesEnVivoFragment.columnasPara(300f)); // pantalla muy estrecha
        assertEquals(3, CanalesEnVivoFragment.columnasPara(360f)); // telefono tipico
        assertEquals(3, CanalesEnVivoFragment.columnasPara(384f)); // el Galaxy A07 medido
        assertEquals(3, CanalesEnVivoFragment.columnasPara(393f)); // el ancho de la web
        assertEquals(3, CanalesEnVivoFragment.columnasPara(411f)); // Pixel
        assertEquals(4, CanalesEnVivoFragment.columnasPara(500f));
        assertEquals(6, CanalesEnVivoFragment.columnasPara(700f));
        assertEquals(6, CanalesEnVivoFragment.columnasPara(1080f)); // techo 6 (tablet)
    }

    @Test
    public void inicialesTomanPrimerasDosPalabras() {
        assertEquals("DS", Logos.iniciales("Directv Sports"));
        assertEquals("BS", Logos.iniciales("beIN SPORTS 1"));
        assertEquals("HB", Logos.iniciales("HBO"));
        assertEquals("?", Logos.iniciales(null));
        assertEquals("?", Logos.iniciales("   "));
        assertEquals("T", Logos.iniciales("T"));
    }

    @Test
    public void avatarNitidoParaLaLosa() {
        // El tile mide ~108-112 dp (324-336 px a 3x); el avatar de iniciales nace
        // con margen para no estirarse.
        assertEquals(360, Logos.AVATAR_PX);
        assertTrue(Logos.AVATAR_PX >= 3 * 108);
    }

    @Test
    public void paletaEsEstableYEstaEnRango() {
        assertEquals(Logos.indicePaleta("Directv Sports"), Logos.indicePaleta("Directv Sports"));
        for (String n : new String[]{"A", "Bein", "TNT Sports", "", "Zzz 9"}) {
            int ix = Logos.indicePaleta(n);
            assertTrue(n, ix >= 0 && ix < Logos.PALETA.length);
        }
        assertEquals(0, Logos.indicePaleta(null));
        assertEquals(0, Logos.indicePaleta(""));
    }
}
