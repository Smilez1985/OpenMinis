import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Execute <package> <function-id> [caller] [param-name] [params]
 * params: (a) "b64:<base64 of JSON>" or JSON "{...}": nested, every object carries "_type"
 *         (b) legacy: <schema-type> field=value field:long=n field:bool=true ...
 * Output: "SUCCESS: <toString>", "JSON: <json>" or "ERROR: code=.. cat=.. msg=..", finally "DONE".
 */
@SuppressWarnings({"unchecked","rawtypes"})
public class Execute {
    static Class<?> k(String n) throws Exception { return Class.forName(n); }
    static final String GD = "android.app.appsearch.GenericDocument";

    // ---------- small JSON reader (no dependency)
    static class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws(); char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String key = str(); ws(); i++; // ':'
                    m.put(key, value()); ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>(); i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; }
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
    static Object toDocument(Map<String, Object> m) throws Exception {
        Class<?> gdb = k(GD + "$Builder"), gd = k(GD);
        Object b = gdb.getConstructor(String.class, String.class, String.class).newInstance("", "p", String.valueOf(m.getOrDefault("_type", "")));
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().equals("_type")) continue;
            Object v = e.getValue();
            List<Object> l = v instanceof List ? (List<Object>) v : Collections.singletonList(v);
            String n = e.getKey();
            if (l.isEmpty()) { b = gdb.getMethod("setPropertyString", String.class, String[].class).invoke(b, n, new String[0]); continue; }
            Object f = l.get(0);
            if (f instanceof Map) {
                Object arr = Array.newInstance(gd, l.size());
                for (int i = 0; i < l.size(); i++) Array.set(arr, i, toDocument((Map<String, Object>) l.get(i)));
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
            String type = (String) gd.getMethod("getSchemaType").invoke(doc);
            boolean first = true;
            if (type != null && !type.isEmpty()) { b.append("\"_type\":").append(q(type)); first = false; }
            String did = (String) gd.getMethod("getId").invoke(doc);
            if (did != null && !did.isEmpty()) { if (!first) b.append(','); b.append("\"_id\":").append(q(did)); first = false; }
            List<String> names = new ArrayList<>((Set<String>) gd.getMethod("getPropertyNames").invoke(doc));
            Collections.sort(names);
            for (String n : names) {
                Object v = gd.getMethod("getProperty", String.class).invoke(doc, n);
                if (!first) b.append(','); first = false;
                b.append(q(n)).append(':').append('[');
                int len = Array.getLength(v);
                for (int i = 0; i < len; i++) {
                    if (i > 0) b.append(',');
                    Object x = Array.get(v, i);
                    if (x == null) b.append("null");
                    else if (x instanceof String) b.append(q((String) x));
                    else if (x instanceof Number || x instanceof Boolean) b.append(x);
                    else if (gd.isInstance(x)) b.append(json(x));
                    else if (x instanceof byte[]) b.append(q("<" + ((byte[]) x).length + " bytes>"));
                    else b.append(q(String.valueOf(x)));
                }
                b.append(']');
            }
            return b.append('}').toString();
        } catch (Throwable t) { return "{\"_error\":" + q(String.valueOf(t)) + "}"; }
    }

    public static void main(String[] a) throws Exception {
        String pkg = a[0], fid = a[1];
        String caller = a.length > 2 ? a[2] : "com.android.shell";

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
                inner = toDocument((Map<String, Object>) new P(p4).value());
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
        Object rbo = rb.getConstructor(String.class, String.class).newInstance(pkg, fid);
        rb.getMethod("setParameters", k(GD)).invoke(rbo, doc);
        Object req = rb.getMethod("build").invoke(rbo);

        Class<?> uh = k("android.os.UserHandle");
        Object user = uh.getMethod("of", int.class).invoke(null, 0);
        Class<?> ar = k("android.app.appfunctions.ExecuteAppFunctionAidlRequest");
        Object areq = buildAidlRequest(ar, k("android.app.appfunctions.ExecuteAppFunctionRequest"), uh, req, user, caller);

        final CountDownLatch done = new CountDownLatch(1);
        Object cb = Callback.create(done);
        mgr.getClass().getMethod("executeAppFunction", ar, k("android.app.appfunctions.IExecuteAppFunctionCallback")).invoke(mgr, areq, cb);
        boolean ok = done.await(20, TimeUnit.SECONDS);
        System.out.println(ok ? "DONE" : "TIMEOUT");
        System.exit(ok ? 0 : 3);
    }

    /** The constructor of ExecuteAppFunctionAidlRequest differs per build:
     *  (Request, UserHandle, String, long) on BP2A, (..., long, long) on BP4A.
     *  We select by types: Request, UserHandle, String, then only long.
     *  The long values are assumed: first = clock since boot, second = wall clock
     *  (inferred only from the method name getRequestWallTime, not proven).
     *  If none matches, all declared signatures are printed (exit 4). */
    static Object buildAidlRequest(Class<?> ar, Class<?> reqK, Class<?> uh, Object req, Object user, String caller) throws Exception {
        long wall = System.currentTimeMillis();
        long sinceBoot;
        try { sinceBoot = (Long) k("android.os.SystemClock").getMethod("elapsedRealtime").invoke(null); }
        catch (Throwable t) { sinceBoot = wall; }
        StringBuilder sig = new StringBuilder();
        for (java.lang.reflect.Constructor<?> c : ar.getDeclaredConstructors()) {
            Class<?>[] t = c.getParameterTypes();
            sig.append("  ").append(c).append('\n');
            if (t.length < 4 || t[0] != reqK || t[1] != uh || t[2] != String.class) continue;
            boolean allLong = true;
            for (int i = 3; i < t.length; i++) if (t[i] != long.class) allLong = false;
            if (!allLong) continue;
            Object[] a = new Object[t.length];
            a[0] = req; a[1] = user; a[2] = caller;
            for (int i = 3; i < t.length; i++) a[i] = (t.length == 4) ? (Object) wall : (i == 3 ? (Object) sinceBoot : (Object) wall);
            c.setAccessible(true);
            return c.newInstance(a);
        }
        // One line in the format of the other errors (code= cat= msg=) so that it can be parsed by machine.
        System.out.println("ERROR: code=NO_CONSTRUCTOR cat=0 msg=no matching constructor of " + ar.getName()
                + "; declared: " + sig.toString().trim().replace('\n', '|').replaceAll("\\s+", " "));
        System.exit(4);
        return null;
    }
}
