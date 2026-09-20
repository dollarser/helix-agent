package com.helix.spike.termlib;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

public final class PtyProbeService extends Service {
    static { System.loadLibrary("pty_probe"); }
    private static native String runProbe();

    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 1) return false;
                reply.writeInt(android.os.Process.myPid());
                reply.writeString(runProbe());
                return true;
            }
        };
    }
}
