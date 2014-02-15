package com.augmentari.roadworks.sensorlogger.detector;

import android.content.Context;
import android.preference.PreferenceManager;
import android.widget.Toast;
import com.augmentari.roadworks.model.RecordingSession;
import com.augmentari.roadworks.sensorlogger.R;
import com.augmentari.roadworks.sensorlogger.component.CircularBuffer;
import com.augmentari.roadworks.sensorlogger.dao.RecordingSessionDAO;
import com.augmentari.roadworks.sensorlogger.service.SensorLoggerService;
import com.augmentari.roadworks.sensorlogger.util.CloseUtils;
import com.augmentari.roadworks.sensorlogger.util.Formats;

import java.io.*;
import java.text.MessageFormat;
import java.util.Date;

/**
 * This detector keeps track of SQL data objects related to sessions of recording, as well as of the actual data files
 * stored on the filesystem.
 */
public class FileLoggingDetector implements SensorLoggerService.AccelerometerChangeListener {
    // size of the buffer for the file stream; .5M for a start
    public static final int BUFFER_SIZE = 512 * 1024;
    public static final double MINIMAL_SEVERITY_TO_RECORD = 2d;

    private long potholesLogged;

    private PrintWriter fileResultsWriter;
    private final RecordingSessionDAO recordingSessionDAO;
    private RecordingSession currentSession = null;
    private Context context;
    private float threshold = 1;
    private float previousAccelerometerValue;

    private double startLatitude;
    private double startLongitude;
    private double startSpeed;

    private double endLatitude;
    private double endLongitude;
    private double endSpeed;
    private double cumulativeSeverity = 0d;


    public FileLoggingDetector(Context context) {
        this.context = context;
        recordingSessionDAO = new RecordingSessionDAO(context);
    }

    @Override
    public void onAccelerometerChanged(float lastAccelFilteredDiffValue, CircularBuffer wholeBuffer, double latitude, double longitude, double speed) {
        if (lastAccelFilteredDiffValue > threshold && previousAccelerometerValue < threshold) {
            cumulativeSeverity = 0d;
            startLatitude = latitude;
            startLongitude = longitude;
            startSpeed = speed;
        }

        if (lastAccelFilteredDiffValue > threshold) {
            cumulativeSeverity += lastAccelFilteredDiffValue - threshold;
        }

        if (lastAccelFilteredDiffValue < threshold && previousAccelerometerValue > threshold) {
            endLatitude = latitude;
            endLongitude = longitude;
            endSpeed = speed;
            recordReading();
        }

        previousAccelerometerValue = lastAccelFilteredDiffValue;
    }

    @Override
    public void onNewSessionStarted() {
        //TODO #18 refactor out the configuration
        try {
            threshold = Float.valueOf(PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getString("pref_detection_threshold", ""));
        } catch (NumberFormatException ex) {
            Toast.makeText(context, "Wrong setting for detection threshold. Defaulting to 2.", Toast.LENGTH_LONG).show();
        }

        try {
            potholesLogged = 0;

            currentSession = new RecordingSession();
            currentSession.setStartTime(new Date());

            String shortFileName = "data" + currentSession.getStartTime().getTime() + ".log";
            currentSession.setDataFileFullPath(new File(context.getFilesDir(),
                    shortFileName).getAbsolutePath());
            recordingSessionDAO.open();
            currentSession = recordingSessionDAO.startNewRecordingSession(currentSession);

            fileResultsWriter = new PrintWriter(
                    new BufferedWriter(
                            new OutputStreamWriter(context.openFileOutput(shortFileName, Context.MODE_PRIVATE))
                            , BUFFER_SIZE));
            writeHeading();
        } catch (FileNotFoundException e) {
            CloseUtils.closeStream(fileResultsWriter);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onSessionClosed() {
        CloseUtils.closeStream(fileResultsWriter);

        if (currentSession != null) {

            recordingSessionDAO.open();
            recordingSessionDAO.finishSession(currentSession.getId(), potholesLogged, new Date());
            CloseUtils.closeDao(recordingSessionDAO);

            Toast.makeText(
                    context,
                    MessageFormat.format(
                            context.getString(R.string.fileCollectedSizeMessage),
                            Formats.formatReadableBytesSize(new File(currentSession.getDataFileFullPath()).length())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void writeHeading() {
        fileResultsWriter.println("Time, Severity, Lat, Lon, Speed");
    }

    private void recordReading() {

        if (cumulativeSeverity < MINIMAL_SEVERITY_TO_RECORD) {
            // despite this being over the threshold, we skip it from recording since it is quite minimal
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()) //as we need to re-sample the actual sequence to a constant sample rate
                .append(",").append(cumulativeSeverity)
                .append(",").append((startLatitude + endLatitude) / 2)
                .append(",").append((startLongitude + endLongitude) / 2)
                .append(",").append((startSpeed + endSpeed) / 2);
        fileResultsWriter.println(sb.toString());

        potholesLogged++;
    }

    public long getPotholesLogged() {
        return potholesLogged;
    }
}
