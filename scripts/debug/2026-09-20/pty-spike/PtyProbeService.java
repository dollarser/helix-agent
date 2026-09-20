package com.helix.spike.termlib;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

public final class PtyProbeService extends Service {
    static { System.loadLibrary("pty_probe"); }
    private static native String runProbe(String install, String loader);

    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 1) return false;
                reply.writeInt(android.os.Process.myPid());
                String[] runtime = PtyRuntime.prepare(PtyProbeService.this);
                reply.writeString(runProbe(runtime[0], runtime[1]));
                return true;
            }
        };
    }
}
