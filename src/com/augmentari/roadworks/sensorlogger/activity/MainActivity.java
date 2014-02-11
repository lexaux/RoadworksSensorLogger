package com.augmentari.roadworks.sensorlogger.activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.augmentari.roadworks.sensorlogger.R;
import com.augmentari.roadworks.sensorlogger.component.AccelerometerGraphView;
import com.augmentari.roadworks.sensorlogger.component.CircularBuffer;
import com.augmentari.roadworks.sensorlogger.service.DataUploaderService;
import com.augmentari.roadworks.sensorlogger.service.SensorLoggerService;
import com.augmentari.roadworks.sensorlogger.util.Constants;
import com.augmentari.roadworks.sensorlogger.util.Formats;
import com.augmentari.roadworks.sensorlogger.util.Log;
import com.bugsense.trace.BugSenseHandler;

import java.util.Timer;

import java.util.TimerTask;


public class MainActivity extends Activity {
    private enum ServiceState {
        DISCONNECTED,
        STARTED,
        STOPPED
    }

    private Timer timer = null;

    public static final int UPDATE_PERIOD_MSEC = 2000;

    private Button serviceControlButton;
    private ServiceState serviceState = ServiceState.DISCONNECTED;

    private TextView timeLoggedTextView;
    private TextView statementsLoggedTextView;

    private AccelerometerGraphView accelerometerGraph;

    private SensorLoggerService.SessionLoggerServiceBinder binder = null;

    private Handler statsUpdateHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (binder.hasGPSfix()) {
                statementsLoggedTextView.setText(Formats.formatWithSuffices(binder.getStatementsLogged()));
                timeLoggedTextView.setText(Long.toString((System.currentTimeMillis() - binder.getStartTimeMillis()) / 1000));

            } else {
                statementsLoggedTextView.setText(getString(R.string.sensor_logger_service_notification_waiting_GPS_text));
                timeLoggedTextView.setText(getString(R.string.sensor_logger_service_notification_waiting_GPS_text));
            }
        }
    };

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (SensorLoggerService.SessionLoggerServiceBinder) service;

            binder.addAccelChangedListener(accelerometerGraph);
            if (binder.isStarted()) {
                setServiceState(ServiceState.STARTED);
            } else {
                setServiceState(ServiceState.STOPPED);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder.removeAccelChangedListener(accelerometerGraph);
            binder = null;
            setServiceState(ServiceState.DISCONNECTED);
        }
    };

    private View.OnClickListener buttonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.serviceControlButton:
                    switch (serviceState) {
                        case DISCONNECTED:
                            throw new IllegalArgumentException("Should not be accessible");

                        case STARTED:
                            Intent stopServiceIntent = new Intent(MainActivity.this, SensorLoggerService.class);
                            stopService(stopServiceIntent);

                            unbindService(connection);

                            Intent intent = new Intent(MainActivity.this, SensorLoggerService.class);
                            bindService(intent, connection, BIND_AUTO_CREATE);

                            setServiceState(ServiceState.STOPPED);
                            break;

                        case STOPPED:
                            startService(new Intent(MainActivity.this, SensorLoggerService.class));
                            setServiceState(ServiceState.STARTED);
                            break;
                    }
                    break;
                default:
                    Log.logNotImplemented(MainActivity.this);
                    break;
            }
        }
    };


    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BugSenseHandler.initAndStartSession(this, Constants.BUGSENSE_API_KEY);

        setContentView(R.layout.main);
        serviceControlButton = (Button) findViewById(R.id.serviceControlButton);
        serviceControlButton.setOnClickListener(buttonOnClickListener);

        statementsLoggedTextView = (TextView) findViewById(R.id.statementsLoggedTextView);
        timeLoggedTextView = (TextView) findViewById(R.id.timeLoggedTextView);
        LinearLayout l = (LinearLayout) findViewById(R.id.chartContainer);

        accelerometerGraph = (AccelerometerGraphView) findViewById(R.id.accelerometerGraph);
    }

    public void setServiceState(ServiceState serviceState) {
        switch (serviceState) {
            case DISCONNECTED:
                serviceControlButton.setEnabled(false);
                serviceControlButton.setText(getString(R.string.service_disconnected));
                serviceControlButton.setBackgroundColor(Color.GRAY);
                break;

            case STARTED:
                statsUpdateHandler.sendEmptyMessage(0);

                serviceControlButton.setEnabled(true);
                serviceControlButton.setText(getString(R.string.service_stop_command));
                serviceControlButton.setBackgroundColor(Color.RED);
                break;

            case STOPPED:
                serviceControlButton.setEnabled(true);
                serviceControlButton.setText(getString(R.string.service_start_command));
                serviceControlButton.setBackgroundColor(Color.GREEN);
                break;
        }
        this.serviceState = serviceState;
    }

    private CircularBuffer buffer = null;

    @Override
    protected void onPause() {
        super.onPause();
        this.buffer = accelerometerGraph.getBuffer();
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (this.buffer != null) {
            accelerometerGraph.setBuffer(buffer);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        timer = new Timer("SensorLoggerService.BroadcastResultsUpdater");
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (serviceState == ServiceState.STARTED) {
                    statsUpdateHandler.sendEmptyMessage(0);
                }
            }
        }, 0, UPDATE_PERIOD_MSEC);


        setServiceState(ServiceState.DISCONNECTED);

        Intent intent = new Intent(this, SensorLoggerService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        timer.cancel();

        if (binder != null) {
            unbindService(connection);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_activity_options, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.showSessionList:
                Intent showResultsList = new Intent(this, SessionListActivity.class);
                startActivity(showResultsList);
                break;

            case R.id.settingsMenu:
                Intent settingsActivityIntent = new Intent(this, PreferencesActivity.class);
                startActivity(settingsActivityIntent);
                break;

            case R.id.manualUploadData:
                Intent startUploadDataService = new Intent(this, DataUploaderService.class);
                startService(startUploadDataService);
                break;

        }
        return true;
    }


}
