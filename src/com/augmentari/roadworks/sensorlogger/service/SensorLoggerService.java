package com.augmentari.roadworks.sensorlogger.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.widget.Toast;
import com.augmentari.roadworks.sensorlogger.R;
import com.augmentari.roadworks.sensorlogger.activity.MainActivity;
import com.augmentari.roadworks.sensorlogger.component.CircularBuffer;
import com.augmentari.roadworks.sensorlogger.util.Constants;
import com.augmentari.roadworks.sensorlogger.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sample service -- will be doing 2 things later:
 * 1) collecting data from various sources (accelerometer, GPS, maybe microphone)
 * 2) packing it, preparing and sending to the rest (server)
 */
public class SensorLoggerService extends Service implements SensorEventListener, LocationListener {

    private boolean isStarted = false;

    private Sensor accelerometer;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private NotificationManager notificationManager;
    private PowerManager powerManager;

    private long startTimeMillis;

    private boolean hasLocation = false;
    private double latitude = 0;
    private double longitude = 0;
    private float speed = 0;

    int pointer = 0;
    private int filterFactor = 1;
    float normalizer1[] = null;
    float normalizer2[] = null;
    float normalizer3[] = null;
    private float[] gravity = new float[3];
    private CircularBuffer buffer = new CircularBuffer();

    // A Wake Lock object. Lock is acquired when the application asks the service to start listening to events, and
    // is releaserd when the service is actually stopped. As this wake lock is a PARTIAL one, screen may go off but the
    // processor should remain running in the background
    private PowerManager.WakeLock wakeLock = null;

    private List<AccelerometerChangeListener> listeners = new ArrayList<AccelerometerChangeListener>();

    @Override
    public IBinder onBind(Intent intent) {
        return new SessionLoggerServiceBinder();
    }

    @Override
    public void onCreate() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isStarted = true;
        // TODO #18 refactor this out to Configuration
        try {
            filterFactor = Integer.valueOf(PreferenceManager
                    .getDefaultSharedPreferences(this)
                    .getString("pref_LPF_filter_factor", ""));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (filterFactor <= 1) {
            Toast.makeText(this, "Filter factor too small, defaulting to 2", Toast.LENGTH_LONG).show();
            filterFactor = 2;
        }

        boolean bypassGps = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_bypass_GPS", false);

        try {
            normalizer1 = new float[filterFactor];
            normalizer2 = new float[filterFactor];
            normalizer3 = new float[filterFactor];

            Arrays.fill(normalizer1, 0.0f);
            Arrays.fill(normalizer2, 0.0f);
            Arrays.fill(normalizer3, 0.0f);
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Wrong setting for filter factor - defaulting to 10. Prefs.", Toast.LENGTH_LONG).show();
        }

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "My Tag");
        wakeLock.acquire();

        hasLocation = false;
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);

        Notification notification = getNotification(
                getString(R.string.notification_title),
                getString(R.string.sensor_logger_service_notification_waiting_GPS_text),
                getString(R.string.sensor_logger_service_notification_waiting_GPS_text)
        );

        startForeground(Constants.ONGOING_NOTIFICATION, notification);

        if (bypassGps) {
            hasLocation = true;
            subscribeToAccelerometerEvents();
        }

        return START_STICKY;
    }

    private Notification getNotification(String starterNotificationText, String ticker, String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        return new Notification.Builder(this)
                .setTicker(ticker)
                .setContentTitle(starterNotificationText)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_logger_notification)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (!isStarted) {
            // we have only been bound, and no real action to take.
            return;
        }
        isStarted = false;

        stopForeground(true);
        sensorManager.unregisterListener(this);
        locationManager.removeUpdates(this);

        if (wakeLock != null) {
            wakeLock.release();
        }

        try {
            for (AccelerometerChangeListener listener : listeners) {
                listener.onSessionClosed();
            }
        } finally {
            super.onDestroy();
        }
    }


    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        final float[] readings = sensorEvent.values;
        assert readings.length == 3;

        // removing gravity
        final float alpha = 0.8f;

        gravity[0] = alpha * gravity[0] + (1 - alpha) * readings[0];
        gravity[1] = alpha * gravity[1] + (1 - alpha) * readings[1];
        gravity[2] = alpha * gravity[2] + (1 - alpha) * readings[2];

        float x = readings[0] - gravity[0];
        float y = readings[1] - gravity[1];
        float z = readings[2] - gravity[2];

        // filtering

        // current differential value to go to the LPF buffer
        normalizer1[pointer] = x;
        normalizer2[pointer] = y;
        normalizer3[pointer] = z;

        float valueSum = 0;
        for (int j = 1; j < filterFactor; j++) {
            valueSum += Math.abs(normalizer1[j] - normalizer1[j - 1])
                    + Math.abs(normalizer2[j] - normalizer2[j - 1])
                    + Math.abs(normalizer3[j] - normalizer3[j - 1]);
        }
        float value = valueSum / filterFactor / 3;
        pointer = (pointer + 1) % filterFactor;

        buffer.append(value);

        for (AccelerometerChangeListener listener : listeners) {
            listener.onAccelerometerChanged(value, buffer, latitude, longitude, speed);
        }
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        Log.i("onAccuracyChanged");
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.i("onLocationChanged");

        // if that's the first time we got a fix, show an appropriate notification.
        // and fire off the accelerometer reading subscription
        if (!hasLocation) {
            subscribeToAccelerometerEvents();
            Notification notification = getNotification(
                    getString(R.string.notification_title),
                    getString(R.string.sensor_logger_service_notification_text),
                    getString(R.string.sensor_logger_service_notification_text)
            );
            notificationManager.notify(Constants.ONGOING_NOTIFICATION, notification);
            hasLocation = true;
        }

        // and now triggering the actual accel updates, which will push data to the file.
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        speed = location.getSpeed();
    }

    private void subscribeToAccelerometerEvents() {
        if (accelerometer == null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);

            startTimeMillis = System.currentTimeMillis();
        }
        for (AccelerometerChangeListener listener : listeners) {
            listener.onNewSessionStarted();
        }
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
        Log.i("onStatusChanged");
    }

    @Override
    public void onProviderEnabled(String s) {
        Log.i("onProviderEnabled");
    }

    @Override
    public void onProviderDisabled(String s) {
        Log.i("onProviderDisabled");
    }

    /**
     * Interface, whose methods get invoked when new data comes from Accelerometer.
     */
    public interface AccelerometerChangeListener {
        public void onAccelerometerChanged(final float lastAccelFilteredDiffValue,
                                           final CircularBuffer wholeBuffer,
                                           final double latitude,
                                           final double longitude,
                                           final double speed);

        public void onNewSessionStarted();

        public void onSessionClosed();
    }

    /**
     * A Binder interface to this service, providing some communication to the outside world.
     */
    public class SessionLoggerServiceBinder extends Binder {
        public boolean isStarted() {
            return isStarted;
        }

        public long getStartTimeMillis() {
            return startTimeMillis;
        }

        public boolean hasGPSfix() {
            return hasLocation;
        }

        public void addAccelChangedListener(AccelerometerChangeListener listener) {
            if (listeners.contains(listener)) return;
            listeners.add(listener);
        }

        public void removeAccelChangedListener(AccelerometerChangeListener listener) {
            listeners.remove(listener);
        }
    }

}
