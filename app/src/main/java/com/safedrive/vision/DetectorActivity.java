package com.safedrive.vision;

import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera activity that runs the drowsiness detection pipeline.
 *
 * <p>Architecture: the Activity owns a {@link SleepDetector} (currently
 * {@link MediaPipeSleepDetector}) and reacts to state changes via the
 * {@link DrowsinessListener} callback — no polling.</p>
 *
 * <p>Supports Picture-in-Picture mode so the driver can use navigation
 * apps while detection continues. In PiP the camera preview remains visible
 * (like a smart mirror) and a colored border indicates status.</p>
 */
public class DetectorActivity extends AppCompatActivity implements DrowsinessListener {

    private static final String TAG = "DetectorActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;

    /** Border width in dp for the PiP status indicator. */
    private static final int PIP_BORDER_DP = 6;

    // ---- UI ----
    private PreviewView viewFinder;
    private TextView statusTextView;
    private ViewGroup rootLayout;

    // ---- Detection ----
    private SleepDetector sleepDetector;
    private ExecutorService cameraExecutor;
    private AlertManager alertManager;

    // ---- State tracking ----
    private DrowsinessState currentState = DrowsinessState.AWAKE;
    private boolean isActivityStopped = false;

    // ---- PiP border (padding + background color) ----
    private int borderPx;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detector);

        // Keep screen on while monitoring
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        viewFinder      = findViewById(R.id.viewFinder);
        statusTextView  = findViewById(R.id.statusTextView);
        rootLayout      = findViewById(R.id.main);

        // Border via padding + background color.
        // The padding pushes the PreviewView inward, and the background color
        // fills the padding area — creating a solid border fully INSIDE the
        // window that PiP can never clip (unlike GradientDrawable stroke).
        borderPx = (int) (PIP_BORDER_DP * getResources().getDisplayMetrics().density);
        rootLayout.setPadding(borderPx, borderPx, borderPx, borderPx);
        rootLayout.setBackgroundColor(0xFF00CC00); // start green
        rootLayout.setClipToPadding(false);

        // Show calibration message — will be replaced by "Status: Normal"
        // when EyeStateTracker finishes its ~2-second calibration phase.
        statusTextView.setText("Calibrating\u2026 Keep eyes open");
        statusTextView.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_blue_light));

        // Alert manager (sound + vibration)
        alertManager = new AlertManager(this);

        // Detector setup — FaceLandmarker loads on a background thread
        MediaPipeSleepDetector detector = new MediaPipeSleepDetector(this);
        detector.setDrowsinessListener(this);
        sleepDetector = detector;

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        isActivityStopped = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // When activity is fully stopped (not just paused for PiP):
        // In PiP, onPause is called but NOT onStop — camera keeps running.
        // onStop means the PiP window was dismissed or the task was removed.
        if (!isInPictureInPictureMode()) {
            isActivityStopped = true;
            alertManager.stopAlert();
        }
    }

    @Override
    protected void onDestroy() {
        // Aggressively stop everything to prevent zombie alarms
        if (alertManager != null) {
            alertManager.stopAlert();
            alertManager.release();
        }
        if (sleepDetector != null) {
            sleepDetector.close();
        }
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // DrowsinessListener callback (called on MediaPipe's internal thread)
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged(DrowsinessState newState, float earScore) {
        currentState = newState;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            // Handle alert sound + vibration
            if (newState == DrowsinessState.DROWSY) {
                alertManager.startAlert();
            } else {
                alertManager.stopAlert();
            }

            // Always update the border color (visible in both modes)
            updateBorderColor(newState);

            // Update text only when NOT in PiP (text is unreadable in PiP)
            if (!isInPictureInPictureMode()) {
                updateNormalUi(newState, earScore);
            }
        });
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void updateNormalUi(DrowsinessState state, float earScore) {
        switch (state) {
            case DROWSY:
                statusTextView.setText("DROWSY ALERT!");
                statusTextView.setTextColor(
                        ContextCompat.getColor(this, android.R.color.holo_red_dark));
                break;
            case FACE_NOT_DETECTED:
                statusTextView.setText("Face not detected");
                statusTextView.setTextColor(
                        ContextCompat.getColor(this, android.R.color.holo_orange_light));
                break;
            case AWAKE:
            default:
                statusTextView.setText("Status: Normal");
                statusTextView.setTextColor(
                        ContextCompat.getColor(this, android.R.color.white));
                break;
        }
    }

    /**
     * Update the border color around the preview. Visible in both normal
     * and PiP modes — in PiP it acts as the primary status indicator.
     */
    private void updateBorderColor(DrowsinessState state) {
        int color;
        switch (state) {
            case DROWSY:
                color = 0xFFFF0000; // Red
                break;
            case FACE_NOT_DETECTED:
                color = 0xFFFF8800; // Orange
                break;
            case AWAKE:
            default:
                color = 0xFF00CC00; // Green
                break;
        }
        rootLayout.setBackgroundColor(color);
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetResolution(new Size(1280, 720))
                        .build();

                // The listener callback handles UI — no polling needed here
                imageAnalysis.setAnalyzer(cameraExecutor,
                        image -> sleepDetector.processFrame(image));

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector,
                        preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed: " + e.getMessage(), e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ------------------------------------------------------------------
    // Picture-in-Picture
    // ------------------------------------------------------------------

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPipMode();
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(3, 4))  // portrait aspect for face cam
                    .build();
            enterPictureInPictureMode(params);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP,
                                               @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPiP, newConfig);

        if (isInPiP) {
            // Entering PiP — hide text (unreadable at small size),
            // keep camera preview + border visible
            statusTextView.setVisibility(View.GONE);
            updateBorderColor(currentState);
        } else {
            // Exiting PiP — restore text
            statusTextView.setVisibility(View.VISIBLE);
            updateNormalUi(currentState, -1f);

            // If the user dismissed PiP (which triggers onStop), stop alarm
            if (isActivityStopped) {
                alertManager.stopAlert();
            }
        }
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Camera permission is required to detect drowsiness.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
