package com.helix.app.test;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only remote Binder. Java avoids reliance on the target APK's Kotlin runtime. */
public final class BlockedObservationService extends Service {
    public static final int QUERY = IBinder.FIRST_CALL_TRANSACTION;
    public static final int STATUS = QUERY + 1;
    public static final int RELEASE = QUERY + 2;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger entered = new AtomicInteger();
    private final AtomicInteger exited = new AtomicInteger();
    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (getPackageManager().checkSignatures(Binder.getCallingUid(), Process.myUid())
                    != PackageManager.SIGNATURE_MATCH) throw new SecurityException("Test signature required");
            if (reply == null) throw new IllegalArgumentException("Synchronous fixture only");
            switch (code) {
                case QUERY:
                    entered.incrementAndGet();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture release deadline");
                        reply.writeNoException();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Fixture interrupted", interrupted);
                    } finally {
                        exited.incrementAndGet();
                    }
                    return true;
                case STATUS:
                    reply.writeNoException();
                    reply.writeInt(Process.myPid());
                    reply.writeInt(entered.get());
                    reply.writeInt(exited.get());
                    return true;
                case RELEASE:
                    release.countDown();
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    @Override public IBinder onBind(Intent intent) { return endpoint; }

    @Override public void onDestroy() {
        release.countDown();
        super.onDestroy();
    }
}
