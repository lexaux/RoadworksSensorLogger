package com.augmentari.roadworks.sensorlogger.detector;

import android.content.Context;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.widget.Toast;
import com.augmentari.roadworks.sensorlogger.R;
import com.augmentari.roadworks.sensorlogger.component.CircularBuffer;
import com.augmentari.roadworks.sensorlogger.service.SensorLoggerService;

/**
 * A small utility class which captures 'shake' event and plays a little sound notification.
 */
public class ShakeDetectorSound implements SensorLoggerService.AccelerometerChangeListener {
    private float prevValue = 0f;
    private float threshold = 1;
    private final Ringtone ringtone;

    public ShakeDetectorSound(Context context) {
        // TODO refactor this out to more common place, also same code used in AccelGraphView
        try {
            threshold = Float.valueOf(PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getString("pref_detection_threshold", ""));
        } catch (NumberFormatException ex) {
            Toast.makeText(context, "Wrong setting for detection threshold. Defaulting to 2.", Toast.LENGTH_LONG).show();
        }
        Uri soundURI = Uri.parse("android.resource://com.augmentari.roadworks.sensorlogger/" + R.raw.beep_29);
        ringtone = RingtoneManager.getRingtone(context, soundURI);
    }

    @Override
    public void onAccelerometerChanged(float accelFilteredDiffValue, CircularBuffer wholeBuffer) {
        if (accelFilteredDiffValue > threshold && prevValue < threshold) {
            ringtone.play();
        }

        prevValue = accelFilteredDiffValue;
    }
}
