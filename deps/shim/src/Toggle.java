import java.lang.reflect.*;
import java.util.concurrent.*;

/** Toggle <package> <function-id> <state 0|1|2> [caller] : IAppFunctionManager.setAppFunctionEnabled.
 *  0 = default (enabledByDefault), 1 = on, 2 = off.  Output: SUCCESS | ERROR ... , then DONE | TIMEOUT.
 *  The argument order of setAppFunctionEnabled differs between builds; setting AF_SWAP in the
 *  environment selects the (function-id, caller) order instead of (caller, function-id). */
public class Toggle {
    public static void main(String[] a) throws Exception {
        String pkg = a[0], fid = a[1]; int state = Integer.parseInt(a[2]);
        String caller = a.length > 3 ? a[3] : "com.android.shell";
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "app_function");
        Class<?> ifc = Class.forName("android.app.appfunctions.IAppFunctionManager");
        Object mgr = Class.forName("android.app.appfunctions.IAppFunctionManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Class<?> uh = Class.forName("android.os.UserHandle");
        Object user = uh.getMethod("of", int.class).invoke(null, 0);
        CountDownLatch latch = new CountDownLatch(1);
        Object cb = ToggleCallback.create(latch);
        Method m = null;
        for (Method x : ifc.getMethods()) if (x.getName().equals("setAppFunctionEnabled")) m = x;
        if (System.getenv("AF_SWAP") != null) m.invoke(mgr, fid, caller, user, state, cb); else m.invoke(mgr, caller, fid, user, state, cb);
        System.out.println(latch.await(15, TimeUnit.SECONDS) ? "DONE" : "TIMEOUT");
        System.exit(0);
    }
}
