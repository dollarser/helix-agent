package com.helix.spike.termlib;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.test.InstrumentationTestCase;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class PtyProbeTest extends InstrumentationTestCase {
    public void testPrivateProcessPty() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ArrayBlockingQueue<IBinder> connection = new ArrayBlockingQueue<>(1);
        ServiceConnection listener = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { connection.offer(binder); }
            public void onServiceDisconnected(ComponentName name) {}
        };
        assertTrue(context.bindService(new Intent(context, PtyProbeService.class), listener, Context.BIND_AUTO_CREATE));
        try {
            IBinder binder = connection.poll(10, TimeUnit.SECONDS);
            assertNotNull(binder);
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            try {
                assertTrue(binder.transact(1, request, reply, 0));
                assertTrue("Must execute in private service", reply.readInt() != android.os.Process.myPid());
                assertEquals("OK: tty, UTF-8, cwd/env, resize, Ctrl-C, EOF", reply.readString());
            } finally { request.recycle(); reply.recycle(); }
        } finally { context.unbindService(listener); }
    }
}
