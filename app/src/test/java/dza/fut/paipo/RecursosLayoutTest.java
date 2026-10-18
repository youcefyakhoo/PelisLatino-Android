package dza.fut.paipo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Guarda contra el {@code InflateException} que no ve ni el build ni lint.
 *
 * <p>Hay atributos de framework cuyo formato declarado es {@code reference|color}, así que
 * AAPT2 acepta un {@code @dimen} sin protestar y el {@code Resources#getDrawable} solo
 * revienta en el inflado, en la cara del usuario. Nos pasó con
 * {@code android:divider="@dimen/space_1"} en la tira de pestañas de canales (error en
 * runtime "Binary XML file line #124 ... Error inflating class &lt;unknown&gt;").
 *
 * <p>Este test recorre los layouts del módulo y falla si algún atributo que pide Drawable
 * referencia un dimen o un integer. Es barato (~200 archivos, un par de ms) y corre sin
 * dispositivo, que es justo lo que falta para atrapar esta clase de bug.
 */
public class RecursosLayoutTest {

    /** Atributos que en la plataforma se resuelven como Drawable. */
    private static final List<String> PIDEN_DRAWABLE = Arrays.asList(
            "divider", "src", "background", "foreground", "listSelector", "button",
            "thumb", "track", "tickMark", "progressDrawable", "scrollbarThumbHorizontal",
            "scrollbarThumbVertical", "scrollbarTrackHorizontal", "scrollbarTrackVertical",
            "drawableTop", "drawableBottom", "drawableLeft", "drawableRight",
            "drawableStart", "drawableEnd", "drawableOverride");

    private static final Pattern MAL = Pattern.compile(
            "(?:android|app):(" + String.join("|", PIDEN_DRAWABLE) + ")\\s*=\\s*\"@(dimen|integer)/[^\"]*\"");

    @Test
    public void ningunAtributoDeDrawableApuntaAUnDimen() throws Exception {
        File dir = buscarLayouts();
        List<String> ofensores = new ArrayList<>();
        for (File f : dir.listFiles((d, n) -> n.endsWith(".xml"))) {
            List<String> lineas = sinComentarios(f);
            for (int i = 0; i < lineas.size(); i++) {
                Matcher m = MAL.matcher(lineas.get(i));
                if (m.find()) {
                    ofensores.add(f.getName() + ":" + (i + 1) + " -> " + m.group().trim());
                }
            }
        }
        assertTrue("atributos que piden Drawable apuntando a @dimen/@integer (revientan al "
                + "inflar, no en el build): " + ofensores, ofensores.isEmpty());
    }

    /** Los comentarios no cuentan: solo los atributos de verdad. */
    private static List<String> sinComentarios(File f) throws Exception {
        String crudo = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String limpio = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(crudo).replaceAll(" ");
        return Arrays.asList(limpio.split("\n", -1));
    }

    /** La tira de pestañas se separa con márgenes del chip, no con dividers del contenedor. */
    @Test
    public void laTiraDePestanasNoUsaDividers() throws Exception {
        File f = new File(buscarLayouts(), "fragment_canales_en_vivo.xml");
        StringBuilder sb = new StringBuilder();
        for (String linea : sinComentarios(f)) sb.append(linea).append('\n');
        String s = sb.toString();
        assertFalse("el contenedor de tabs no puede llevar android:divider (pide Drawable)",
                s.contains("android:divider"));
    }

    private static File buscarLayouts() {
        for (String candidato : new String[]{"src/main/res/layout", "app/src/main/res/layout",
                "../app/src/main/res/layout"}) {
            File dir = new File(candidato);
            if (dir.isDirectory()) return dir;
        }
        throw new AssertionError("no encuentro res/layout desde " + new File(".").getAbsolutePath()
                + " (¿se movió la estructura del módulo?)");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  pathData de los VectorDrawable
    //
    //  AAPT2 guarda android:pathData como CADENA y no la parsea, así que un
    //  pathData inválido compila perfectamente y revienta al inflar el
    //  drawable — el mismo tipo de fallo que persigue esta clase. Nos pasó en
    //  la v9: el confeti generado emitía arcos de SEIS parámetros
    //  (`a78,0 0 1 156,0`) cuando el arco necesita SIETE (`rx ry rot
    //  large-arc-flag sweep-flag dx dy`). Con ry=0 y el "sweep" comiéndose el
    //  156, android.util.PathParser se encuentra un '-' donde espera un flag
    //  0/1. Y como ese vector cuelga del windowBackground del tema, el fallo
    //  ocurría antes de pintar nada: la app "se cerraba antes de abrir".
    // ══════════════════════════════════════════════════════════════════════

    /** Comando -> cuántos parámetros lleva. */
    private static final Map<Character, Integer> PARAMS = new HashMap<>();

    static {
        for (String par : "M2 m2 L2 l2 H1 h1 V1 v1 C6 c6 S4 s4 Q4 q4 T2 t2 A7 a7 Z0 z0".split(" ")) {
            PARAMS.put(par.charAt(0), Integer.parseInt(par.substring(1)));
        }
    }

    private static final Pattern NUMERO =
            Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Pattern PATHDATA =
            Pattern.compile("android:pathData\\s*=\\s*\"([^\"]*)\"");

    @Test
    public void losVectoresTienenPathDataValido() throws Exception {
        File dir = buscarDrawables();
        List<String> errores = new ArrayList<>();
        int vectores = 0;
        File[] ficheros = dir.listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue("no hay drawables en " + dir, ficheros != null && ficheros.length > 0);
        for (File f : ficheros) {
            String crudo = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String limpio = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(crudo).replaceAll(" ");
            if (!limpio.contains("<vector")) continue;
            vectores++;
            Matcher m = PATHDATA.matcher(limpio);
            int k = 0;
            while (m.find()) {
                k++;
                errores.addAll(problemasDePathData(m.group(1), f.getName() + " (path " + k + ")"));
            }
            if (k == 0) errores.add(f.getName() + ": <vector> sin ningún android:pathData");
        }
        assertTrue("vectores revisados: " + vectores + ". Problemas de pathData (aapt2 no los "
                + "valida y revientan al inflar): " + errores, errores.isEmpty());
    }

    /**
     * Recorre el pathData con la gramática de android.util.PathParser y devuelve
     * la lista de problemas. Vacía = el drawable se puede inflar.
     */
    private static List<String> problemasDePathData(String path, String donde) {
        List<String> errores = new ArrayList<>();
        int i = 0;
        int n = path.length();
        Character cmd = null;
        while (i < n) {
            i = saltar(path, i, n);
            if (i >= n) break;
            char c = path.charAt(i);
            if (Character.isLetter(c)) {
                if (!PARAMS.containsKey(c)) {
                    errores.add(donde + ": comando desconocido '" + c + "'");
                    return errores;
                }
                cmd = c;
                i++;
                if (PARAMS.get(c) == 0) continue;
            } else if (cmd == null) {
                errores.add(donde + ": el pathData empieza con un número, sin comando");
                return errores;
            } else if (cmd == 'M') {
                cmd = 'L';   // un moveto con más números se sigue como lineto
            } else if (cmd == 'm') {
                cmd = 'l';
            }

            int nparams = PARAMS.get(cmd);
            String[] params = new String[nparams];
            for (int k = 0; k < nparams; k++) {
                i = saltar(path, i, n);
                if (i >= n) {
                    errores.add(donde + ": al comando '" + cmd + "' le faltan parámetros ("
                            + k + " de " + nparams + ")");
                    return errores;
                }
                boolean esFlag = (cmd == 'A' || cmd == 'a') && (k == 3 || k == 4);
                if (esFlag) {
                    char flag = path.charAt(i);
                    if (flag != '0' && flag != '1') {
                        errores.add(donde + ": el flag " + k + " del arco '" + cmd
                                + "' no es 0 ni 1, es '" + flag + "'");
                        return errores;
                    }
                    params[k] = String.valueOf(flag);
                    i++;
                } else {
                    Matcher num = NUMERO.matcher(path);
                    num.region(i, n);
                    if (!num.lookingAt()) {
                        errores.add(donde + ": se esperaba un número en la posición " + i
                                + " y hay '" + path.charAt(i) + "'");
                        return errores;
                    }
                    params[k] = num.group();
                    i = num.end();
                }
            }

            // Un arco con rx o ry a 0 es degenerado: PathParser divide por ry*ry
            // y saca NaN, que Skia pinta como basura (o aborta).
            if (cmd == 'A' || cmd == 'a') {
                double rx = Double.parseDouble(params[0]);
                double ry = Double.parseDouble(params[1]);
                if (rx == 0 || ry == 0) {
                    errores.add(donde + ": arco con rx=" + params[0] + " ry=" + params[1]
                            + " (degenerado, dará NaN)");
                    return errores;
                }
            }
        }
        if (cmd == null) errores.add(donde + ": pathData vacío");
        return errores;
    }

    private static int saltar(String s, int i, int n) {
        while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
        return i;
    }

    private static File buscarDrawables() {
        for (String candidato : new String[]{"src/main/res/drawable", "app/src/main/res/drawable",
                "../app/src/main/res/drawable"}) {
            File dir = new File(candidato);
            if (dir.isDirectory()) return dir;
        }
        throw new AssertionError("no encuentro res/drawable desde " + new File(".").getAbsolutePath()
                + " (¿se movió la estructura del módulo?)");
    }
}
