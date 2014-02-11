package com.augmentari.roadworks.sensorlogger.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
import android.widget.Toast;
import com.augmentari.roadworks.model.RecordingSession;
import com.augmentari.roadworks.sensorlogger.R;
import com.augmentari.roadworks.sensorlogger.activity.MainActivity;
import com.augmentari.roadworks.sensorlogger.dao.RecordingSessionDAO;
import com.augmentari.roadworks.sensorlogger.util.CloseUtils;
import com.augmentari.roadworks.sensorlogger.util.Constants;
import com.augmentari.roadworks.sensorlogger.util.Formats;
import com.augmentari.roadworks.sensorlogger.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Sample service -- will be doing 2 things later:
 * 1) collecting data from various sources (accelerometer, GPS, maybe microphone)
 * 2) packing it, preparing and sending to the rest (server)
 */
public class SensorLoggerService extends Service implements SensorEventListener, LocationListener {

    // size of the buffer for the file stream; .5M for a start
    public static final int BUFFER_SIZE = 128 * 1024;

    private boolean isStarted = false;

    private Sensor accelerometer;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private NotificationManager notificationManager;
    private PowerManager powerManager;

    private FileOutputStream outputStream;
    private PrintWriter fileResultsWriter;
    private long startTimeMillis;
    private long statementsLogged;

    private boolean hasLocation = false;
    private double latitude = 0;
    private double longitude = 0;
    private float speed = 0;

    private RecordingSessionDAO recordingSessionDAO;

    // A Wake Lock object. Lock is acquired when the application asks the service to start listening to events, and
    // is releaserd when the service is actually stopped. As this wake lock is a PARTIAL one, screen may go off but the
    // processor should remain running in the background
    private PowerManager.WakeLock wakeLock = null;
    private RecordingSession currentSession = null;
    private List<AccelerometerChangeListener> listeners = new ArrayList<AccelerometerChangeListener>();
    private float[] gravity = new float[3];

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

        recordingSessionDAO = new RecordingSessionDAO(this);

        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isStarted = true;

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

        // seem like don't need to close 'lower' streams, as this delegates close command down the
        // chain till the fileOutputStream
        if (wakeLock != null) {
            wakeLock.release();
        }

        CloseUtils.closeStream(fileResultsWriter);

        if (currentSession != null) {

            recordingSessionDAO.open();
            recordingSessionDAO.finishSession(currentSession.getId(), statementsLogged, new Date());
            CloseUtils.closeDao(recordingSessionDAO);

            Toast.makeText(
                    this,
                    MessageFormat.format(
                            getString(R.string.fileCollectedSizeMessage),
                            Formats.formatReadableBytesSize(new File(currentSession.getDataFileFullPath()).length())),
                    Toast.LENGTH_LONG).show();
        }

        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        recordReading(sensorEvent.values);
    }

    private void writeHeading() {
        fileResultsWriter.println("Time, Accelerometer Sensor 1, Sensor 2, Sensor 3, Gps Speed, Latitude, Longitude");
    }

    private void recordReading(float[] readings) {
        assert readings.length == 3;

        final float alpha = 0.8f;

        gravity[0] = alpha * gravity[0] + (1 - alpha) * readings[0];
        gravity[1] = alpha * gravity[1] + (1 - alpha) * readings[1];
        gravity[2] = alpha * gravity[2] + (1 - alpha) * readings[2];

        float x = readings[0] - gravity[0];
        float y = readings[1] - gravity[1];
        float z = readings[2] - gravity[2];

        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()) //as we need to re-sample the actual sequence to a constant sample rate
                .append(",").append(x)
                .append(",").append(y)
                .append(",").append(z)
                .append(",").append(speed)
                .append(",").append(latitude)
                .append(",").append(longitude);
        fileResultsWriter.println(sb.toString());

        statementsLogged++;
        for (AccelerometerChangeListener listener : listeners) {
            listener.onAccelerometerChanged(x, y, z);
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
            try {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);

                startTimeMillis = System.currentTimeMillis();
                statementsLogged = 0;

                currentSession = new RecordingSession();
                currentSession.setStartTime(new Date());

                String shortFileName = "data" + currentSession.getStartTime().getTime() + ".log";
                currentSession.setDataFileFullPath(new File(getFilesDir(),
                        shortFileName).getAbsolutePath());
                recordingSessionDAO.open();
                currentSession = recordingSessionDAO.startNewRecordingSession(currentSession);

                outputStream = openFileOutput(shortFileName, Context.MODE_PRIVATE);
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, BUFFER_SIZE);
                fileResultsWriter = new PrintWriter(new OutputStreamWriter(bufferedOutputStream));
                writeHeading();
            } catch (FileNotFoundException e) {
                CloseUtils.closeStream(outputStream);
                throw new RuntimeException(e);
            }
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
        public void onAccelerometerChanged(float a, float b, float c);
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

        public long getStatementsLogged() {
            return statementsLogged;
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
