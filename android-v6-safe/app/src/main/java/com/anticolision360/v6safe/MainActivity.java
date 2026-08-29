package com.anticolision360.v6safe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;

public final class MainActivity extends ComponentActivity implements SensorEventListener, LocationListener {
    private static final int REQ_CAMERA = 31;
    private static final int REQ_LOCATION = 32;

    private PreviewView previewView;
    private TextView statusView;
    private TextView telemetryView;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor linearAccelerationSensor;
    private Sensor accelerometerSensor;
    private Sensor gyroscopeSensor;

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

        statusView = new TextView(this);
        statusView.setText("AntiColisión 360 V6 Alpha 4\nPreparando cámara + GPS + IMU…");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(17f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackgroundColor(0x99000000);
        statusView.setPadding(24, 18, 24, 18);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.TOP;
        root.addView(statusView, statusParams);

        telemetryView = new TextView(this);
        telemetryView.setTextColor(Color.WHITE);
        telemetryView.setTextSize(16f);
        telemetryView.setGravity(Gravity.CENTER);
        telemetryView.setBackgroundColor(0xAA000000);
        telemetryView.setPadding(20, 16, 20, 16);
        FrameLayout.LayoutParams telemetryParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        telemetryParams.gravity = Gravity.BOTTOM;
        root.addView(telemetryView, telemetryParams);

        setContentView(root);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        configureSensors();
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

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
                statusView.setText("CÁMARA ✓   GPS/IMU ✓   ·   V6 ALPHA 4");
            } catch (Throwable t) {
                statusView.setText("ERROR DE CÁMARA\n" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
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
}
