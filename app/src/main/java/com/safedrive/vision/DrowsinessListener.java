package com.safedrive.vision;

/**
 * Callback interface for drowsiness state changes.
 * Only fires on actual state transitions, not every frame.
 *
 * Future-proof: when yawn detection is added, either add YAWNING to
 * {@link DrowsinessState} or add a default method onYawnDetected(float ratio).
 */
public interface DrowsinessListener {

    /**
     * Called when the drowsiness state changes.
     *
     * @param newState the new detection state
     * @param earScore the current smoothed Eye Aspect Ratio (0 = fully closed, ~0.3 = open).
     *                 May be {@code -1f} when no face is detected.
     */
    void onStateChanged(DrowsinessState newState, float earScore);
}
