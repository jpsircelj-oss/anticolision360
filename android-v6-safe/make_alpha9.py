from pathlib import Path
import re

base = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha8.java')
out = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha9.java')
s = base.read_text(encoding='utf-8')

if 'public final class MainActivityAlpha8' not in s:
    raise SystemExit('Alpha8 base class not found')

s = s.replace('MainActivityAlpha8', 'MainActivityAlpha9')
s = s.replace('Alpha 8', 'Alpha 9')
s = s.replace('ALPHA 8', 'ALPHA 9')

s = s.replace(
    'import android.content.Context;\n',
    'import android.content.Context;\nimport android.content.Intent;\nimport android.content.pm.ShortcutInfo;\nimport android.content.pm.ShortcutManager;\n'
)

s = s.replace(
    '    private TextView telemetryView;\n',
    '    private TextView telemetryView;\n'
    '    private TextView modeButton;\n'
    '    private volatile boolean debugMode = false;\n'
    '    private volatile LaneState lastLaneState;\n'
)

s = s.replace(
    '        super.onCreate(savedInstanceState);\n\n        FrameLayout root',
    '        super.onCreate(savedInstanceState);\n'
    '        enterImmersiveMode();\n'
    '        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);\n\n'
    '        FrameLayout root'
)

s = s.replace(
    '        root.addView(statusView, statusParams);\n\n        telemetryView = new TextView(this);',
    '        root.addView(statusView, statusParams);\n'
    '        statusView.setVisibility(View.GONE);\n\n'
    '        telemetryView = new TextView(this);'
)

s = s.replace('        telemetryView.setTextSize(15f);', '        telemetryView.setTextSize(12f);')
s = s.replace('        telemetryView.setGravity(Gravity.CENTER);', '        telemetryView.setGravity(Gravity.CENTER_VERTICAL);')
s = s.replace('        telemetryView.setBackgroundColor(0xAA000000);', '        telemetryView.setBackgroundColor(0x88000000);')
s = s.replace('        telemetryView.setPadding(18, 14, 18, 14);', '        telemetryView.setPadding(14, 8, 14, 8);')
s = s.replace(
    '        FrameLayout.LayoutParams telemetryParams = new FrameLayout.LayoutParams(\n'
    '                FrameLayout.LayoutParams.MATCH_PARENT,\n'
    '                FrameLayout.LayoutParams.WRAP_CONTENT);\n'
    '        telemetryParams.gravity = Gravity.BOTTOM;\n'
    '        root.addView(telemetryView, telemetryParams);',
    '        FrameLayout.LayoutParams telemetryParams = new FrameLayout.LayoutParams(\n'
    '                FrameLayout.LayoutParams.WRAP_CONTENT,\n'
    '                FrameLayout.LayoutParams.WRAP_CONTENT);\n'
    '        telemetryParams.gravity = Gravity.BOTTOM | Gravity.START;\n'
    '        root.addView(telemetryView, telemetryParams);\n\n'
    '        modeButton = new TextView(this);\n'
    '        modeButton.setText("⚙");\n'
    '        modeButton.setTextColor(Color.WHITE);\n'
    '        modeButton.setTextSize(20f);\n'
    '        modeButton.setGravity(Gravity.CENTER);\n'
    '        modeButton.setBackgroundColor(0x66000000);\n'
    '        int modeSize = (int) (48f * getResources().getDisplayMetrics().density);\n'
    '        int modeMargin = (int) (8f * getResources().getDisplayMetrics().density);\n'
    '        FrameLayout.LayoutParams modeParams = new FrameLayout.LayoutParams(modeSize, modeSize);\n'
    '        modeParams.gravity = Gravity.TOP | Gravity.END;\n'
    '        modeParams.topMargin = modeMargin;\n'
    '        modeParams.rightMargin = modeMargin;\n'
    '        modeButton.setOnClickListener(v -> {\n'
    '            debugMode = !debugMode;\n'
    '            statusView.setVisibility(debugMode ? View.VISIBLE : View.GONE);\n'
    '            modeButton.setText(debugMode ? "DBG" : "⚙");\n'
    '            overlayView.setDebugMode(debugMode);\n'
    '            updateTelemetry();\n'
    '        });\n'
    '        root.addView(modeButton, modeParams);'
)

s = s.replace(
    '        setContentView(root);\n\n        cameraExecutor',
    '        setContentView(root);\n'
    '        requestHomeShortcutOnce();\n\n'
    '        cameraExecutor'
)

s = s.replace(
    '    protected void onResume() {\n        super.onResume();\n',
    '    protected void onResume() {\n        super.onResume();\n        enterImmersiveMode();\n'
)

insert_before_detector = '''
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void requestHomeShortcutOnce() {
        try {
            if (getSharedPreferences("ac360_ui", MODE_PRIVATE)
                    .getBoolean("shortcut_requested", false)) return;
            ShortcutManager manager = getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return;
            Intent intent = new Intent(this, MainActivityAlpha9.class);
            intent.setAction(Intent.ACTION_MAIN);
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "ac360-drive")
                    .setShortLabel("AntiColisión 360")
                    .setLongLabel("Abrir AntiColisión 360")
                    .setIntent(intent)
                    .build();
            if (manager.requestPinShortcut(shortcut, null)) {
                getSharedPreferences("ac360_ui", MODE_PRIVATE)
                        .edit().putBoolean("shortcut_requested", true).apply();
            }
        } catch (Throwable ignored) {
        }
    }

'''
s = s.replace('    private void initializeDetector() {', insert_before_detector + '    private void initializeDetector() {')

needle = '''                boolean vulnerable = raw.equals("person")
                        || raw.equals("bicycle")
                        || raw.equals("motorcycle");
                rawBoxes.add(new RawBox(rect, translated, category.getScore(), vulnerable));'''
replacement = '''                boolean vulnerable = raw.equals("person")
                        || raw.equals("bicycle")
                        || raw.equals("motorcycle");
                if (!plausibleRoadDetection(rect, category.getScore(), vulnerable,
                        sourceWidth, sourceHeight)) continue;
                rawBoxes.add(new RawBox(rect, translated, category.getScore(), vulnerable));'''
if needle not in s:
    raise SystemExit('raw detection insertion point not found')
s = s.replace(needle, replacement)

needle = '''            final int filteredCount = filteredByScreen;
            runOnUiThread(() -> {
                overlayView.setResults(trackedBoxes, laneState, sourceWidth, sourceHeight);'''
replacement = '''            final int filteredCount = filteredByScreen;
            final List<TrackedBox> visibleBoxes = debugMode
                    ? new ArrayList<>(trackedBoxes)
                    : selectRelevant(trackedBoxes, sourceWidth, sourceHeight);
            lastLaneState = laneState;
            runOnUiThread(() -> {
                overlayView.setResults(visibleBoxes, laneState, sourceWidth, sourceHeight, debugMode);'''
if needle not in s:
    raise SystemExit('overlay call insertion point not found')
s = s.replace(needle, replacement)

helpers = '''
    private static boolean plausibleRoadDetection(RectF rect,
                                                   float score,
                                                   boolean vulnerable,
                                                   int sourceWidth,
                                                   int sourceHeight) {
        if (rect == null || sourceWidth <= 0 || sourceHeight <= 0) return false;
        if (rect.width() < 4f || rect.height() < 4f) return false;
        float frameArea = sourceWidth * (float) sourceHeight;
        float areaRatio = area(rect) / Math.max(1f, frameArea);
        boolean edge = rect.left <= sourceWidth * 0.02f
                || rect.right >= sourceWidth * 0.98f;
        if (vulnerable && edge && areaRatio > 0.22f && score < 0.58f) return false;
        if (!vulnerable && edge && areaRatio > 0.70f && score < 0.55f) return false;
        return true;
    }

    private static List<TrackedBox> selectRelevant(List<TrackedBox> boxes,
                                                    int sourceWidth,
                                                    int sourceHeight) {
        List<TrackedBox> candidates = new ArrayList<>();
        float frameArea = Math.max(1f, sourceWidth * (float) sourceHeight);
        for (TrackedBox box : boxes) {
            float bottom = box.rect.bottom / Math.max(1f, sourceHeight);
            float areaRatio = area(box.rect) / frameArea;
            boolean ahead = "ADELANTE".equals(box.position);
            if (box.vulnerable) {
                if (box.score < 0.30f && bottom < 0.68f) continue;
            } else if (!ahead && bottom < 0.68f && areaRatio < 0.045f) {
                continue;
            }
            candidates.add(box);
        }
        Collections.sort(candidates, (a, b) -> Float.compare(
                relevanceScore(b, sourceWidth, sourceHeight),
                relevanceScore(a, sourceWidth, sourceHeight)));
        if (candidates.size() > 3) {
            return new ArrayList<>(candidates.subList(0, 3));
        }
        return candidates;
    }

    private static float relevanceScore(TrackedBox box, int sourceWidth, int sourceHeight) {
        float frameArea = Math.max(1f, sourceWidth * (float) sourceHeight);
        float areaRatio = area(box.rect) / frameArea;
        float bottom = box.rect.bottom / Math.max(1f, sourceHeight);
        float score = box.score * 25f + bottom * 28f + Math.min(28f, areaRatio * 360f);
        if (box.vulnerable) score += 100f;
        if ("ADELANTE".equals(box.position)) score += 70f;
        else if (!box.vulnerable) score -= 8f;
        return score;
    }

'''
s = s.replace('    private static boolean isDisplayLabel(String raw) {', helpers + '    private static boolean isDisplayLabel(String raw) {')

pattern = re.compile(r'    private void updateTelemetry\(\) \{.*?\n    \}\n\n    private static String cardinal', re.S)
new_telemetry = '''    private void updateTelemetry() {
        if (telemetryView == null) return;
        String accuracyText = Float.isNaN(gpsAccuracy)
                ? "--" : String.format(Locale.US, "%.0f m", gpsAccuracy);
        LaneState lane = lastLaneState;
        String laneText = lane != null && lane.usable()
                ? String.format(Locale.US, "CARRIL %.0f%%", lane.confidence * 100f)
                : "CARRIL —";

        if (!debugMode) {
            String gpsText = hasLocationPermission() && gpsActive ? accuracyText : "--";
            telemetryView.setText(String.format(Locale.US,
                    "%.0f km/h · GPS %s · %s", gpsSpeedKmh, gpsText, laneText));
            return;
        }

        String gpsPermission = hasLocationPermission()
                ? (gpsActive ? "GPS ✓" : "GPS ESPERANDO")
                : "GPS SIN PERMISO";
        String gpsHeadingText = Float.isNaN(gpsBearing)
                ? "--" : String.format(Locale.US, "%.0f° %s", gpsBearing, cardinal(gpsBearing));
        String imuHeadingText = Float.isNaN(imuHeading)
                ? "--" : String.format(Locale.US, "%.0f°", imuHeading);
        telemetryView.setText(String.format(Locale.US,
                "%s   %.1f km/h   PREC %s   %s\\nRUMBO GPS %s   ·   IMU %s\\n"
                        + "ACEL %.2f m/s²   ·   GIRO %.2f rad/s",
                gpsPermission, gpsSpeedKmh, accuracyText, laneText,
                gpsHeadingText, imuHeadingText, acceleration, gyroZ));
    }

    private static String cardinal'''
s, n = pattern.subn(new_telemetry, s, count=1)
if n != 1:
    raise SystemExit(f'updateTelemetry replacement count={n}')

s = s.replace('confidence < 0.60f', 'confidence < 0.72f')

s = s.replace(
    '        private int sourceHeight = 1;\n',
    '        private int sourceHeight = 1;\n        private boolean debugMode = false;\n'
)
s = s.replace(
    '''        void setResults(List<TrackedBox> boxes,
                        LaneState laneState,
                        int sourceWidth,
                        int sourceHeight) {
            this.boxes = new ArrayList<>(boxes);
            this.laneState = laneState;
            this.sourceWidth = Math.max(1, sourceWidth);
            this.sourceHeight = Math.max(1, sourceHeight);
            invalidate();
        }''',
    '''        void setResults(List<TrackedBox> boxes,
                        LaneState laneState,
                        int sourceWidth,
                        int sourceHeight,
                        boolean debugMode) {
            this.boxes = new ArrayList<>(boxes);
            this.laneState = laneState;
            this.sourceWidth = Math.max(1, sourceWidth);
            this.sourceHeight = Math.max(1, sourceHeight);
            this.debugMode = debugMode;
            invalidate();
        }

        void setDebugMode(boolean debugMode) {
            this.debugMode = debugMode;
            invalidate();
        }'''
)
s = s.replace('            drawLane(canvas, scale, dx, dy);', '            if (debugMode) drawLane(canvas, scale, dx, dy);')

old_label = '''                String prefix = box.vulnerable ? "★ " : "";
                String text = String.format(Locale.US,
                        "%s%s · %s · T%d · %.0f%%",
                        prefix,
                        box.label,
                        shortPosition(box.position),
                        box.id,
                        box.score * 100f);'''
new_label = '''                String prefix = box.vulnerable ? "★ " : "";
                String text;
                if (debugMode) {
                    text = String.format(Locale.US,
                            "%s%s · %s · T%d · %.0f%%",
                            prefix,
                            box.label,
                            shortPosition(box.position),
                            box.id,
                            box.score * 100f);
                } else {
                    text = prefix + box.label + " " + box.position;
                }'''
if old_label not in s:
    raise SystemExit('overlay label block not found')
s = s.replace(old_label, new_label)

s = s.replace('Color.argb(210, 255, 218, 40)', 'Color.argb(150, 255, 218, 40)')
s = s.replace('Color.argb(210, 255, 255, 255)', 'Color.argb(150, 255, 255, 255)')

out.write_text(s, encoding='utf-8')
print(f'Generated {out} ({len(s)} chars)')
