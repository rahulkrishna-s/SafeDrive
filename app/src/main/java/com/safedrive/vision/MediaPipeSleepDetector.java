package com.safedrive.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * MediaPipe Face Landmarker–based drowsiness detector.
 *
 * <p>Processes RGBA camera frames via CameraX, computes the Eye Aspect Ratio
 * (EAR) from 478-point face mesh landmarks, smooths the EAR with a rolling
 * average, and feeds the result to a hysteresis-based state machine
 * ({@link EyeStateTracker}) that distinguishes blinks from drowsiness.</p>
 *
 * <h3>Memory optimizations</h3>
 * <ul>
 *   <li>Reusable {@link Bitmap} — only reallocated when frame dimensions change.</li>
 *   <li>No per-frame Bitmap allocation via {@code ImageProxy.toBitmap()}.</li>
 * </ul>
 */
public class MediaPipeSleepDetector implements SleepDetector {

    private static final String TAG = "MediaPipeSleepDetector";
    private static final String MODEL_PATH = "face_landmarker.task";

    // ---- MediaPipe ----
    private FaceLandmarker faceLandmarker;
    private volatile boolean isClosed = false;

    // ---- Reusable Bitmap for frame conversion ----
    private Bitmap reusableBitmap;
    private int lastWidth = -1;
    private int lastHeight = -1;

    // ---- Detection pipeline ----
    private final EarSmoother earSmoother;
    private EyeStateTracker eyeStateTracker;

    // ---- Landmark indices (MediaPipe 478-point face mesh) ----
    // Left eye
    private static final int L_EYE_LATERAL  = 362;
    private static final int L_EYE_UPPER_1  = 385;
    private static final int L_EYE_UPPER_2  = 387;
    private static final int L_EYE_MEDIAL   = 263;
    private static final int L_EYE_LOWER_1  = 373;
    private static final int L_EYE_LOWER_2  = 380;
    // Right eye
    private static final int R_EYE_LATERAL  = 33;
    private static final int R_EYE_UPPER_1  = 160;
    private static final int R_EYE_UPPER_2  = 158;
    private static final int R_EYE_MEDIAL   = 133;
    private static final int R_EYE_LOWER_1  = 153;
    private static final int R_EYE_LOWER_2  = 144;

    // ---- Yaw estimation landmarks ----
    private static final int NOSE_TIP          = 1;
    private static final int LEFT_FACE_CONTOUR = 234;
    private static final int RIGHT_FACE_CONTOUR = 454;

    // ---- Yaw thresholds for eye selection ----
    /** Below this |yawRatio| both eyes are averaged (roughly <15°). */
    private static final float YAW_BOTH_EYES   = 0.20f;
    /** Above this |yawRatio| only the near eye is used (roughly >25°). */
    private static final float YAW_NEAR_EYE_ONLY = 0.35f;

    /**
     * Creates the detector. FaceLandmarker initialization runs on a background
     * thread so the calling Activity doesn't block on model loading.
     * Frames arriving before init completes are safely dropped.
     */
    public MediaPipeSleepDetector(Context context) {
        earSmoother = new EarSmoother(3);
        // Initialize MediaPipe on a background thread to avoid startup lag
        new Thread(() -> setupFaceLandmarker(context), "MediaPipeInit").start();
    }

    // ------------------------------------------------------------------
    // SleepDetector interface
    // ------------------------------------------------------------------

    @Override
    public void setDrowsinessListener(DrowsinessListener listener) {
        eyeStateTracker = new EyeStateTracker(listener);
    }

    @Override
    public void processFrame(ImageProxy image) {
        if (isClosed || faceLandmarker == null) {
            image.close();
            return;
        }

        try {
            // Reuse Bitmap — only reallocate when dimensions change
            int w = image.getWidth();
            int h = image.getHeight();
            if (reusableBitmap == null || w != lastWidth || h != lastHeight) {
                if (reusableBitmap != null) {
                    reusableBitmap.recycle();
                }
                reusableBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                lastWidth = w;
                lastHeight = h;
                Log.d(TAG, "Allocated reusable Bitmap: " + w + "x" + h);
            }

            // Copy RGBA pixels directly from the ImageProxy buffer into the reusable Bitmap.
            // DetectorActivity sets OUTPUT_IMAGE_FORMAT_RGBA_8888, so plane[0] is RGBA.
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();
            reusableBitmap.copyPixelsFromBuffer(buffer);

            MPImage mpImage = new BitmapImageBuilder(reusableBitmap).build();

            long frameTime = SystemClock.uptimeMillis();
            faceLandmarker.detectAsync(mpImage, frameTime);

        } catch (Exception e) {
            Log.e(TAG, "Error processing frame: " + e.getMessage(), e);
        } finally {
            image.close();
        }
    }

    @Override
    public void close() {
        isClosed = true;
        if (faceLandmarker != null) {
            faceLandmarker.close();
            faceLandmarker = null;
        }
        if (reusableBitmap != null) {
            reusableBitmap.recycle();
            reusableBitmap = null;
        }
        earSmoother.reset();
        if (eyeStateTracker != null) {
            eyeStateTracker.reset();
        }
        Log.d(TAG, "MediaPipeSleepDetector closed");
    }

    // ------------------------------------------------------------------
    // FaceLandmarker setup (GPU → CPU fallback)
    // ------------------------------------------------------------------

    private void setupFaceLandmarker(Context context) {
        // Try GPU first for better performance
        faceLandmarker = tryCreateLandmarker(context, Delegate.GPU);

        if (faceLandmarker == null) {
            // Fall back to CPU
            Log.w(TAG, "GPU delegate unavailable — falling back to CPU");
            faceLandmarker = tryCreateLandmarker(context, Delegate.CPU);
        }

        if (faceLandmarker == null) {
            Log.e(TAG, "Failed to create FaceLandmarker with any delegate!");
        }
    }

    private FaceLandmarker tryCreateLandmarker(Context context, Delegate delegate) {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .setDelegate(delegate)
                    .build();

            FaceLandmarker.FaceLandmarkerOptions options =
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumFaces(1)
                            .setMinFaceDetectionConfidence(0.15f)
                            .setMinFacePresenceConfidence(0.15f)
                            .setMinTrackingConfidence(0.3f)
                            .setResultListener(this::processResult)
                            .setErrorListener(e ->
                                    Log.e(TAG, "FaceLandmarker error: " + e.getMessage()))
                            .build();

            FaceLandmarker lm = FaceLandmarker.createFromOptions(context, options);
            Log.i(TAG, "FaceLandmarker created with " + delegate + " delegate");
            return lm;

        } catch (Exception e) {
            Log.w(TAG, "Failed to create FaceLandmarker with " + delegate
                    + ": " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Result processing — runs on MediaPipe's internal callback thread
    // ------------------------------------------------------------------

    // Track consecutive no-face frames to avoid spamming FACE_NOT_DETECTED
    // on single-frame tracking glitches
    private int consecutiveNoFaceFrames = 0;
    private static final int NO_FACE_GRACE_FRAMES = 8;

    private void processResult(FaceLandmarkerResult result, MPImage image) {
        if (isClosed || eyeStateTracker == null) return;

        if (result.faceLandmarks().isEmpty()) {
            consecutiveNoFaceFrames++;
            if (consecutiveNoFaceFrames >= NO_FACE_GRACE_FRAMES) {
                earSmoother.reset();
                eyeStateTracker.onFaceNotDetected();
            }
            return;
        }

        // Face found — reset the no-face counter
        consecutiveNoFaceFrames = 0;

        List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);

        // ---- Head yaw estimation ----
        // yawRatio: -1.0 (turned left) … 0.0 (frontal) … +1.0 (turned right)
        float yawRatio = calculateYawRatio(landmarks);
        float absYaw = Math.abs(yawRatio);

        // ---- EAR calculation per eye ----
        float leftEar = calculateEAR(landmarks,
                L_EYE_LATERAL, L_EYE_UPPER_1, L_EYE_UPPER_2,
                L_EYE_MEDIAL,  L_EYE_LOWER_1, L_EYE_LOWER_2);

        float rightEar = calculateEAR(landmarks,
                R_EYE_LATERAL, R_EYE_UPPER_1, R_EYE_UPPER_2,
                R_EYE_MEDIAL,  R_EYE_LOWER_1, R_EYE_LOWER_2);

        // ---- Yaw-adaptive EAR blending ----
        // At large yaw angles, the "far" eye is heavily foreshortened and
        // its 2D EAR is unreliable. We progressively rely on the "near" eye.
        //   |yaw| < 0.20  → average both eyes (frontal)
        //   0.20..0.35    → weighted blend toward near eye
        //   |yaw| ≥ 0.35  → near eye only
        float blendedEar;
        if (absYaw < YAW_BOTH_EYES) {
            // Frontal — simple average
            blendedEar = (leftEar + rightEar) / 2.0f;
        } else {
            // Determine which eye is "near" (closer to camera)
            // yawRatio > 0 → turned right → left eye is near camera
            // yawRatio < 0 → turned left  → right eye is near camera
            float nearEar = (yawRatio > 0) ? leftEar : rightEar;
            float farEar  = (yawRatio > 0) ? rightEar : leftEar;

            if (absYaw >= YAW_NEAR_EYE_ONLY) {
                // Large angle — trust only the near eye
                blendedEar = nearEar;
            } else {
                // Transition zone — linearly blend from 50/50 to 100% near eye
                float t = (absYaw - YAW_BOTH_EYES)
                        / (YAW_NEAR_EYE_ONLY - YAW_BOTH_EYES);
                // t goes from 0.0 (at YAW_BOTH_EYES) to 1.0 (at YAW_NEAR_EYE_ONLY)
                // nearWeight goes from 0.5 to 1.0
                float nearWeight = 0.5f + 0.5f * t;
                blendedEar = nearEar * nearWeight + farEar * (1.0f - nearWeight);
            }
        }

        float smoothedEar = earSmoother.addAndGetAverage(blendedEar);

        // Only allow calibration frames when face is roughly frontal
        boolean calibrationEligible = (absYaw < 0.15f);
        eyeStateTracker.update(smoothedEar, System.currentTimeMillis(),
                calibrationEligible);

        Log.d(TAG, String.format(
                "EAR L=%.3f R=%.3f blend=%.3f smooth=%.3f yaw=%.2f near=%s state=%s",
                leftEar, rightEar, blendedEar, smoothedEar, yawRatio,
                (absYaw >= YAW_BOTH_EYES) ? (yawRatio > 0 ? "LEFT" : "RIGHT") : "BOTH",
                eyeStateTracker.getCurrentState()));
    }

    // ------------------------------------------------------------------
    // Yaw estimation
    // ------------------------------------------------------------------

    /**
     * Estimates head yaw from the nose-to-face-edge distance ratio.
     * Uses only reliable x-coordinates (not noisy z).
     *
     * @return yawRatio in [-1, +1]. 0 = frontal,
     *         positive = turned right, negative = turned left.
     */
    private float calculateYawRatio(List<NormalizedLandmark> landmarks) {
        float noseX  = landmarks.get(NOSE_TIP).x();
        float leftX  = landmarks.get(LEFT_FACE_CONTOUR).x();
        float rightX = landmarks.get(RIGHT_FACE_CONTOUR).x();

        float leftDist  = Math.abs(noseX - leftX);
        float rightDist = Math.abs(noseX - rightX);
        float sum = leftDist + rightDist;

        if (sum == 0f) return 0f;
        return (rightDist - leftDist) / sum;
    }

    // ------------------------------------------------------------------
    // EAR math
    // ------------------------------------------------------------------

    /**
     * Eye Aspect Ratio:
     * <pre>EAR = (||p2 - p6|| + ||p3 - p5||) / (2 * ||p1 - p4||)</pre>
     * where p1–p4 are horizontal corners and p2,p6 / p3,p5 are vertical pairs.
     */
    private float calculateEAR(List<NormalizedLandmark> landmarks,
                               int p1, int p2, int p3, int p4, int p5, int p6) {
        float vertDist1 = distance(landmarks.get(p2), landmarks.get(p6));
        float vertDist2 = distance(landmarks.get(p3), landmarks.get(p5));
        float horizDist = distance(landmarks.get(p1), landmarks.get(p4));

        if (horizDist == 0f) return 0f; // guard against division by zero

        return (vertDist1 + vertDist2) / (2.0f * horizDist);
    }

    /** 2D Euclidean distance between two normalized landmarks. */
    private float distance(NormalizedLandmark a, NormalizedLandmark b) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
