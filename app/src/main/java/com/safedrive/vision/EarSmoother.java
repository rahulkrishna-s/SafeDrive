package com.safedrive.vision;

/**
 * Rolling average filter for Eye Aspect Ratio values.
 * Smooths out single-frame noise/outliers before feeding to the state machine.
 *
 * At 30 fps a window of 5 frames spans ~167ms — short enough to not delay
 * real drowsiness detection but long enough to filter noise.
 */
public class EarSmoother {

    private final float[] buffer;
    private final int capacity;
    private int count;
    private int index;
    private float sum;

    /**
     * @param windowSize number of frames to average over (default: 5)
     */
    public EarSmoother(int windowSize) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be >= 1");
        }
        this.capacity = windowSize;
        this.buffer = new float[windowSize];
        this.count = 0;
        this.index = 0;
        this.sum = 0f;
    }

    public EarSmoother() {
        this(5);
    }

    /**
     * Add a raw EAR value and return the smoothed (rolling average) value.
     *
     * @param rawEar the raw EAR computed from the current frame
     * @return smoothed EAR value
     */
    public float addAndGetAverage(float rawEar) {
        if (count < capacity) {
            // Buffer not full yet — just accumulate
            buffer[index] = rawEar;
            sum += rawEar;
            count++;
        } else {
            // Buffer full — subtract the oldest value, add the new one
            sum -= buffer[index];
            buffer[index] = rawEar;
            sum += rawEar;
        }
        index = (index + 1) % capacity;
        return sum / count;
    }

    /**
     * Reset the smoother (e.g., when face is lost and re-detected).
     */
    public void reset() {
        count = 0;
        index = 0;
        sum = 0f;
    }
}
