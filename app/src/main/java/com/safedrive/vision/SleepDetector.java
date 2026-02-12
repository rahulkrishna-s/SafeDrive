package com.safedrive.vision;

import androidx.camera.core.ImageProxy;

/**
 * Abstraction for a drowsiness detector that processes camera frames.
 *
 * <p>Implementations (e.g. {@link MediaPipeSleepDetector}) handle the ML
 * inference internally and notify the registered {@link DrowsinessListener}
 * on state changes — the Activity never polls.</p>
 *
 * <p>Future-proof: a {@code YawnAwareSleepDetector} or any other variant
 * can implement this interface without changing the Activity.</p>
 */
public interface SleepDetector {

    /**
     * Send a camera frame for analysis. The implementation must call
     * {@link ImageProxy#close()} when done.
     */
    void processFrame(ImageProxy image);

    /**
     * Register a listener for drowsiness state changes.
     */
    void setDrowsinessListener(DrowsinessListener listener);

    /**
     * Release all resources (MediaPipe model, native memory, etc.).
     * Must be called in the Activity's {@code onDestroy()}.
     */
    void close();
}
