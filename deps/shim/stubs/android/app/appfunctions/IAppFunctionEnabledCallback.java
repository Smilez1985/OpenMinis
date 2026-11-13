package android.app.appfunctions; public interface IAppFunctionEnabledCallback extends android.os.IInterface { void onSuccess() throws android.os.RemoteException; void onError(android.os.ParcelableException e) throws android.os.RemoteException;
 abstract class Stub extends android.os.Binder implements IAppFunctionEnabledCallback { public android.os.IBinder asBinder(){return this;} } }
