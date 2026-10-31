import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Ausfuehren <paket> <fkt-id> [aufrufer] [pname] [params]
 * params: (a) "b64:<base64 von JSON>" oder JSON "{...}" : verschachtelt, jedes Objekt mit "_typ"
 *         (b) alt: <schema-typ> feld=wert feld:long=n feld:bool=true ...
 * Ausgabe: "ERFOLG: <toString>", "JSON: <json>" bzw. "FEHLER: code=.. kat=.. msg=..", zuletzt "FERTIG".
 */
@SuppressWarnings({"unchecked","rawtypes"})
public class Ausfuehren {
    static Class<?> k(String n) throws Exception { return Class.forName(n); }
    static final String GD = "android.app.appsearch.GenericDocument";

    // ---------- kleiner JSON-Leser (keine Abhaengigkeit)
    static class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object wert() {
            ws(); char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String key = str(); ws(); i++; // ':'
                    m.put(key, wert()); ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>(); i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(wert()); ws(); if (s.charAt(i++) == ']') return l; }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            int j = i; while (j < s.length() && "+-0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            String t = s.substring(i, j); i = j;
            return (t.contains(".") || t.contains("e") || t.contains("E")) ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
        }
        String str() {
            StringBuilder b = new StringBuilder(); i++;
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char d = s.charAt(i++);
                    if (d == 'n') b.append('\n'); else if (d == 't') b.append('\t');
                    else if (d == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    else b.append(d);
                } else b.append(c);
            }
            i++; return b.toString();
        }
    }

    // ---------- JSON -> GenericDocument
    @SuppressWarnings("unchecked")
    static Object zuDok(Map<String, Object> m) throws Exception {
        Class<?> gdb = k(GD + "$Builder"), gd = k(GD);
        Object b = gdb.getConstructor(String.class, String.class, String.class).newInstance("", "p", String.valueOf(m.getOrDefault("_typ", "")));
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().equals("_typ")) continue;
            Object v = e.getValue();
            List<Object> l = v instanceof List ? (List<Object>) v : Collections.singletonList(v);
            String n = e.getKey();
            if (l.isEmpty()) { b = gdb.getMethod("setPropertyString", String.class, String[].class).invoke(b, n, new String[0]); continue; }
            Object f = l.get(0);
            if (f instanceof Map) {
                Object arr = Array.newInstance(gd, l.size());
                for (int i = 0; i < l.size(); i++) Array.set(arr, i, zuDok((Map<String, Object>) l.get(i)));
                b = gdb.getMethod("setPropertyDocument", String.class, arr.getClass()).invoke(b, n, arr);
            } else if (f instanceof Long) {
                long[] a = new long[l.size()]; for (int i = 0; i < a.length; i++) a[i] = (Long) l.get(i);
                b = gdb.getMethod("setPropertyLong", String.class, long[].class).invoke(b, n, a);
            } else if (f instanceof Double) {
                double[] a = new double[l.size()]; for (int i = 0; i < a.length; i++) a[i] = ((Number) l.get(i)).doubleValue();
                b = gdb.getMethod("setPropertyDouble", String.class, double[].class).invoke(b, n, a);
            } else if (f instanceof Boolean) {
                boolean[] a = new boolean[l.size()]; for (int i = 0; i < a.length; i++) a[i] = (Boolean) l.get(i);
                b = gdb.getMethod("setPropertyBoolean", String.class, boolean[].class).invoke(b, n, a);
            } else {
                String[] a = new String[l.size()]; for (int i = 0; i < a.length; i++) a[i] = String.valueOf(l.get(i));
                b = gdb.getMethod("setPropertyString", String.class, String[].class).invoke(b, n, a);
            }
        }
        return gdb.getMethod("build").invoke(b);
    }

    // ---------- GenericDocument -> JSON
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n"); else if (c == '\t') b.append("\\t");
            else if (c < 32) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
        }
        return b.append('"').toString();
    }

    public static String json(Object doc) {
        try {
            Class<?> gd = k(GD);
            StringBuilder b = new StringBuilder("{");
            String typ = (String) gd.getMethod("getSchemaType").invoke(doc);
            boolean erst = true;
            if (typ != null && !typ.isEmpty()) { b.append("\"_typ\":").append(q(typ)); erst = false; }
            String did = (String) gd.getMethod("getId").invoke(doc);
            if (did != null && !did.isEmpty()) { if (!erst) b.append(','); b.append("\"_id\":").append(q(did)); erst = false; }
            List<String> namen = new ArrayList<>((Set<String>) gd.getMethod("getPropertyNames").invoke(doc));
            Collections.sort(namen);
            for (String n : namen) {
                Object v = gd.getMethod("getProperty", String.class).invoke(doc, n);
                if (!erst) b.append(','); erst = false;
                b.append(q(n)).append(':').append('[');
                int len = Array.getLength(v);
                for (int i = 0; i < len; i++) {
                    if (i > 0) b.append(',');
                    Object x = Array.get(v, i);
                    if (x == null) b.append("null");
                    else if (x instanceof String) b.append(q((String) x));
                    else if (x instanceof Number || x instanceof Boolean) b.append(x);
                    else if (gd.isInstance(x)) b.append(json(x));
                    else if (x instanceof byte[]) b.append(q("<" + ((byte[]) x).length + " Byte>"));
                    else b.append(q(String.valueOf(x)));
                }
                b.append(']');
            }
            return b.append('}').toString();
        } catch (Throwable t) { return "{\"_fehler\":" + q(String.valueOf(t)) + "}"; }
    }

    public static void main(String[] a) throws Exception {
        String paket = a[0], fid = a[1];
        String aufrufer = a.length > 2 ? a[2] : "com.android.shell";

        Object binder = k("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "app_function");
        Object mgr = k("android.app.appfunctions.IAppFunctionManager$Stub")
                .getMethod("asInterface", k("android.os.IBinder")).invoke(null, binder);

        Object doc0 = null;
        Class<?> gdb = k(GD + "$Builder");
        Object b = gdb.getConstructor(String.class, String.class, String.class).newInstance("", "", "");
        if (a.length > 4) {
            Object inner;
            String p4 = a[4];
            if (p4.startsWith("b64:")) p4 = new String(Base64.getDecoder().decode(p4.substring(4)), "UTF-8");
            if (p4.startsWith("{")) {
                inner = zuDok((Map<String, Object>) new P(p4).wert());
            } else {
                Object ib = gdb.getConstructor(String.class, String.class, String.class).newInstance("", "p", p4);
                for (int i = 5; i < a.length; i++) {
                    int e = a[i].indexOf('=');
                    if (e < 0) continue;
                    String key = a[i].substring(0, e), val = a[i].substring(e + 1);
                    if (key.endsWith(":long")) ib = gdb.getMethod("setPropertyLong", String.class, long[].class).invoke(ib, key.substring(0, key.length() - 5), new long[]{Long.parseLong(val)});
                    else if (key.endsWith(":bool")) ib = gdb.getMethod("setPropertyBoolean", String.class, boolean[].class).invoke(ib, key.substring(0, key.length() - 5), new boolean[]{Boolean.parseBoolean(val)});
                    else ib = gdb.getMethod("setPropertyString", String.class, String[].class).invoke(ib, key, new String[]{val});
                }
                inner = gdb.getMethod("build").invoke(ib);
            }
            if (a[3].equals("@")) {
                b = null; doc0 = inner;
            } else {
                Class<?> gd = k(GD);
                Object arr = Array.newInstance(gd, 1); Array.set(arr, 0, inner);
                gdb.getMethod("setPropertyDocument", String.class, arr.getClass()).invoke(b, a[3], arr);
            }
        }
        Object doc = doc0 != null ? doc0 : gdb.getMethod("build").invoke(b);

        Class<?> rb = k("android.app.appfunctions.ExecuteAppFunctionRequest$Builder");
        Object rbo = rb.getConstructor(String.class, String.class).newInstance(paket, fid);
        rb.getMethod("setParameters", k(GD)).invoke(rbo, doc);
        Object req = rb.getMethod("build").invoke(rbo);

        Class<?> uh = k("android.os.UserHandle");
        Object user = uh.getMethod("of", int.class).invoke(null, 0);
        Class<?> ar = k("android.app.appfunctions.ExecuteAppFunctionAidlRequest");
        Object areq = baueAidlRequest(ar, k("android.app.appfunctions.ExecuteAppFunctionRequest"), uh, req, user, aufrufer);

        final CountDownLatch fertig = new CountDownLatch(1);
        Object cb = Callback.bauen(fertig);
        mgr.getClass().getMethod("executeAppFunction", ar, k("android.app.appfunctions.IExecuteAppFunctionCallback")).invoke(mgr, areq, cb);
        boolean ok = fertig.await(20, TimeUnit.SECONDS);
        System.out.println(ok ? "FERTIG" : "ZEITUEBERSCHREITUNG");
        System.exit(ok ? 0 : 3);
    }

    /** Der Konstruktor von ExecuteAppFunctionAidlRequest ist je Build verschieden:
     *  (Request, UserHandle, String, long) auf BP2A, (..., long, long) auf BP4A.
     *  Wir waehlen nach Typen: Request, UserHandle, String, danach nur long.
     *  Die long-Werte sind angenommen: erstes = Uhr seit Start, zweites = Wanduhr
     *  (nur aus dem Methodennamen getRequestWallTime geschlossen, nicht belegt).
     *  Passt keiner, werden alle deklarierten Signaturen ausgegeben (Exit 4). */
    static Object baueAidlRequest(Class<?> ar, Class<?> reqK, Class<?> uh, Object req, Object user, String aufrufer) throws Exception {
        long wall = System.currentTimeMillis();
        long seit;
        try { seit = (Long) k("android.os.SystemClock").getMethod("elapsedRealtime").invoke(null); }
        catch (Throwable t) { seit = wall; }
        StringBuilder sig = new StringBuilder();
        for (java.lang.reflect.Constructor<?> c : ar.getDeclaredConstructors()) {
            Class<?>[] t = c.getParameterTypes();
            sig.append("  ").append(c).append('\n');
            if (t.length < 4 || t[0] != reqK || t[1] != uh || t[2] != String.class) continue;
            boolean langs = true;
            for (int i = 3; i < t.length; i++) if (t[i] != long.class) langs = false;
            if (!langs) continue;
            Object[] a = new Object[t.length];
            a[0] = req; a[1] = user; a[2] = aufrufer;
            for (int i = 3; i < t.length; i++) a[i] = (t.length == 4) ? (Object) wall : (i == 3 ? (Object) seit : (Object) wall);
            c.setAccessible(true);
            return c.newInstance(a);
        }
        // Eine Zeile im Format der anderen Fehler (code= kat= msg=), damit sie sich maschinell lesen laesst.
        System.out.println("FEHLER: code=NO_CONSTRUCTOR kat=0 msg=kein passender Konstruktor von " + ar.getName()
                + "; deklariert: " + sig.toString().trim().replace('\n', '|').replaceAll("\\s+", " "));
        System.exit(4);
        return null;
    }
}
