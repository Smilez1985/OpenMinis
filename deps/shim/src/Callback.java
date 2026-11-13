import android.app.appfunctions.*;
import java.util.concurrent.CountDownLatch;

public class Callback extends IExecuteAppFunctionCallback.Stub {
    final CountDownLatch latch;
    Callback(CountDownLatch latch) { this.latch = latch; }

    public void onSuccess(ExecuteAppFunctionResponse r) {
        Object d = r.getResultDocument();
        System.out.println("SUCCESS: " + d);
        System.out.println("JSON: " + Execute.json(d));
        latch.countDown();
    }

    public void onError(AppFunctionException e) {
        System.out.println("ERROR: code=" + e.getErrorCode() + " cat=" + e.getErrorCategory() + " msg=" + e.getErrorMessage());
        latch.countDown();
    }

    static Object create(CountDownLatch latch) { return new Callback(latch); }
}
