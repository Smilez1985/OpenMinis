import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Prints constructors and methods of the classes that shim.jar uses via reflection.
 *  Read-only. Invocation: app_process / Constructors */
public class Constructors {
    static void show(String name) {
        System.out.println("== " + name);
        try {
            Class<?> c = Class.forName(name);
            for (Constructor<?> k : c.getDeclaredConstructors()) System.out.println("  C " + k);
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getName().startsWith("get") || m.getName().startsWith("set")
                        || m.getName().equals("build") || m.getName().startsWith("execute")) System.out.println("  M " + m);
            }
        } catch (Throwable t) {
            System.out.println("  MISSING: " + t);
        }
    }

    public static void main(String[] a) {
        try {
            Class<?> b = Class.forName("android.os.Build$VERSION");
            System.out.println("sdk=" + b.getField("SDK_INT").get(null) + " release=" + b.getField("RELEASE").get(null));
        } catch (Throwable t) { System.out.println("sdk=?" + t); }
        show("android.app.appfunctions.ExecuteAppFunctionAidlRequest");
        show("android.app.appfunctions.ExecuteAppFunctionRequest");
        show("android.app.appfunctions.ExecuteAppFunctionRequest$Builder");
        show("android.app.appfunctions.IAppFunctionService");
        show("android.app.appfunctions.IAppFunctionManager");
        show("android.app.appfunctions.IAppFunctionManager$Stub");
    }
}
