import android.app.appfunctions.*;
import java.util.concurrent.CountDownLatch;
public class ToggleCallback extends IAppFunctionEnabledCallback.Stub {
    final CountDownLatch latch;
    ToggleCallback(CountDownLatch latch) { this.latch = latch; }
    public void onSuccess() { System.out.println("SUCCESS"); latch.countDown(); }
    public void onError(android.os.ParcelableException e) { System.out.println("ERROR: " + e + " cause=" + e.getCause()); latch.countDown(); }
    static Object create(CountDownLatch latch) { return new ToggleCallback(latch); }
}
