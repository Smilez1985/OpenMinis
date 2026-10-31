import android.app.appfunctions.*;
import java.util.concurrent.CountDownLatch;
public class SchalterCallback extends IAppFunctionEnabledCallback.Stub {
    final CountDownLatch l;
    SchalterCallback(CountDownLatch l) { this.l = l; }
    public void onSuccess() { System.out.println("ERFOLG"); l.countDown(); }
    public void onError(android.os.ParcelableException e) { System.out.println("FEHLER: " + e + " ursache=" + e.getCause()); l.countDown(); }
    static Object bauen(CountDownLatch l) { return new SchalterCallback(l); }
}
