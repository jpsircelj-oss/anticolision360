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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivityAlpha7 extends ComponentActivity implements SensorEventListener, LocationListener {
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
    private final TemporalTracker temporalTracker = new TemporalTracker();

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
        statusView.setText("AntiColisión 360 V6 Alpha 7\nPreparando filtro de pantallas + tracking…");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(15f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackgroundColor(0x99000000);
        statusView.setPadding(18, 12, 18, 12);
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
                            .setScoreThreshold(0.26f)
                            .setMaxResults(20)
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
        if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        Sensor accel = usingLinearAcceleration ? linearAccelerationSensor : accelerometerSensor;
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        if (gyroscopeSensor != null) sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME);
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
            if (gpsActive) locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this);
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this);
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else statusView.setText("PERMISO DE CÁMARA DENEGADO\nPermití Cámara para continuar.");
        } else if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) startLocation();
            else updateTelemetry();
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                statusView.setText(detectorReady
                        ? "CÁMARA ✓  GPS/IMU ✓  IA ✓  TRACK ✓  FILTRO ✓  ·  V6 ALPHA 7"
                        : "CÁMARA ✓  GPS/IMU ✓  IA —  ·  V6 ALPHA 7");
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

            int sourceWidth = tensorImage.getWidth();
            int sourceHeight = tensorImage.getHeight();
            float frameArea = Math.max(1f, sourceWidth * (float) sourceHeight);

            List<RectF> displayRects = new ArrayList<>();
            for (Detection detection : detections) {
                if (detection.getCategories() == null || detection.getCategories().isEmpty()) continue;
                Category category = detection.getCategories().get(0);
                String raw = category.getLabel() == null ? "" : category.getLabel().toLowerCase(Locale.US);
                if (!isDisplayLabel(raw)) continue;
                RectF rect = new RectF(detection.getBoundingBox());
                if (area(rect) / frameArea >= 0.012f) displayRects.add(expand(rect, sourceWidth, sourceHeight, 0.055f));
            }

            List<RawBox> rawBoxes = new ArrayList<>();
            int filteredByScreen = 0;
            for (Detection detection : detections) {
                if (detection.getCategories() == null || detection.getCategories().isEmpty()) continue;
                Category category = detection.getCategories().get(0);
                String translated = translateTargetLabel(category.getLabel());
                if (translated == null) continue;

                RectF rect = new RectF(detection.getBoundingBox());
                if (insideDisplay(rect, displayRects)) {
                    filteredByScreen++;
                    continue;
                }

                String raw = category.getLabel() == null ? "" : category.getLabel().toLowerCase(Locale.US);
                boolean vulnerable = raw.equals("person") || raw.equals("bicycle") || raw.equals("motorcycle");
                rawBoxes.add(new RawBox(rect, translated, category.getScore(), vulnerable));
            }

            List<TrackedBox> trackedBoxes = temporalTracker.update(rawBoxes, sourceWidth, sourceHeight);
            Collections.sort(trackedBoxes, (a, b) -> {
                if (a.vulnerable != b.vulnerable) return a.vulnerable ? -1 : 1;
                if (!a.position.equals(b.position)) {
                    if (a.position.equals("ADELANTE")) return -1;
                    if (b.position.equals("ADELANTE")) return 1;
                }
                return Float.compare(b.score, a.score);
            });

            final int filteredCount = filteredByScreen;
            runOnUiThread(() -> {
                overlayView.setResults(trackedBoxes, sourceWidth, sourceHeight);
                statusView.setText(String.format(Locale.US,
                        "CÁMARA ✓  GPS/IMU ✓  IA ✓  TRACK ✓  FILTRO ✓\n%d raw · %d pantalla · %d estables · %d ms · V6 ALPHA 7",
                        rawBoxes.size() + filteredCount,
                        filteredCount,
                        trackedBoxes.size(),
                        inferenceMs));
            });
        } catch (Throwable t) {
            runOnUiThread(() -> statusView.setText(
                    "IA/TRACK ERROR · " + t.getClass().getSimpleName() + " · V6 ALPHA 7"));
        } finally {
            image.close();
        }
    }

    private static boolean isDisplayLabel(String raw) {
        return raw.equals("tv") || raw.equals("laptop");
    }

    private static boolean insideDisplay(RectF target, List<RectF> displays) {
        float targetArea = Math.max(1f, area(target));
        for (RectF display : displays) {
            float overlap = intersectionArea(target, display) / targetArea;
            boolean centerInside = display.contains(target.centerX(), target.centerY());
            if (overlap >= 0.52f || (centerInside && overlap >= 0.34f)) return true;
        }
        return false;
    }

    private static RectF expand(RectF rect, int maxWidth, int maxHeight, float fraction) {
        float margin = Math.max(rect.width(), rect.height()) * fraction;
        return new RectF(
                Math.max(0f, rect.left - margin),
                Math.max(0f, rect.top - margin),
                Math.min(maxWidth, rect.right + margin),
                Math.min(maxHeight, rect.bottom + margin));
    }

    private static float intersectionArea(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        return Math.max(0f, right - left) * Math.max(0f, bottom - top);
    }

    private static float area(RectF r) {
        return Math.max(0f, r.width()) * Math.max(0f, r.height());
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
        if (location.hasSpeed()) gpsSpeedKmh = Math.max(0.0, location.getSpeed() * 3.6);
        if (location.hasBearing()) gpsBearing = location.getBearing();
        if (location.hasAccuracy()) gpsAccuracy = location.getAccuracy();
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
            if (type == Sensor.TYPE_ACCELEROMETER) mag = Math.abs(mag - SensorManager.GRAVITY_EARTH);
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
        telemetryView.setText(String.format(Locale.US,
                "%s   %.1f km/h   PREC %s\nRUMBO GPS %s   ·   IMU %s\nACEL %.2f m/s²   ·   GIRO %.2f rad/s",
                gpsPermission, gpsSpeedKmh, accuracyText, gpsHeadingText, imuHeadingText, acceleration, gyroZ));
    }

    private static String cardinal(float bearing) {
        String[] directions = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        int index = Math.round(((bearing % 360f) / 45f)) % 8;
        return directions[index];
    }

    private static final class RawBox {
        final RectF rect;
        final String label;
        final float score;
        final boolean vulnerable;

        RawBox(RectF rect, String label, float score, boolean vulnerable) {
            this.rect = rect;
            this.label = label;
            this.score = score;
            this.vulnerable = vulnerable;
        }
    }

    private static final class TrackedBox {
        final int id;
        final RectF rect;
        final String label;
        final String position;
        final float score;
        final boolean vulnerable;

        TrackedBox(int id, RectF rect, String label, String position, float score, boolean vulnerable) {
            this.id = id;
            this.rect = rect;
            this.label = label;
            this.position = position;
            this.score = score;
            this.vulnerable = vulnerable;
        }
    }

    private static final class Track {
        final int id;
        final Map<String, Float> labelVotes = new HashMap<>();
        RectF rect;
        String stableLabel;
        String stablePosition;
        String candidatePosition;
        int candidatePositionFrames;
        float score;
        int hits;
        int missed;
        float vx;
        float vy;

        Track(int id, RawBox box, int sourceWidth) {
            this.id = id;
            this.rect = new RectF(box.rect);
            this.stableLabel = box.label;
            this.score = box.score;
            this.hits = 1;
            this.stablePosition = relativePosition(this.rect, sourceWidth);
            this.labelVotes.put(box.label, Math.max(0.01f, box.score));
        }

        void update(RawBox box, int sourceWidth) {
            float oldCx = rect.centerX();
            float oldCy = rect.centerY();
            float overlapBefore = iou(rect, box.rect);
            float alpha = overlapBefore > 0.35f ? 0.36f : 0.50f;
            rect.left = blend(rect.left, box.rect.left, alpha);
            rect.top = blend(rect.top, box.rect.top, alpha);
            rect.right = blend(rect.right, box.rect.right, alpha);
            rect.bottom = blend(rect.bottom, box.rect.bottom, alpha);
            vx = 0.68f * vx + 0.32f * (rect.centerX() - oldCx);
            vy = 0.68f * vy + 0.32f * (rect.centerY() - oldCy);
            score = 0.68f * score + 0.32f * box.score;
            hits++;
            missed = 0;

            for (Map.Entry<String, Float> entry : labelVotes.entrySet()) entry.setValue(entry.getValue() * 0.88f);
            labelVotes.put(box.label, labelVotes.getOrDefault(box.label, 0f) + Math.max(0.05f, box.score));

            String bestLabel = stableLabel;
            float bestVote = -1f;
            for (Map.Entry<String, Float> entry : labelVotes.entrySet()) {
                if (entry.getValue() > bestVote) {
                    bestVote = entry.getValue();
                    bestLabel = entry.getKey();
                }
            }
            float currentVote = labelVotes.getOrDefault(stableLabel, 0f);
            if (bestLabel.equals(stableLabel) || bestVote > currentVote * 1.35f) stableLabel = bestLabel;

            String newPosition = relativePosition(rect, sourceWidth);
            if (newPosition.equals(stablePosition)) {
                candidatePosition = null;
                candidatePositionFrames = 0;
            } else if (newPosition.equals(candidatePosition)) {
                candidatePositionFrames++;
                if (candidatePositionFrames >= 3) {
                    stablePosition = newPosition;
                    candidatePosition = null;
                    candidatePositionFrames = 0;
                }
            } else {
                candidatePosition = newPosition;
                candidatePositionFrames = 1;
            }
        }

        RectF predictedRect() {
            RectF p = new RectF(rect);
            p.offset(vx, vy);
            return p;
        }

        boolean vulnerable() {
            return stableLabel.equals("PEATÓN") || stableLabel.equals("BICICLETA") || stableLabel.equals("MOTOCICLETA");
        }
    }

    private static final class TemporalTracker {
        private final List<Track> tracks = new ArrayList<>();
        private int nextId = 1;

        List<TrackedBox> update(List<RawBox> detections, int sourceWidth, int sourceHeight) {
            int existingCount = tracks.size();
            boolean[] used = new boolean[existingCount];
            List<RawBox> ordered = new ArrayList<>(detections);
            Collections.sort(ordered, (a, b) -> Float.compare(b.score, a.score));
            float diagonal = (float) Math.sqrt((double) sourceWidth * sourceWidth + (double) sourceHeight * sourceHeight);

            for (RawBox detection : ordered) {
                int bestIndex = -1;
                float bestAffinity = -1f;
                for (int i = 0; i < existingCount; i++) {
                    if (used[i]) continue;
                    Track track = tracks.get(i);
                    RectF predicted = track.predictedRect();
                    float overlap = iou(predicted, detection.rect);
                    float centerDistance = centerDistance(predicted, detection.rect) / Math.max(1f, diagonal);
                    float proximity = Math.max(0f, 1f - centerDistance * 10f);
                    float sizeSimilarity = Math.min(area(predicted), area(detection.rect)) /
                            Math.max(1f, Math.max(area(predicted), area(detection.rect)));
                    boolean sameLabel = track.stableLabel.equals(detection.label);
                    boolean sameFamily = labelFamily(track.stableLabel).equals(labelFamily(detection.label));
                    if (!sameFamily && overlap < 0.30f && centerDistance > 0.030f) continue;
                    float labelBonus = sameLabel ? 0.18f : (sameFamily ? 0.08f : -0.10f);
                    float affinity = overlap * 0.60f + proximity * 0.22f + sizeSimilarity * 0.10f + labelBonus;
                    if ((overlap >= 0.10f || centerDistance <= 0.060f) && affinity > bestAffinity) {
                        bestAffinity = affinity;
                        bestIndex = i;
                    }
                }
                if (bestIndex >= 0) {
                    used[bestIndex] = true;
                    tracks.get(bestIndex).update(detection, sourceWidth);
                } else {
                    tracks.add(new Track(nextId++, detection, sourceWidth));
                }
            }

            for (int i = 0; i < existingCount; i++) if (!used[i]) tracks.get(i).missed++;
            Iterator<Track> iterator = tracks.iterator();
            while (iterator.hasNext()) if (iterator.next().missed > 4) iterator.remove();

            List<TrackedBox> output = new ArrayList<>();
            for (Track track : tracks) {
                if (track.hits < 3 || track.missed > 1) continue;
                output.add(new TrackedBox(track.id, new RectF(track.rect), track.stableLabel,
                        track.stablePosition, track.score, track.vulnerable()));
            }
            return output;
        }
    }

    private static String labelFamily(String label) {
        if (label.equals("AUTO") || label.equals("BUS") || label.equals("CAMIÓN")) return "VEH";
        if (label.equals("PEATÓN") || label.equals("BICICLETA") || label.equals("MOTOCICLETA")) return "VRU";
        return label;
    }

    private static float blend(float oldValue, float newValue, float newWeight) {
        return oldValue * (1f - newWeight) + newValue * newWeight;
    }

    private static float centerDistance(RectF a, RectF b) {
        float dx = a.centerX() - b.centerX();
        float dy = a.centerY() - b.centerY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float iou(RectF a, RectF b) {
        float intersection = intersectionArea(a, b);
        float union = area(a) + area(b) - intersection;
        return union <= 0f ? 0f : intersection / union;
    }

    private static String relativePosition(RectF rect, int sourceWidth) {
        if (sourceWidth <= 0) return "ADELANTE";
        float left = rect.left / sourceWidth;
        float right = rect.right / sourceWidth;
        float center = rect.centerX() / sourceWidth;
        float width = Math.max(0.01f, right - left);
        float overlap = Math.max(0f, Math.min(right, 0.66f) - Math.max(left, 0.34f));
        float overlapFraction = overlap / width;
        if ((center >= 0.36f && center <= 0.64f) || overlapFraction >= 0.34f) return "ADELANTE";
        return center < 0.50f ? "IZQUIERDA" : "DERECHA";
    }

    private static String shortPosition(String position) {
        if ("IZQUIERDA".equals(position)) return "IZQ";
        if ("DERECHA".equals(position)) return "DER";
        return "ADEL";
    }

    private static final class DetectionOverlay extends View {
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<TrackedBox> boxes = Collections.emptyList();
        private int sourceWidth = 1;
        private int sourceHeight = 1;

        DetectionOverlay(Context context) {
            super(context);
            setWillNotDraw(false);
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(5f);
            textPaint.setColor(Color.BLACK);
            textPaint.setTextSize(25f);
            textPaint.setFakeBoldText(true);
            labelPaint.setStyle(Paint.Style.FILL);
        }

        void setResults(List<TrackedBox> boxes, int sourceWidth, int sourceHeight) {
            this.boxes = new ArrayList<>(boxes);
            this.sourceWidth = Math.max(1, sourceWidth);
            this.sourceHeight = Math.max(1, sourceHeight);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (boxes == null || boxes.isEmpty()) return;
            float scale = Math.max(getWidth() / (float) sourceWidth, getHeight() / (float) sourceHeight);
            float dx = (getWidth() - sourceWidth * scale) * 0.5f;
            float dy = (getHeight() - sourceHeight * scale) * 0.5f;
            List<RectF> occupiedLabels = new ArrayList<>();

            for (TrackedBox box : boxes) {
                int color = box.vulnerable ? Color.YELLOW : Color.rgb(0, 255, 210);
                boxPaint.setColor(color);
                labelPaint.setColor(color);
                RectF mapped = new RectF(
                        box.rect.left * scale + dx,
                        box.rect.top * scale + dy,
                        box.rect.right * scale + dx,
                        box.rect.bottom * scale + dy);
                canvas.drawRect(mapped, boxPaint);

                String prefix = box.vulnerable ? "★ " : "";
                String text = String.format(Locale.US, "%s%s · %s · T%d · %.0f%%",
                        prefix, box.label, shortPosition(box.position), box.id, box.score * 100f);
                float paddingX = 8f;
                float paddingY = 5f;
                float textWidth = textPaint.measureText(text);
                float labelWidth = Math.min(getWidth() - 8f, textWidth + paddingX * 2f);
                float labelHeight = textPaint.getTextSize() + paddingY * 2f + 4f;
                float left = clamp(mapped.centerX() - labelWidth * 0.5f, 4f, getWidth() - labelWidth - 4f);
                float top = Math.max(4f, mapped.top - labelHeight - 4f);
                RectF labelRect = new RectF(left, top, left + labelWidth, top + labelHeight);

                if (intersectsAny(labelRect, occupiedLabels)) {
                    top = Math.min(getHeight() - labelHeight - 4f, mapped.bottom + 4f);
                    labelRect.set(left, top, left + labelWidth, top + labelHeight);
                    int attempts = 0;
                    while (intersectsAny(labelRect, occupiedLabels) && attempts < 5) {
                        top = Math.min(getHeight() - labelHeight - 4f, top + labelHeight + 4f);
                        labelRect.set(left, top, left + labelWidth, top + labelHeight);
                        attempts++;
                    }
                }
                occupiedLabels.add(new RectF(labelRect));
                canvas.drawRect(labelRect, labelPaint);
                canvas.drawText(text, labelRect.left + paddingX,
                        labelRect.top + paddingY + textPaint.getTextSize(), textPaint);
            }
        }

        private static boolean intersectsAny(RectF rect, List<RectF> others) {
            for (RectF other : others) if (RectF.intersects(rect, other)) return true;
            return false;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
