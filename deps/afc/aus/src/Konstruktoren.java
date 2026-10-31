import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Druckt Konstruktoren und Methoden der Klassen, die aus.jar per Reflection benutzt.
 *  Nur lesend. Aufruf: app_process / Konstruktoren */
public class Konstruktoren {
    static void zeige(String name) {
        System.out.println("== " + name);
        try {
            Class<?> c = Class.forName(name);
            for (Constructor<?> k : c.getDeclaredConstructors()) System.out.println("  K " + k);
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getName().startsWith("get") || m.getName().startsWith("set")
                        || m.getName().equals("build") || m.getName().startsWith("execute")) System.out.println("  M " + m);
            }
        } catch (Throwable t) {
            System.out.println("  FEHLT: " + t);
        }
    }

    public static void main(String[] a) {
        try {
            Class<?> b = Class.forName("android.os.Build$VERSION");
            System.out.println("sdk=" + b.getField("SDK_INT").get(null) + " release=" + b.getField("RELEASE").get(null));
        } catch (Throwable t) { System.out.println("sdk=?" + t); }
        zeige("android.app.appfunctions.ExecuteAppFunctionAidlRequest");
        zeige("android.app.appfunctions.ExecuteAppFunctionRequest");
        zeige("android.app.appfunctions.ExecuteAppFunctionRequest$Builder");
        zeige("android.app.appfunctions.IAppFunctionService");
        zeige("android.app.appfunctions.IAppFunctionManager");
        zeige("android.app.appfunctions.IAppFunctionManager$Stub");
    }
}
