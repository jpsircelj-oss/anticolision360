from pathlib import Path

base = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha93.java')
out = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha94.java')
s = base.read_text(encoding='utf-8')

if 'public final class MainActivityAlpha93' not in s:
    raise SystemExit('Alpha93 generated base class not found')

s = s.replace('MainActivityAlpha93', 'MainActivityAlpha94')
s = s.replace('Alpha 9.3', 'Alpha 9.4')
s = s.replace('ALPHA 9.3', 'ALPHA 9.4')

# Imports for soft edge glows and red-only audio.
s = s.replace(
    'import android.Manifest;\n',
    'import android.Manifest;\nimport android.media.AudioManager;\nimport android.media.ToneGenerator;\n'
)
s = s.replace(
    'import android.graphics.Paint;\n',
    'import android.graphics.Paint;\nimport android.graphics.LinearGradient;\nimport android.graphics.Shader;\n'
)

# Activity state for the glow overlay and differentiated red alarms.
s = s.replace(
    '    private TextView alertView;\n',
    '    private TextView alertView;\n'
    '    private EdgeGlowView edgeGlowView;\n'
    '    private ToneGenerator toneGenerator;\n'
    '    private long lastLateralBeepMs = 0L;\n'
    '    private long lastFrontAlarmMs = 0L;\n'
)

# Put a dedicated diffuse glow layer above the camera/diagnostic overlay.
needle = '''        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        alertView = new TextView(this);'''
replacement = '''        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        edgeGlowView = new EdgeGlowView(this);
        root.addView(edgeGlowView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        alertView = new TextView(this);'''
if needle not in s:
    raise SystemExit('edgeGlowView insertion point not found')
s = s.replace(needle, replacement, 1)

# Initialize alarm output after the view tree is ready.
needle = '''        setContentView(root);

        cameraExecutor = Executors.newSingleThreadExecutor();'''
replacement = '''        setContentView(root);
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (Throwable ignored) {
            toneGenerator = null;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();'''
if needle not in s:
    raise SystemExit('tone generator insertion point not found')
s = s.replace(needle, replacement, 1)

# Release audio resource with the activity.
needle = '''    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
    }'''
replacement = '''    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (toneGenerator != null) {
            try { toneGenerator.release(); } catch (Throwable ignored) { }
            toneGenerator = null;
        }
    }'''
if needle not in s:
    raise SystemExit('onDestroy block not found')
s = s.replace(needle, replacement, 1)

# Normal mode: never show text cards. Send tracked objects to the glow risk layer instead.
needle = '''                overlayView.setResults(overlayBoxes, laneState, sourceWidth, sourceHeight, debugMode);
                updateAlertHud(debugMode ? Collections.emptyList() : visibleBoxes);'''
replacement = '''                overlayView.setResults(overlayBoxes, laneState, sourceWidth, sourceHeight, debugMode);
                alertView.setVisibility(View.GONE);
                updateEdgeWarnings(debugMode ? Collections.emptyList() : trackedBoxes,
                        sourceWidth, sourceHeight);'''
if needle not in s:
    raise SystemExit('normal alert replacement point not found')
s = s.replace(needle, replacement, 1)

# Ensure entering DEBUG clears edge glows immediately.
needle = '''                                alertView.setVisibility(View.GONE);
                                modeButton.setText(debugMode ? "DBG" : "⚙");
                                overlayView.setDebugMode(debugMode);
                                updateTelemetry();'''
replacement = '''                                alertView.setVisibility(View.GONE);
                                if (edgeGlowView != null) edgeGlowView.clear();
                                modeButton.setText(debugMode ? "DBG" : "⚙");
                                overlayView.setDebugMode(debugMode);
                                updateTelemetry();'''
if needle not in s:
    raise SystemExit('debug clear insertion point not found')
s = s.replace(needle, replacement, 1)

# Risk-to-glow mapping. Bounding-box geometry is a visual proximity proxy only.
insert_point = '    private void updateAlertHud(List<TrackedBox> alerts) {'
if insert_point not in s:
    raise SystemExit('updateAlertHud method not found')
methods = '''    private void updateEdgeWarnings(List<TrackedBox> boxes,
                                    int sourceWidth,
                                    int sourceHeight) {
        if (edgeGlowView == null) return;
        if (debugMode || boxes == null || boxes.isEmpty()) {
            edgeGlowView.clear();
            return;
        }

        int left = 0;
        int right = 0;
        int front = 0;
        for (TrackedBox box : boxes) {
            int level = warningLevel(box, sourceWidth, sourceHeight);
            if (level <= 0) continue;
            if ("IZQUIERDA".equals(box.position)) {
                left = Math.max(left, level);
            } else if ("DERECHA".equals(box.position)) {
                right = Math.max(right, level);
            } else {
                front = Math.max(front, level);
            }
        }

        edgeGlowView.setLevels(left, right, front);
        playRedAlarmIfNeeded(left, right, front);
    }

    private int warningLevel(TrackedBox box, int sourceWidth, int sourceHeight) {
        if (box == null || sourceWidth <= 0 || sourceHeight <= 0) return 0;
        float frameArea = Math.max(1f, sourceWidth * (float) sourceHeight);
        float areaRatio = area(box.rect) / frameArea;
        float bottom = box.rect.bottom / Math.max(1f, sourceHeight);
        float speedBoost = clamp((float) (gpsSpeedKmh / 120.0), 0f, 1f);
        boolean ahead = "ADELANTE".equals(box.position);
        boolean bicycleOrMoto = "BICICLETA".equals(box.label)
                || "MOTOCICLETA".equals(box.label);
        boolean pedestrian = "PEATÓN".equals(box.label);
        boolean vulnerable = bicycleOrMoto || pedestrian;

        // VRU priority: bicycle/motorcycle/pedestrian turn red earlier as visual proximity grows.
        if (vulnerable) {
            float redBottom = bicycleOrMoto ? 0.66f : 0.70f;
            float redArea = bicycleOrMoto ? 0.018f : 0.024f;
            redBottom -= 0.07f * speedBoost;
            redArea -= 0.006f * speedBoost;
            if (bottom >= redBottom || areaRatio >= redArea) return 2;

            float yellowBottom = 0.48f - 0.04f * speedBoost;
            float yellowArea = bicycleOrMoto ? 0.005f : 0.007f;
            if (bottom >= yellowBottom || areaRatio >= yellowArea) return 1;
            return 0;
        }

        // Vehicles ahead are considered earlier than lateral vehicles.
        if (ahead) {
            float redBottom = 0.80f - 0.10f * speedBoost;
            float redArea = 0.070f - 0.030f * speedBoost;
            if (bottom >= redBottom || areaRatio >= redArea) return 2;

            float yellowBottom = 0.58f - 0.08f * speedBoost;
            float yellowArea = 0.018f - 0.006f * speedBoost;
            if (bottom >= yellowBottom || areaRatio >= yellowArea) return 1;
            return 0;
        }

        // Lateral cars/buses/trucks stay quiet unless visually close.
        float redBottom = 0.88f - 0.07f * speedBoost;
        float redArea = 0.115f - 0.030f * speedBoost;
        if (bottom >= redBottom || areaRatio >= redArea) return 2;

        float yellowBottom = 0.73f - 0.05f * speedBoost;
        float yellowArea = 0.040f - 0.010f * speedBoost;
        if (bottom >= yellowBottom || areaRatio >= yellowArea) return 1;
        return 0;
    }

    private void playRedAlarmIfNeeded(int left, int right, int front) {
        if (toneGenerator == null) return;
        long now = System.currentTimeMillis();
        try {
            if (front >= 2) {
                if (now - lastFrontAlarmMs >= 900L) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 330);
                    lastFrontAlarmMs = now;
                }
                return;
            }
            if (left >= 2 || right >= 2) {
                if (now - lastLateralBeepMs >= 480L) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
                    lastLateralBeepMs = now;
                }
            }
        } catch (Throwable ignored) {
        }
    }

'''
s = s.replace(insert_point, methods + insert_point, 1)

# Diffuse edge renderer. Yellow = attention, red = high risk. Red pulses softly.
insert_point = '    private static final class DetectionOverlay extends View {'
if insert_point not in s:
    raise SystemExit('DetectionOverlay class not found')
glow_class = '''    private static final class EdgeGlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int leftLevel = 0;
        private int rightLevel = 0;
        private int frontLevel = 0;

        EdgeGlowView(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
        }

        void setLevels(int left, int right, int front) {
            leftLevel = Math.max(0, Math.min(2, left));
            rightLevel = Math.max(0, Math.min(2, right));
            frontLevel = Math.max(0, Math.min(2, front));
            invalidate();
        }

        void clear() {
            if (leftLevel == 0 && rightLevel == 0 && frontLevel == 0) return;
            leftLevel = rightLevel = frontLevel = 0;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (leftLevel == 0 && rightLevel == 0 && frontLevel == 0) return;

            float edgeWidth = getWidth() * 0.23f;
            float frontHeight = getHeight() * 0.18f;
            if (leftLevel > 0) drawLeft(canvas, edgeWidth, leftLevel);
            if (rightLevel > 0) drawRight(canvas, edgeWidth, rightLevel);
            if (frontLevel > 0) drawFront(canvas, frontHeight, frontLevel);

            if (leftLevel >= 2 || rightLevel >= 2 || frontLevel >= 2) {
                postInvalidateDelayed(65L);
            }
        }

        private int glowColor(int level) {
            float pulse = 1f;
            if (level >= 2) {
                double phase = (System.currentTimeMillis() % 700L) / 700.0 * Math.PI * 2.0;
                pulse = 0.66f + 0.34f * (float) ((Math.sin(phase) + 1.0) * 0.5);
            }
            int alpha = level >= 2 ? Math.round(225f * pulse) : 135;
            return level >= 2
                    ? Color.argb(alpha, 255, 24, 24)
                    : Color.argb(alpha, 255, 210, 30);
        }

        private void drawLeft(Canvas canvas, float edgeWidth, int level) {
            int color = glowColor(level);
            paint.setShader(new LinearGradient(
                    0f, 0f, edgeWidth, 0f,
                    color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, edgeWidth, getHeight(), paint);
            paint.setShader(null);
        }

        private void drawRight(Canvas canvas, float edgeWidth, int level) {
            int color = glowColor(level);
            paint.setShader(new LinearGradient(
                    getWidth(), 0f, getWidth() - edgeWidth, 0f,
                    color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(getWidth() - edgeWidth, 0f, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }

        private void drawFront(Canvas canvas, float frontHeight, int level) {
            int color = glowColor(level);
            paint.setShader(new LinearGradient(
                    0f, 0f, 0f, frontHeight,
                    color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), frontHeight, paint);
            paint.setShader(null);
        }
    }

'''
s = s.replace(insert_point, glow_class + insert_point, 1)

out.write_text(s, encoding='utf-8')
print(f'Generated {out} ({len(s)} chars)')
