package com.safedrive.vision;

/**
 * Represents the current drowsiness detection state.
 * Extensible for future states like YAWNING.
 */
public enum DrowsinessState {
    AWAKE("Awake"),
    DROWSY("Drowsy"),
    FACE_NOT_DETECTED("Face Not Detected");

    private final String label;

    DrowsinessState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
