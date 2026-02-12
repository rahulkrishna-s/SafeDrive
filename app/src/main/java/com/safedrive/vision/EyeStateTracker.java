package com.safedrive.vision;

import android.util.Log;

/**
 * State machine that distinguishes blinks from drowsiness using
 * <b>adaptive, calibrated thresholds</b> and sustained-closure time windows.
 *
 * <h3>Why adaptive?</h3>
 * Fixed EAR thresholds fail in real driving because the baseline EAR varies
 * hugely depending on the person's eye shape, phone mounting angle, and
 * lighting. This tracker calibrates itself during the first ~2 seconds by
 * measuring the driver's natural open-eye EAR, then sets thresholds as
 * percentages of that personal baseline. It also slowly re-adapts the baseline
 * over time to handle gradual lighting changes.
 *
 * <h3>States</h3>
 * <ol>
 *   <li><b>CALIBRATING</b> — collecting baseline EAR for ~2 s (keep eyes open)</li>
 *   <li><b>AWAKE</b> — normal driving</li>
 *   <li><b>EYES_CLOSED_PENDING</b> — EAR dropped below close threshold; waiting
 *       to see if this is a blink or genuine drowsiness</li>
 *   <li><b>DROWSY</b> — eyes closed for &ge; {@code DROWSY_DURATION_MS}</li>
 * </ol>
 */
public class EyeStateTracker {

    private static final String TAG = "EyeStateTracker";

    // ---- Calibration ----
    /** Number of frames to collect during calibration (~2 s at 30 fps). */
    private static final int CALIBRATION_FRAMES = 60;
    /** Fallback baseline if calibration produces an out-of-range value. */
    private static final float DEFAULT_BASELINE_EAR = 0.26f;

    // ---- Threshold ratios relative to personal baseline ----
    /** EAR must drop below baseline * CLOSE_RATIO to be "closed". */
    private static final float CLOSE_RATIO = 0.55f;
    /** EAR must rise above baseline * OPEN_RATIO to be "re-opened". */
    private static final float OPEN_RATIO  = 0.62f;

    // ---- Timing ----
    /** Eyes must stay closed at least this long to trigger a drowsy alert. */
    private static final long DROWSY_DURATION_MS = 1500L;

    // ---- Baseline slow-adaptation rate ----
    /** How fast the baseline tracks upward when eyes are clearly open. */
    private static final float BASELINE_ADAPT_RATE = 0.005f;

    // ---- Internal state machine ----
    private enum InternalState {
        CALIBRATING,
        AWAKE,
        EYES_CLOSED_PENDING,
        DROWSY
    }

    private InternalState internalState = InternalState.CALIBRATING;
    private DrowsinessState lastReportedState = DrowsinessState.AWAKE;
    private long eyeClosedTimestamp = 0L;

    // ---- Calibration accumulators ----
    private float calibrationSum = 0f;
    private int calibrationCount = 0;

    // ---- Adaptive thresholds ----
    private float baselineEar = DEFAULT_BASELINE_EAR;
    private float closeThreshold;
    private float openThreshold;

    private final DrowsinessListener listener;

    public EyeStateTracker(DrowsinessListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("DrowsinessListener must not be null");
        }
        this.listener = listener;
        recalculateThresholds();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Feed a new smoothed EAR value and the current timestamp.
     * Call once per processed frame from the MediaPipe result callback.
     *
     * @param smoothedEar          the smoothed (blended) EAR for this frame
     * @param timestampMs          current time in ms
     * @param calibrationEligible  true when the face is roughly frontal
     *                             (low yaw) and the EAR value is reliable
     *                             enough for baseline calibration
     */
    public void update(float smoothedEar, long timestampMs,
                       boolean calibrationEligible) {

        // ---- Recovery from FACE_NOT_DETECTED ----
        if (lastReportedState == DrowsinessState.FACE_NOT_DETECTED) {
            if (internalState != InternalState.CALIBRATING) {
                internalState = InternalState.AWAKE;
            }
            lastReportedState = DrowsinessState.AWAKE;
            listener.onStateChanged(DrowsinessState.AWAKE, smoothedEar);
        }

        switch (internalState) {

            case CALIBRATING:
                // Only accumulate calibration samples from frontal frames
                // to avoid basing the threshold on angle-distorted EAR.
                if (!calibrationEligible) break;

                calibrationSum += smoothedEar;
                calibrationCount++;

                if (calibrationCount >= CALIBRATION_FRAMES) {
                    baselineEar = calibrationSum / calibrationCount;

                    if (baselineEar < 0.10f || baselineEar > 0.50f) {
                        Log.w(TAG, "Calibration value " + baselineEar
                                + " out of range, using default");
                        baselineEar = DEFAULT_BASELINE_EAR;
                    }

                    recalculateThresholds();
                    internalState = InternalState.AWAKE;

                    Log.i(TAG, String.format(
                            "Calibration done: baseline=%.3f close=%.3f open=%.3f",
                            baselineEar, closeThreshold, openThreshold));

                    lastReportedState = DrowsinessState.AWAKE;
                    listener.onStateChanged(DrowsinessState.AWAKE, smoothedEar);
                }
                break;

            case AWAKE:
                // Slowly adapt baseline when eyes are clearly open
                if (smoothedEar > openThreshold) {
                    baselineEar += (smoothedEar - baselineEar) * BASELINE_ADAPT_RATE;
                    recalculateThresholds();
                }

                if (smoothedEar < closeThreshold) {
                    internalState = InternalState.EYES_CLOSED_PENDING;
                    eyeClosedTimestamp = timestampMs;
                    Log.d(TAG, String.format("Eyes closing (EAR=%.3f < %.3f)",
                            smoothedEar, closeThreshold));
                }
                break;

            case EYES_CLOSED_PENDING:
                if (smoothedEar > openThreshold) {
                    long blinkMs = timestampMs - eyeClosedTimestamp;
                    Log.d(TAG, "Blink (" + blinkMs + " ms)");
                    internalState = InternalState.AWAKE;
                } else if (timestampMs - eyeClosedTimestamp >= DROWSY_DURATION_MS) {
                    internalState = InternalState.DROWSY;
                    reportState(DrowsinessState.DROWSY, smoothedEar);
                    Log.w(TAG, "DROWSY! Eyes closed for "
                            + (timestampMs - eyeClosedTimestamp) + " ms");
                }
                break;

            case DROWSY:
                if (smoothedEar > openThreshold) {
                    internalState = InternalState.AWAKE;
                    reportState(DrowsinessState.AWAKE, smoothedEar);
                    Log.d(TAG, String.format("Awake again (EAR=%.3f)", smoothedEar));
                }
                break;
        }
    }

    /** Called when no face is detected (after the grace period). */
    public void onFaceNotDetected() {
        if (internalState != InternalState.CALIBRATING) {
            internalState = InternalState.AWAKE;
        }
        eyeClosedTimestamp = 0L;
        reportState(DrowsinessState.FACE_NOT_DETECTED, -1f);
    }

    /** @return true while the initial calibration phase is running. */
    public boolean isCalibrating() {
        return internalState == InternalState.CALIBRATING;
    }

    /** @return 0.0-1.0 calibration progress (1.0 = done). */
    public float getCalibrationProgress() {
        if (internalState != InternalState.CALIBRATING) return 1.0f;
        return (float) calibrationCount / CALIBRATION_FRAMES;
    }

    /** @return the current personal baseline EAR. */
    public float getBaselineEar() {
        return baselineEar;
    }

    public DrowsinessState getCurrentState() {
        return lastReportedState;
    }

    /** Reset everything (e.g., activity re-created). */
    public void reset() {
        internalState = InternalState.CALIBRATING;
        lastReportedState = DrowsinessState.AWAKE;
        eyeClosedTimestamp = 0L;
        calibrationSum = 0f;
        calibrationCount = 0;
        baselineEar = DEFAULT_BASELINE_EAR;
        recalculateThresholds();
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private void recalculateThresholds() {
        closeThreshold = baselineEar * CLOSE_RATIO;
        openThreshold  = baselineEar * OPEN_RATIO;
    }

    private void reportState(DrowsinessState newState, float earScore) {
        if (newState != lastReportedState) {
            lastReportedState = newState;
            listener.onStateChanged(newState, earScore);
        }
    }
}
