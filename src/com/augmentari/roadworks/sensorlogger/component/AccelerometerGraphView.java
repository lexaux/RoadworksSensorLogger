package com.augmentari.roadworks.sensorlogger.component;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;
import com.augmentari.roadworks.sensorlogger.service.SensorLoggerService;
import com.augmentari.roadworks.sensorlogger.util.Log;

import java.util.Arrays;

/**
 * View showing line chart/graph view of the accelerometer readings.
 */
public class AccelerometerGraphView extends SurfaceView implements SurfaceHolder.Callback, SensorLoggerService.AccelerometerChangeListener {

    public static final float GRAVITY_FT_SEC = 9.8f;
    // max possible size of the circularbuffer = sizeof(float) * 3 * max(deviceX, deviceY). So, should not be more than
    // 20Kb
    private CircularBuffer buffer = null;
    final Object changedDataLock = new Object();
    private DrawingThread thread;
    private Paint paintNormal, paintOverThreshold, detectionFillPaint, whitePaint;
    private int width;
    private float ftSecToPx;
    private int filterFactor = 1;
    private int height;
    private float threshold = 2f;

    public AccelerometerGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);

        paintNormal = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintNormal.setColor(Color.GREEN);
        paintNormal.setStyle(Paint.Style.STROKE);

        paintOverThreshold = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintOverThreshold.setColor(Color.RED);
        paintOverThreshold.setStrokeWidth(2f);
        paintOverThreshold.setStyle(Paint.Style.STROKE);

        whitePaint = new Paint();
        whitePaint.setColor(Color.WHITE);
        whitePaint.setStyle(Paint.Style.STROKE);

        detectionFillPaint = new Paint();
        detectionFillPaint.setColor(Color.RED);
        detectionFillPaint.setStrokeWidth(0);
        detectionFillPaint.setStyle(Paint.Style.STROKE);
    }


    protected void doDrawOnSeparateThread(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        canvas.drawRect(0, 0, width - 1, height - 1, whitePaint);
        canvas.drawLine(0, height - threshold * ftSecToPx, width, height - threshold * ftSecToPx, whitePaint);

        if (buffer == null) {
            // nothing to draw, quiting.
            return;
        }

        float lastX1 = width;
        float lastY1 = height - (buffer.getA(0) + buffer.getB(0) + buffer.getC(0) / 3 * ftSecToPx);

        int pointer = 0;
        float normalizer1[] = new float[filterFactor];
        float normalizer2[] = new float[filterFactor];
        float normalizer3[] = new float[filterFactor];
        Arrays.fill(normalizer1, 0.0f);
        Arrays.fill(normalizer2, 0.0f);
        Arrays.fill(normalizer3, 0.0f);

        for (int i = 1; i < buffer.getActualSize(); i++) {
            float toX = width - i;

            // current differential value to go to the LPF buffer
            normalizer1[pointer] = Math.abs(buffer.getA(i) - buffer.getA(i - 1));
            normalizer2[pointer] = Math.abs(buffer.getB(i) - buffer.getB(i - 1));
            normalizer3[pointer] = Math.abs(buffer.getC(i) - buffer.getC(i - 1));

            float valueSum = 0;
            for (int j = 0; j < filterFactor; j++) {
                valueSum += normalizer1[j] + normalizer2[j] + normalizer3[j];
            }
            float value = valueSum / filterFactor / 3;
            float toY = height - (value * ftSecToPx);

            Paint paint;
            if (value < threshold) {
                paint = paintNormal;
            } else {
                paint = paintOverThreshold;
                canvas.drawRect(lastX1, lastY1, toX, height - threshold * ftSecToPx - 1, detectionFillPaint);
            }
            canvas.drawLine(lastX1, lastY1, toX, toY, paint);

            lastX1 = toX;
            lastY1 = toY;

            pointer = (pointer + 1) % filterFactor;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        thread = new DrawingThread(getHolder(), this);
        thread.setRunning(true);
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        try {
            filterFactor = Integer.valueOf(PreferenceManager
                    .getDefaultSharedPreferences(getContext())
                    .getString("pref_LPF_filter_factor", ""));
        } catch (NumberFormatException ex) {
            Toast.makeText(getContext(), "Wrong setting for filter factor - defaulting to 10. Prefs.", Toast.LENGTH_LONG).show();
        }

        try {
            threshold = Float.valueOf(PreferenceManager
                    .getDefaultSharedPreferences(getContext())
                    .getString("pref_detection_threshold", ""));
        } catch (NumberFormatException ex) {
            Toast.makeText(getContext(), "Wrong setting for detection threshold. Defaulting to 2.", Toast.LENGTH_LONG).show();
        }

        if (buffer == null) {
            WindowManager manager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            Point p = new Point();
            manager.getDefaultDisplay().getSize(p);
            buffer = new CircularBuffer(Math.max(p.x, p.y));
        }
        this.width = width;
        this.height = height;

        ftSecToPx = height / GRAVITY_FT_SEC;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        boolean retry = true;
        thread.setRunning(false);
        while (retry) {
            try {
                synchronized (changedDataLock) {
                    changedDataLock.notifyAll();
                }
                thread.join();
                retry = false;
            } catch (InterruptedException e) {
                Log.i("Interrupted stopping drawing thread of the surface");
            }
        }
    }

    @Override
    public void onAccelerometerChanged(float a, float b, float c) {
        synchronized (changedDataLock) {
            changedDataLock.notifyAll();
        }
        if (buffer == null) return;
        buffer.append(a, b, c);
    }

    public CircularBuffer getBuffer() {
        return buffer;
    }

    public void setBuffer(CircularBuffer buffer) {
        this.buffer = buffer;
    }

    class DrawingThread extends Thread {
        private final SurfaceHolder holder;
        private final AccelerometerGraphView graphView;
        private boolean running;

        public DrawingThread(SurfaceHolder holder, AccelerometerGraphView context) {
            super("Accel drawingn thread");
            this.holder = holder;
            this.graphView = context;
        }

        public void setRunning(boolean running) {
            this.running = running;
        }

        @Override
        public void run() {
            while (running) {
                Canvas c = null;
                try {
                    synchronized (changedDataLock) {
                        changedDataLock.wait();
                    }
                    c = holder.lockCanvas(null);
                    if (c != null) {
                        synchronized (holder) {
                            graphView.doDrawOnSeparateThread(c);
                            // drawn, wait for new arrivals.
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    if (c != null) {
                        holder.unlockCanvasAndPost(c);
                    }
                }
            }
        }
    }
}