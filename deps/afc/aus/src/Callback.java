import android.app.appfunctions.*;
import java.util.concurrent.CountDownLatch;

public class Callback extends IExecuteAppFunctionCallback.Stub {
    final CountDownLatch l;
    Callback(CountDownLatch l) { this.l = l; }

    public void onSuccess(ExecuteAppFunctionResponse r) {
        Object d = r.getResultDocument();
        System.out.println("ERFOLG: " + d);
        System.out.println("JSON: " + Ausfuehren.json(d));
        l.countDown();
    }

    public void onError(AppFunctionException e) {
        System.out.println("FEHLER: code=" + e.getErrorCode() + " kat=" + e.getErrorCategory() + " msg=" + e.getErrorMessage());
        l.countDown();
    }

    static Object bauen(CountDownLatch l) { return new Callback(l); }
}
