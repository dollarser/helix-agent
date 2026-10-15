package com.helix.app.eval;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** Independent test APK process has no Kotlin runtime; keep this surface platform-only. */
public final class AnimatedObservationSurface extends SurfaceView
    implements SurfaceHolder.Callback, Runnable {
  private final Handler renderHandler = new Handler(Looper.getMainLooper());
  private final Paint paint = new Paint();
  private int frame;

  public AnimatedObservationSurface(Context context) {
    super(context);
    paint.setColor(Color.WHITE);
    getHolder().addCallback(this);
  }

  @Override
  public void run() {
    if (!getHolder().getSurface().isValid() || getWidth() <= 0) return;
    Canvas canvas = getHolder().lockHardwareCanvas();
    try {
      canvas.drawColor(Color.rgb(25, 118, 108));
      float x = (frame++ * 11) % getWidth();
      float y = getHeight() * 0.85f;
      canvas.drawRect(x, y, x + 60, y + 30, paint);
    } finally {
      getHolder().unlockCanvasAndPost(canvas);
    }
    renderHandler.postDelayed(this, 16);
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    renderHandler.post(this);
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    renderHandler.removeCallbacks(this);
  }
}
