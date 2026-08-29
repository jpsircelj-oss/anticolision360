package com.anticolision360.v6safe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.Rot90Op;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity implements SensorEventListener, LocationListener {
    private static final int REQ_CAMERA = 31;
    private static final int REQ_LOCATION = 32;
    private static final long MIN_INFERENCE_INTERVAL_NS = 180_000_000L;

    private PreviewView previewView;
    private DetectionOverlay overlayView;
    private TextView statusView;
    private TextView telemetryView;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor linearAccelerationSensor;
    private Sensor accelerometerSensor;
    private Sensor gyroscopeSensor;

    private ExecutorService cameraExecutor;
    private ObjectDetector objectDetector;
    private android.graphics.Bitmap bitmapBuffer;
    private long lastInferenceNs = 0L;
    private boolean detectorReady = false;

    private double gpsSpeedKmh = 0.0;
    private float gpsBearing = Float.NaN;
    private float gpsAccuracy = Float.NaN;
    private float imuHeading = Float.NaN;
    private float acceleration = 0f;
    private float gyroZ = 0f;
    private boolean gpsActive = false;
    private boolean usingLinearAcceleration = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        overlayView = new DetectionOverlay(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setText("AntiColisión 360 V6 Alpha 5\nPreparando cámara + GPS/IMU + IA…");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackgroundColor(0x99000000);
        statusView.setPadding(20, 14, 20, 14);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.TOP;
        root.addView(statusView, statusParams);

        telemetryView = new TextView(this);
        telemetryView.setTextColor(Color.WHITE);
        telemetryView.setTextSize(15f);
        telemetryView.setGravity(Gravity.CENTER);
        telemetryView.setBackgroundColor(0xAA000000);
        telemetryView.setPadding(18, 14, 18, 14);
        FrameLayout.LayoutParams telemetryParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        telemetryParams.gravity = Gravity.BOTTOM;
        root.addView(telemetryView, telemetryParams);

        setContentView(root);

        cameraExecutor = Executors.newSingleThreadExecutor();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        configureSensors();
        initializeDetector();
        updateTelemetry();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        if (hasLocationPermission()) {
            startLocation();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
        }
    }

    private void initializeDetector() {
        try {
            ObjectDetector.ObjectDetectorOptions options =
                    ObjectDetector.ObjectDetectorOptions.builder()
                            .setScoreThreshold(0.30f)
                            .setMaxResults(12)
                            .build();
            objectDetector = ObjectDetector.createFromFileAndOptions(
                    this,
                    "efficientdet_lite0.tflite",
                    options);
            detectorReady = true;
        } catch (Throwable t) {
            detectorReady = false;
            statusView.setText("IA NO DISPONIBLE · " + t.getClass().getSimpleName());
        }
    }

    private void configureSensors() {
        if (sensorManager == null) return;
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        usingLinearAcceleration = linearAccelerationSensor != null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensors();
        if (hasLocationPermission()) startLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
    }

    private void registerSensors() {
        if (sensorManager == null) return;
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        Sensor accel = usingLinearAcceleration ? linearAccelerationSensor : accelerometerSensor;
        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroscopeSensor != null) {
            sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (locationManager == null || !hasLocationPermission()) return;
        try {
            gpsActive = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (gpsActive) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        0f,
                        this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        0f,
                        this);
            }
        } catch (SecurityException ignored) {
            gpsActive = false;
        }
        updateTelemetry();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                statusView.setText("PERMISO DE CÁMARA DENEGADO\nPermití Cámara para continuar.");
            }
        } else if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startLocation();
            } else {
                updateTelemetry();
            }
        }
    }

    private void startCamera() {
        statusView.setText("Abriendo cámara trasera…");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis);

                statusView.setText(detectorReady
                        ? "CÁMARA ✓   GPS/IMU ✓   IA ✓   ·   V6 ALPHA 5"
                        : "CÁMARA ✓   GPS/IMU ✓   IA —   ·   V6 ALPHA 5");
            } catch (Throwable t) {
                statusView.setText("ERROR DE CÁMARA\n" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy image) {
        try {
            if (!detectorReady || objectDetector == null) return;

            long now = System.nanoTime();
            if (now - lastInferenceNs < MIN_INFERENCE_INTERVAL_NS) return;
            lastInferenceNs = now;

            int width = image.getWidth();
            int height = image.getHeight();
            if (bitmapBuffer == null || bitmapBuffer.getWidth() != width || bitmapBuffer.getHeight() != height) {
                bitmapBuffer = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            }

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();
            bitmapBuffer.copyPixelsFromBuffer(buffer);

            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            ImageProcessor processor = new ImageProcessor.Builder()
                    .add(new Rot90Op(-rotationDegrees / 90))
                    .build();
            TensorImage tensorImage = processor.process(TensorImage.fromBitmap(bitmapBuffer));

            long startMs = System.currentTimeMillis();
            List<Detection> detections = objectDetector.detect(tensorImage);
            long inferenceMs = System.currentTimeMillis() - startMs;

            List<OverlayBox> boxes = new ArrayList<>();
            for (Detection detection : detections) {
                if (detection.getCategories() == null || detection.getCategories().isEmpty()) continue;
                Category category = detection.getCategories().get(0);
                String translated = translateTargetLabel(category.getLabel());
                if (translated == null) continue;

                String raw = category.getLabel() == null ? "" : category.getLabel().toLowerCase(Locale.US);
                boolean vulnerable = raw.equals("person") || raw.equals("bicycle") || raw.equals("motorcycle");
                boxes.add(new OverlayBox(
                        new RectF(detection.getBoundingBox()),
                        translated,
                        category.getScore(),
                        vulnerable));
            }

            Collections.sort(boxes, (a, b) -> {
                if (a.vulnerable != b.vulnerable) return a.vulnerable ? -1 : 1;
                return Float.compare(b.score, a.score);
            });

            int sourceWidth = tensorImage.getWidth();
            int sourceHeight = tensorImage.getHeight();
            runOnUiThread(() -> {
                overlayView.setResults(boxes, sourceWidth, sourceHeight);
                statusView.setText(String.format(Locale.US,
                        "CÁMARA ✓   GPS/IMU ✓   IA ✓   ·   %d obj   ·   %d ms   ·   V6 ALPHA 5",
                        boxes.size(),
                        inferenceMs));
            });
        } catch (Throwable t) {
            runOnUiThread(() -> statusView.setText(
                    "IA ERROR · " + t.getClass().getSimpleName() + " · V6 ALPHA 5"));
        } finally {
            image.close();
        }
    }

    private static String translateTargetLabel(String label) {
        if (label == null) return null;
        switch (label.toLowerCase(Locale.US)) {
            case "person": return "PEATÓN";
            case "bicycle": return "BICICLETA";
            case "motorcycle": return "MOTOCICLETA";
            case "car": return "AUTO";
            case "bus": return "BUS";
            case "truck": return "CAMIÓN";
            default: return null;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        if (location.hasSpeed()) {
            gpsSpeedKmh = Math.max(0.0, location.getSpeed() * 3.6);
        }
        if (location.hasBearing()) {
            gpsBearing = location.getBearing();
        }
        if (location.hasAccuracy()) {
            gpsAccuracy = location.getAccuracy();
        }
        gpsActive = true;
        updateTelemetry();
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) gpsActive = true;
        updateTelemetry();
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) gpsActive = false;
        updateTelemetry();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotation = new float[9];
            float[] orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(rotation, event.values);
            SensorManager.getOrientation(rotation, orientation);
            float degrees = (float) Math.toDegrees(orientation[0]);
            if (degrees < 0f) degrees += 360f;
            imuHeading = degrees;
        } else if (type == Sensor.TYPE_LINEAR_ACCELERATION || type == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            if (type == Sensor.TYPE_ACCELEROMETER) {
                mag = Math.abs(mag - SensorManager.GRAVITY_EARTH);
            }
            acceleration = 0.82f * acceleration + 0.18f * mag;
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            gyroZ = 0.82f * gyroZ + 0.18f * event.values[2];
        }
        updateTelemetry();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void updateTelemetry() {
        if (telemetryView == null) return;
        String gpsPermission = hasLocationPermission() ? (gpsActive ? "GPS ✓" : "GPS ESPERANDO") : "GPS SIN PERMISO";
        String accuracyText = Float.isNaN(gpsAccuracy) ? "--" : String.format(Locale.US, "%.0f m", gpsAccuracy);
        String gpsHeadingText = Float.isNaN(gpsBearing) ? "--" : String.format(Locale.US, "%.0f° %s", gpsBearing, cardinal(gpsBearing));
        String imuHeadingText = Float.isNaN(imuHeading) ? "--" : String.format(Locale.US, "%.0f°", imuHeading);

        String text = String.format(Locale.US,
                "%s   %.1f km/h   PREC %s\nRUMBO GPS %s   ·   IMU %s\nACEL %.2f m/s²   ·   GIRO %.2f rad/s",
                gpsPermission,
                gpsSpeedKmh,
                accuracyText,
                gpsHeadingText,
                imuHeadingText,
                acceleration,
                gyroZ);
        telemetryView.setText(text);
    }

    private static String cardinal(float bearing) {
        String[] directions = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        int index = Math.round(((bearing % 360f) / 45f)) % 8;
        return directions[index];
    }

    private static final class OverlayBox {
        final RectF box;
        final String label;
        final float score;
        final boolean vulnerable;

        OverlayBox(RectF box, String label, float score, boolean vulnerable) {
            this.box = box;
            this.label = label;
            this.score = score;
            this.vulnerable = vulnerable;
        }
    }

    private static final class DetectionOverlay extends View {
        private final Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint vulnerablePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private List<OverlayBox> boxes = Collections.emptyList();
        private int sourceWidth = 1;
        private int sourceHeight = 1;

        DetectionOverlay(Context context) {
            super(context);
            setWillNotDraw(false);

            normalPaint.setStyle(Paint.Style.STROKE);
            normalPaint.setStrokeWidth(5f);
            normalPaint.setColor(Color.CYAN);

            vulnerablePaint.setStyle(Paint.Style.STROKE);
            vulnerablePaint.setStrokeWidth(8f);
            vulnerablePaint.setColor(Color.YELLOW);

            textPaint.setColor(Color.BLACK);
            textPaint.setTextSize(32f);
            textPaint.setFakeBoldText(true);

            textBackgroundPaint.setStyle(Paint.Style.FILL);
        }

        void setResults(List<OverlayBox> newBoxes, int width, int height) {
            boxes = new ArrayList<>(newBoxes);
            sourceWidth = Math.max(1, width);
            sourceHeight = Math.max(1, height);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (boxes.isEmpty()) return;

            float scale = Math.max(
                    getWidth() / (float) sourceWidth,
                    getHeight() / (float) sourceHeight);
            float dx = (getWidth() - sourceWidth * scale) * 0.5f;
            float dy = (getHeight() - sourceHeight * scale) * 0.5f;

            for (OverlayBox item : boxes) {
                RectF r = new RectF(
                        item.box.left * scale + dx,
                        item.box.top * scale + dy,
                        item.box.right * scale + dx,
                        item.box.bottom * scale + dy);

                Paint boxPaint = item.vulnerable ? vulnerablePaint : normalPaint;
                canvas.drawRect(r, boxPaint);

                String label = (item.vulnerable ? "★ " : "")
                        + item.label
                        + String.format(Locale.US, " %.0f%%", item.score * 100f);
                float textWidth = textPaint.measureText(label);
                float textHeight = textPaint.getTextSize() + 12f;
                float top = Math.max(0f, r.top - textHeight);
                float right = Math.min(getWidth(), r.left + textWidth + 18f);

                textBackgroundPaint.setColor(item.vulnerable ? Color.YELLOW : Color.CYAN);
                canvas.drawRect(r.left, top, right, top + textHeight, textBackgroundPaint);
                canvas.drawText(label, r.left + 9f, top + textPaint.getTextSize(), textPaint);
            }
        }
    }
}
