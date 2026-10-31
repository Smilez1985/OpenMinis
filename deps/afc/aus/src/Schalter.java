import java.lang.reflect.*;
import java.util.concurrent.*;

/** Schalter <paket> <fkt-id> <zustand 0|1|2> [aufrufer] : IAppFunctionManager.setAppFunctionEnabled.
 *  0 = Standard (enabledByDefault), 1 = an, 2 = aus.  Ausgabe: ERFOLG | FEHLER ... , dann FERTIG. */
public class Schalter {
    public static void main(String[] a) throws Exception {
        String paket = a[0], fid = a[1]; int zustand = Integer.parseInt(a[2]);
        String aufrufer = a.length > 3 ? a[3] : "com.android.shell";
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "app_function");
        Class<?> ifc = Class.forName("android.app.appfunctions.IAppFunctionManager");
        Object mgr = Class.forName("android.app.appfunctions.IAppFunctionManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Class<?> uh = Class.forName("android.os.UserHandle");
        Object user = uh.getMethod("of", int.class).invoke(null, 0);
        CountDownLatch l = new CountDownLatch(1);
        Object cb = SchalterCallback.bauen(l);
        Method m = null;
        for (Method x : ifc.getMethods()) if (x.getName().equals("setAppFunctionEnabled")) m = x;
        if (System.getenv("AF_TAUSCH") != null) m.invoke(mgr, fid, aufrufer, user, zustand, cb); else m.invoke(mgr, aufrufer, fid, user, zustand, cb);
        System.out.println(l.await(15, TimeUnit.SECONDS) ? "FERTIG" : "ZEITUEBERSCHREITUNG");
        System.exit(0);
    }
}
