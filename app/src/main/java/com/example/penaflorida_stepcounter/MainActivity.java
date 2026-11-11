package com.example.penaflorida_stepcounter;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Collections;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int CAMERA_PERMISSION_CODE = 200;
    private static final int ACTIVITY_RECOGNITION_CODE = 100;

    private SensorManager sensorManager;
    private Sensor stepCounterSensor;
    private Sensor stepDetectorSensor;
    private Sensor accelerometerSensor;
    private Sensor lightSensor;
    
    private TextView stepsTextView;
    private TextView stepsLabelTextView;
    private TextView statusTextView;
    private MaterialButton startStopButton;
    private MaterialButton resetButton;
    private TextView lightValueTextView;
    private TextView lightConditionTextView;
    private TextView lightSourceTextView;
    private MaterialCardView sensorInfoCard;
    private Toolbar toolbar;

    private boolean isTracking = false;
    
    private boolean hasStepCounter = false;
    private boolean hasStepDetector = false;
    private boolean hasAccelerometer = false;
    private boolean hasPhysicalLightSensor = false;
    private boolean isCameraLightActive = false;
    private float currentLux = 0f;

    private float stepsBaseline = -1f;
    private int accumulatedSteps = 0;
    private int currentSessionSteps = 0;

    private float lastFilteredMag = 0f;
    private long lastStepTime = 0;
    
    private final float[] windowBuffer = new float[50]; 
    private int windowIndex = 0;
    private boolean windowFull = false;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    private static final String PREFS_NAME = "StepCounterPrefs";
    private static final String KEY_ACCUMULATED = "accumulated_steps";
    private static final String KEY_IS_TRACKING = "is_tracking";
    private static final String KEY_BASELINE = "baseline";

    private View mainLayout;
    private MaterialCardView progressCardBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        mainLayout = findViewById(R.id.main);
        toolbar = findViewById(R.id.toolbar);
        if (mainLayout != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        stepsTextView = findViewById(R.id.stepsText);
        stepsLabelTextView = findViewById(R.id.stepsLabel);
        statusTextView = findViewById(R.id.statusText);
        startStopButton = findViewById(R.id.startStopButton);
        resetButton = findViewById(R.id.resetButton);
        lightValueTextView = findViewById(R.id.lightValueText);
        lightConditionTextView = findViewById(R.id.lightConditionText);
        lightSourceTextView = findViewById(R.id.lightSourceText);
        sensorInfoCard = findViewById(R.id.sensorInfoCard);

        progressCardBackground = findViewById(R.id.backgroundCardView);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        loadState();
        initializeSensors(); 
        updateUI();

        if (startStopButton != null) {
            startStopButton.setOnClickListener(v -> toggleTracking());
        }
        if (resetButton != null) {
            resetButton.setOnClickListener(v -> resetCounter());
        }

        if (!checkActivityRecognitionPermission()) {
            requestActivityRecognitionPermission();
        }

        checkLightSensorStatus();
    }

    private void checkLightSensorStatus() {
        if (hasPhysicalLightSensor) {
            Toast.makeText(this, "Physical Light Sensor is WORKING", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Physical Light Sensor NOT FOUND. Using camera estimation.", Toast.LENGTH_LONG).show();
        }
    }

    private void initializeSensors() {
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        boolean hasActivityPermission = checkActivityRecognitionPermission();

        hasStepCounter = (hasActivityPermission && stepCounterSensor != null);
        hasStepDetector = (hasActivityPermission && stepDetectorSensor != null);
        hasAccelerometer = (accelerometerSensor != null);
        hasPhysicalLightSensor = (lightSensor != null);

        if (!hasPhysicalLightSensor) {
            checkCameraPermissionAndStart();
        }
    }

    private void registerSensors() {
        if (!isTracking) return;
        
        if (hasStepCounter) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (hasStepDetector) {
            sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (hasAccelerometer) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        if (hasPhysicalLightSensor) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            isCameraLightActive = false;
            closeCamera();
        } else {
            checkCameraPermissionAndStart();
        }
    }

    private void unregisterSensors() {
        sensorManager.unregisterListener(this);
        if (!hasPhysicalLightSensor) {
            closeCamera();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            handleLightSensorUpdate(event.values[0], true);
            return; 
        }

        if (!isTracking) return;

        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            handleStepCounterUpdate(event.values[0]);
        } 
        else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            onStepDetected();
        } 
        else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            processFusedAccelerometer(event.values);
        }
    }

    private void handleStepCounterUpdate(float totalSteps) {
        if (stepsBaseline < 0) stepsBaseline = totalSteps;
        if (totalSteps < stepsBaseline) stepsBaseline = totalSteps; 
        
        int hwSteps = (int) (totalSteps - stepsBaseline);
        if (hwSteps > currentSessionSteps) {
            currentSessionSteps = hwSteps;
            updateUI();
        }
    }

    private void onStepDetected() {
        long now = System.currentTimeMillis();
        if (now - lastStepTime < 350) return;

        currentSessionSteps++;
        updateUI();
        
        lastStepTime = now;
    }

    private void processFusedAccelerometer(float[] values) {
        float x = values[0], y = values[1], z = values[2];
        float magnitude = (float) Math.sqrt(x*x + y*y + z*z);
        
        float alpha = 0.1f;
        float filteredMag = (lastFilteredMag * (1.0f - alpha)) + (magnitude * alpha);
        
        windowBuffer[windowIndex] = filteredMag;
        windowIndex = (windowIndex + 1) % windowBuffer.length;
        if (windowIndex == 0) windowFull = true;
        
        if (windowFull) {
            float min = windowBuffer[0], max = windowBuffer[0];
            for (float val : windowBuffer) {
                if (val < min) min = val;
                if (val > max) max = val;
            }
            
            float dynamicThreshold = (min + max) / 2.0f;
            float amplitude = max - min;
            
            if (amplitude > 2.8f) {
                if (lastFilteredMag < dynamicThreshold && filteredMag >= dynamicThreshold) {
                    onStepDetected();
                }
            }
        }
        lastFilteredMag = filteredMag;
    }

    private void handleLightSensorUpdate(float lux, boolean fromPhysicalSensor) {
        currentLux = lux;
        if (lightValueTextView != null) {
            lightValueTextView.setText(String.format(Locale.getDefault(), "%.2f lux", lux));
        }

        String condition;
        if (lux < 50) {
            condition = getString(R.string.light_condition_dim);
        } else if (lux < 500) {
            condition = getString(R.string.light_condition_normal);
        } else {
            condition = getString(R.string.light_condition_bright);
        }
        if (lightConditionTextView != null) {
            lightConditionTextView.setText(condition);
        }

        if (lightSourceTextView != null) {
            if (fromPhysicalSensor) {
                lightSourceTextView.setText(R.string.light_source_hardware);
            } else {
                lightSourceTextView.setText(R.string.light_source_camera_estimated);
            }
        }
        applyDynamicTheme(lux);
    }

    private void applyDynamicTheme(float lux) {
        if (mainLayout == null) return;
        
        boolean isDarkMode = lux < 100;
        WindowInsetsControllerCompat windowInsetsController = ViewCompat.getWindowInsetsController(getWindow().getDecorView());

        int backgroundColor = isDarkMode ? Color.BLACK : Color.WHITE;
        int textColor = isDarkMode ? Color.WHITE : Color.BLACK;

        mainLayout.setBackgroundColor(backgroundColor);
        
        if (toolbar != null) {
            toolbar.setTitleTextColor(textColor);
            toolbar.setBackgroundColor(backgroundColor);
        }

        if (stepsTextView != null) stepsTextView.setTextColor(textColor);
        if (stepsLabelTextView != null) stepsLabelTextView.setTextColor(textColor);
        if (statusTextView != null) statusTextView.setTextColor(textColor);
        if (lightValueTextView != null) lightValueTextView.setTextColor(textColor);
        if (lightConditionTextView != null) lightConditionTextView.setTextColor(textColor);
        if (lightSourceTextView != null) lightSourceTextView.setTextColor(textColor);
        
        if (sensorInfoCard != null) {
            sensorInfoCard.setCardBackgroundColor(backgroundColor);
            sensorInfoCard.setStrokeColor(textColor);
        }

        if (progressCardBackground != null) {
             progressCardBackground.setStrokeColor(textColor);
             progressCardBackground.setCardBackgroundColor(Color.TRANSPARENT);
        }

        if (startStopButton != null) {
            startStopButton.setTextColor(backgroundColor);
            startStopButton.setBackgroundTintList(ColorStateList.valueOf(textColor));
        }

        if (resetButton != null) {
            resetButton.setTextColor(textColor);
        }

        if (windowInsetsController != null) {
            windowInsetsController.setAppearanceLightStatusBars(!isDarkMode);
            windowInsetsController.setAppearanceLightNavigationBars(!isDarkMode);
        }
    }

    private void toggleTracking() {
        if (!hasStepCounter && !hasStepDetector && !hasAccelerometer) {
            Toast.makeText(this, "No usable step sensors found", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isTracking) {
            accumulatedSteps += currentSessionSteps;
            currentSessionSteps = 0;
            isTracking = false;
            stepsBaseline = -1f;
            unregisterSensors();
        } else {
            isTracking = true;
            currentSessionSteps = 0;
            registerSensors();
        }
        saveState();
        updateUI();
    }

    private void resetCounter() {
        accumulatedSteps = 0;
        currentSessionSteps = 0;
        stepsBaseline = -1f; 
        saveState();
        updateUI();
        Toast.makeText(this, R.string.toast_reset, Toast.LENGTH_SHORT).show();
    }

    private void updateUI() {
        if (stepsTextView != null) {
            stepsTextView.setText(String.valueOf(accumulatedSteps + currentSessionSteps));
        }
        
        if (statusTextView != null) {
            if (isTracking) {
                if (hasStepCounter || hasStepDetector) {
                    statusTextView.setText(R.string.status_tracking);
                } else {
                    statusTextView.setText(R.string.status_universal_tracking);
                }
            } else {
                if (!hasStepCounter && !hasStepDetector && !hasAccelerometer) {
                    statusTextView.setText(R.string.status_no_sensor);
                } else {
                    statusTextView.setText(accumulatedSteps > 0 ? R.string.status_paused : R.string.status_ready);
                }
            }
        }

        if (startStopButton != null) {
            startStopButton.setText(isTracking ? R.string.btn_stop : (accumulatedSteps > 0 ? R.string.btn_resume : R.string.btn_start));
        }

        if (lightSourceTextView != null) {
            if (!hasPhysicalLightSensor && !isCameraLightActive) {
                lightSourceTextView.setText(R.string.light_source_none);
                if (lightValueTextView != null) lightValueTextView.setText(R.string.status_light_sensor_not_found);
                if (lightConditionTextView != null) lightConditionTextView.setText(R.string.light_condition_default);
            } else if (hasPhysicalLightSensor) {
                lightSourceTextView.setText(R.string.light_source_hardware);
            } else if (isCameraLightActive) {
                lightSourceTextView.setText(R.string.light_source_camera_estimated);
            }
        }

        if (currentLux > 0) {
            applyDynamicTheme(currentLux);
        } else {
            applyDynamicTheme(200f); 
        }
    }

    private void saveState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_ACCUMULATED, accumulatedSteps)
                .putBoolean(KEY_IS_TRACKING, isTracking)
                .putFloat(KEY_BASELINE, stepsBaseline)
                .apply();
    }

    private void loadState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        accumulatedSteps = prefs.getInt(KEY_ACCUMULATED, 0);
        isTracking = prefs.getBoolean(KEY_IS_TRACKING, false);
        stepsBaseline = prefs.getFloat(KEY_BASELINE, -1f);
    }

    private boolean checkActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, ACTIVITY_RECOGNITION_CODE);
        }
    }

    private void checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            startCameraBrightness();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ACTIVITY_RECOGNITION_CODE) {
            initializeSensors();
            updateUI();
        } else if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraBrightness();
            } else {
                Toast.makeText(this, R.string.toast_camera_permission_denied, Toast.LENGTH_LONG).show();
                isCameraLightActive = false;
                updateUI();
            }
        }
    }

    private void startCameraBrightness() {
        if (isCameraLightActive || hasPhysicalLightSensor) return;
        closeCamera();
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null || cameraManager.getCameraIdList().length == 0) {
                isCameraLightActive = false;
                updateUI();
                return;
            }
            String cameraId = cameraManager.getCameraIdList()[0];
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    isCameraLightActive = true;
                    runOnUiThread(() -> updateUI());
                    startCaptureSession();
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    closeCamera();
                    isCameraLightActive = false;
                    runOnUiThread(() -> updateUI());
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    closeCamera();
                    isCameraLightActive = false;
                    runOnUiThread(() -> updateUI());
                }
            }, null);
        } catch (Exception e) {
            isCameraLightActive = false;
            updateUI();
        }
    }

    private void startCaptureSession() {
        try {
            imageReader = ImageReader.newInstance(64, 48, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
            }, null);

            Surface surface = imageReader.getSurface();
            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                Long exposure = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME);
                                if (exposure != null) {
                                    float estimatedLux = mapCameraParamsToLux(exposure);
                                    if (Math.abs(currentLux - estimatedLux) > 5) {
                                        runOnUiThread(() -> handleLightSensorUpdate(estimatedLux, false));
                                    }
                                }
                            }
                        }, null);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    closeCamera();
                }
            }, null);
        } catch (Exception e) {
            isCameraLightActive = false;
        }
    }

    private float mapCameraParamsToLux(long exposureTime) {
        float exposureMs = exposureTime / 1_000_000f;
        float lux = (1500f / (exposureMs + 0.5f)) - 10f;
        return Math.max(1f, Math.min(lux, 2000f));
    }

    private void closeCamera() {
        if (captureSession != null) { 
            try { captureSession.close(); } catch (Exception e) {} 
            captureSession = null; 
        }
        if (cameraDevice != null) { 
            try { cameraDevice.close(); } catch (Exception e) {} 
            cameraDevice = null; 
        }
        if (imageReader != null) { 
            try { imageReader.close(); } catch (Exception e) {} 
            imageReader = null; 
        }
        isCameraLightActive = false;
    }

    @Override
    protected void onResume() { 
        super.onResume(); 
        initializeSensors(); 
        if(isTracking) {
            registerSensors(); 
        } else if (hasPhysicalLightSensor) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        updateUI();
    }
    
    @Override
    protected void onPause() { 
        super.onPause(); 
        unregisterSensors();
        closeCamera();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
