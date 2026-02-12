package com.safedrive.vision;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * Manages the drowsiness alarm sound and device vibration.
 *
 * <ul>
 *   <li>Sound: plays a bundled alarm from {@code res/raw/alarm} (falls back to
 *       the system alarm ringtone if the raw resource is missing).</li>
 *   <li>Vibration: repeating 500 ms on / 300 ms off pattern.</li>
 * </ul>
 *
 * Call {@link #release()} in {@code onDestroy()} to free resources.
 */
public class AlertManager {

    private static final String TAG = "AlertManager";

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private boolean isAlerting = false;
    private final Context context;

    public AlertManager(Context context) {
        this.context = context.getApplicationContext();

        // Obtain Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager)
                    this.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vibrator = vm.getDefaultVibrator();
            }
        } else {
            vibrator = (Vibrator) this.context.getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    /**
     * Start the alert (sound + vibration). Safe to call multiple times —
     * redundant calls are ignored while an alert is already active.
     */
    public synchronized void startAlert() {
        if (isAlerting) return;
        isAlerting = true;

        startSound();
        startVibration();
        Log.w(TAG, "Drowsiness alert STARTED");
    }

    /**
     * Stop the alert. Safe to call even when no alert is active.
     */
    public synchronized void stopAlert() {
        if (!isAlerting) return;
        isAlerting = false;

        stopSound();
        stopVibration();
        Log.d(TAG, "Drowsiness alert STOPPED");
    }

    /**
     * Release all resources. Must be called in {@code onDestroy()}.
     */
    public synchronized void release() {
        stopAlert();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        vibrator = null;
    }

    // ---- Sound ----

    private void startSound() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }

            // Try bundled alarm first
            int rawId = context.getResources().getIdentifier("alarm", "raw", context.getPackageName());
            if (rawId != 0) {
                mediaPlayer = MediaPlayer.create(context, rawId);
            }

            // Fallback to system alarm ringtone
            if (mediaPlayer == null) {
                Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (alarmUri == null) {
                    alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                }
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(context, alarmUri);
                mediaPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                );
                mediaPlayer.prepare();
            }

            if (mediaPlayer != null) {
                mediaPlayer.setLooping(true);
                mediaPlayer.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start alarm sound: " + e.getMessage(), e);
        }
    }

    private void stopSound() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping sound: " + e.getMessage(), e);
        }
    }

    // ---- Vibration ----

    private void startVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        try {
            // Pattern: 0ms delay, 500ms on, 300ms off — repeating from index 0
            long[] pattern = {0, 500, 300};

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start vibration: " + e.getMessage(), e);
        }
    }

    private void stopVibration() {
        try {
            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping vibration: " + e.getMessage(), e);
        }
    }
}
