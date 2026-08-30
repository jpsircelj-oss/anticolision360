from pathlib import Path
import re

base = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha94.java')
out = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha941.java')
s = base.read_text(encoding='utf-8')

if 'public final class MainActivityAlpha94' not in s:
    raise SystemExit('Alpha94 generated base class not found')

s = s.replace('MainActivityAlpha94', 'MainActivityAlpha941')
s = s.replace('Alpha 9.4', 'Alpha 9.4.1')
s = s.replace('ALPHA 9.4', 'ALPHA 9.4.1')

if 'import android.graphics.RadialGradient;' not in s:
    s = s.replace('import android.graphics.LinearGradient;\n',
                  'import android.graphics.LinearGradient;\nimport android.graphics.RadialGradient;\n')

# Stricter warning logic: do not go red from one geometric cue alone.
pattern = re.compile(
    r'    private int warningLevel\(TrackedBox box, int sourceWidth, int sourceHeight\) \{.*?\n    \}\n\n    private void playRedAlarmIfNeeded',
    re.S,
)
new_warning = '''    private int warningLevel(TrackedBox box, int sourceWidth, int sourceHeight) {
        if (box == null || sourceWidth <= 0 || sourceHeight <= 0) return 0;
        float frameArea = Math.max(1f, sourceWidth * (float) sourceHeight);
        float areaRatio = area(box.rect) / frameArea;
        float bottom = box.rect.bottom / Math.max(1f, sourceHeight);
        float widthRatio = box.rect.width() / Math.max(1f, sourceWidth);
        float heightRatio = box.rect.height() / Math.max(1f, sourceHeight);
        float speedBoost = clamp((float) (gpsSpeedKmh / 120.0), 0f, 1f);
        boolean ahead = "ADELANTE".equals(box.position);
        boolean bicycleOrMoto = "BICICLETA".equals(box.label)
                || "MOTOCICLETA".equals(box.label);
        boolean pedestrian = "PEATÓN".equals(box.label);
        boolean vulnerable = bicycleOrMoto || pedestrian;

        // Ignore weak giant detections: these caused false full-screen red warnings.
        if (vulnerable) {
            if (box.score < 0.34f) return 0;
            if ((areaRatio > 0.15f || widthRatio > 0.62f || heightRatio > 0.78f)
                    && box.score < 0.66f) return 0;
        } else {
            if (box.score < 0.38f) return 0;
            if (areaRatio > 0.30f && box.score < 0.62f) return 0;
        }

        // Vulnerable road users keep priority, but RED requires corroborating proximity cues.
        if (vulnerable) {
            float redBottom = (bicycleOrMoto ? 0.70f : 0.73f) - 0.05f * speedBoost;
            float redArea = (bicycleOrMoto ? 0.020f : 0.026f) - 0.004f * speedBoost;
            boolean redCombined = bottom >= redBottom && areaRatio >= redArea * 0.55f;
            boolean redVeryLarge = areaRatio >= redArea * 2.2f && box.score >= 0.56f;
            boolean redVeryLow = bottom >= redBottom + 0.12f
                    && areaRatio >= redArea * 0.32f && box.score >= 0.54f;
            if (redCombined || redVeryLarge || redVeryLow) return 2;

            float yellowBottom = 0.52f - 0.03f * speedBoost;
            float yellowArea = bicycleOrMoto ? 0.006f : 0.008f;
            if ((bottom >= yellowBottom && areaRatio >= yellowArea * 0.42f)
                    || areaRatio >= yellowArea) return 1;
            return 0;
        }

        // Lead vehicles: yellow early, red only when both vertical proximity and size agree.
        if (ahead) {
            float redBottom = 0.82f - 0.07f * speedBoost;
            float redArea = 0.072f - 0.022f * speedBoost;
            boolean redCombined = bottom >= redBottom && areaRatio >= redArea * 0.50f;
            boolean redVeryLarge = areaRatio >= redArea * 1.75f && box.score >= 0.58f;
            if (redCombined || redVeryLarge) return 2;

            float yellowBottom = 0.62f - 0.05f * speedBoost;
            float yellowArea = 0.020f - 0.004f * speedBoost;
            if ((bottom >= yellowBottom && areaRatio >= yellowArea * 0.45f)
                    || areaRatio >= yellowArea) return 1;
            return 0;
        }

        // Lateral vehicles stay quiet unless clearly close. RED needs both cues + confidence.
        float redBottom = 0.89f - 0.05f * speedBoost;
        float redArea = 0.110f - 0.022f * speedBoost;
        if (bottom >= redBottom && areaRatio >= redArea * 0.62f && box.score >= 0.50f) return 2;

        float yellowBottom = 0.76f - 0.04f * speedBoost;
        float yellowArea = 0.042f - 0.008f * speedBoost;
        if (bottom >= yellowBottom && areaRatio >= yellowArea * 0.55f) return 1;
        return 0;
    }

    private void playRedAlarmIfNeeded'''
s, n = pattern.subn(lambda m: new_warning, s, count=1)
if n != 1:
    raise SystemExit(f'warningLevel replacement count={n}')

# Replace the broad rectangular gradients with localized soft radial halos.
pattern = re.compile(
    r'    private static final class EdgeGlowView extends View \{.*?\n    \}\n\n    private static final class DetectionOverlay extends View \{',
    re.S,
)
new_glow = '''    private static final class EdgeGlowView extends View {
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

            if (leftLevel > 0) drawSide(canvas, true, leftLevel);
            if (rightLevel > 0) drawSide(canvas, false, rightLevel);
            if (frontLevel > 0) drawFront(canvas, frontLevel);

            if (leftLevel >= 2 || rightLevel >= 2 || frontLevel >= 2) {
                postInvalidateDelayed(65L);
            }
        }

        private int glowColor(int level) {
            float pulse = 1f;
            if (level >= 2) {
                double phase = (System.currentTimeMillis() % 760L) / 760.0 * Math.PI * 2.0;
                pulse = 0.62f + 0.38f * (float) ((Math.sin(phase) + 1.0) * 0.5);
            }
            int alpha = level >= 2 ? Math.round(205f * pulse) : 112;
            return level >= 2
                    ? Color.argb(alpha, 255, 24, 24)
                    : Color.argb(alpha, 255, 210, 30);
        }

        private RadialGradient radial(float cx, float cy, float radius, int color) {
            int mid = Color.argb(
                    Math.max(1, Color.alpha(color) / 2),
                    Color.red(color), Color.green(color), Color.blue(color));
            return new RadialGradient(
                    cx, cy, radius,
                    new int[]{color, mid, Color.TRANSPARENT},
                    new float[]{0f, 0.38f, 1f},
                    Shader.TileMode.CLAMP);
        }

        private void drawSide(Canvas canvas, boolean left, int level) {
            int color = glowColor(level);
            float cx = left ? 0f : getWidth();
            float cy = getHeight() * 0.53f;
            float radius = Math.min(getHeight() * 0.62f, getWidth() * 0.38f);
            paint.setShader(radial(cx, cy, radius, color));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
        }

        private void drawFront(Canvas canvas, int level) {
            int color = glowColor(level);
            float cx = getWidth() * 0.50f;
            float cy = 0f;
            float radius = getWidth() * 0.34f;
            paint.setShader(radial(cx, cy, radius, color));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
        }
    }

    private static final class DetectionOverlay extends View {'''
s, n = pattern.subn(lambda m: new_glow, s, count=1)
if n != 1:
    raise SystemExit(f'EdgeGlowView replacement count={n}')

out.write_text(s, encoding='utf-8')
print(f'Generated {out} ({len(s)} chars)')
