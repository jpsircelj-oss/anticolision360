from pathlib import Path
import re

base = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha9.java')
out = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha91.java')
s = base.read_text(encoding='utf-8')

if 'public final class MainActivityAlpha9' not in s:
    raise SystemExit('Alpha9 generated base class not found')

s = s.replace('MainActivityAlpha9', 'MainActivityAlpha91')
s = s.replace('Alpha 9', 'Alpha 9.1')
s = s.replace('ALPHA 9', 'ALPHA 9.1')

# Normal driving HUD: smaller and less technical.
s = s.replace(
    '"%.0f km/h · GPS %s · %s", gpsSpeedKmh, gpsText, laneText',
    '"%.0f km/h  ·  GPS %s", gpsSpeedKmh, gpsText'
)

# Stronger rejection of giant/edge false detections.
needle = '''        if (vulnerable && edge && areaRatio > 0.22f && score < 0.58f) return false;
        if (!vulnerable && edge && areaRatio > 0.70f && score < 0.55f) return false;
        return true;'''
replacement = '''        if (vulnerable && edge && areaRatio > 0.22f && score < 0.58f) return false;
        if (!vulnerable && edge && areaRatio > 0.70f && score < 0.55f) return false;
        if (!vulnerable && areaRatio > 0.38f && score < 0.66f) return false;
        if (!vulnerable
                && rect.width() / Math.max(1f, sourceWidth) > 0.84f
                && rect.height() / Math.max(1f, sourceHeight) > 0.34f) return false;
        return true;'''
if needle not in s:
    raise SystemExit('plausibleRoadDetection block not found')
s = s.replace(needle, replacement)

# Replace relevance selection: deduplicate tracks and show max 2 in normal mode.
pattern = re.compile(r'    private static List<TrackedBox> selectRelevant\(.*?\n    \}\n\n    private static float relevanceScore', re.S)
new_select = '''    private static List<TrackedBox> selectRelevant(List<TrackedBox> boxes,
                                                    int sourceWidth,
                                                    int sourceHeight) {
        List<TrackedBox> candidates = new ArrayList<>();
        float frameArea = Math.max(1f, sourceWidth * (float) sourceHeight);
        for (TrackedBox box : boxes) {
            float bottom = box.rect.bottom / Math.max(1f, sourceHeight);
            float areaRatio = area(box.rect) / frameArea;
            boolean ahead = "ADELANTE".equals(box.position);
            if (box.vulnerable) {
                if (box.score < 0.32f && bottom < 0.70f) continue;
            } else {
                if (!ahead && bottom < 0.74f && areaRatio < 0.065f) continue;
                if (ahead && bottom < 0.47f && areaRatio < 0.012f) continue;
            }
            candidates.add(box);
        }
        Collections.sort(candidates, (a, b) -> Float.compare(
                relevanceScore(b, sourceWidth, sourceHeight),
                relevanceScore(a, sourceWidth, sourceHeight)));

        List<TrackedBox> selected = new ArrayList<>();
        for (TrackedBox candidate : candidates) {
            boolean duplicate = false;
            for (TrackedBox kept : selected) {
                float overlap = iou(candidate.rect, kept.rect);
                float dx = candidate.rect.centerX() - kept.rect.centerX();
                float dy = candidate.rect.centerY() - kept.rect.centerY();
                float norm = (float) Math.sqrt(dx * dx + dy * dy)
                        / Math.max(1f, (float) Math.sqrt(sourceWidth * (double) sourceWidth
                                + sourceHeight * (double) sourceHeight));
                boolean sameMeaning = candidate.label.equals(kept.label)
                        && candidate.position.equals(kept.position);
                if (overlap >= 0.28f || (sameMeaning && norm < 0.075f)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) selected.add(candidate);
            if (selected.size() >= 2) break;
        }
        return selected;
    }

    private static float relevanceScore'''
s, n = pattern.subn(lambda m: new_select, s, count=1)
if n != 1:
    raise SystemExit(f'selectRelevant replacement count={n}')

# Smaller settings control.
s = s.replace('modeButton.setTextSize(20f);', 'modeButton.setTextSize(17f);')
s = s.replace('48f * getResources().getDisplayMetrics().density', '38f * getResources().getDisplayMetrics().density')
s = s.replace('8f * getResources().getDisplayMetrics().density', '6f * getResources().getDisplayMetrics().density')

# Auto-hide settings button; invisible top-right hotspot restores it.
needle = '''        root.addView(modeButton, modeParams);'''
replacement = '''        View settingsHotspot = new View(this);
        settingsHotspot.setBackgroundColor(Color.TRANSPARENT);
        int hotspotSize = (int) (58f * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams hotspotParams = new FrameLayout.LayoutParams(hotspotSize, hotspotSize);
        hotspotParams.gravity = Gravity.TOP | Gravity.END;
        root.addView(settingsHotspot, hotspotParams);
        root.addView(modeButton, modeParams);
        settingsHotspot.setOnClickListener(v -> {
            modeButton.setVisibility(View.VISIBLE);
            modeButton.postDelayed(() -> {
                if (!debugMode) modeButton.setVisibility(View.GONE);
            }, 3500L);
        });
        modeButton.postDelayed(() -> {
            if (!debugMode) modeButton.setVisibility(View.GONE);
        }, 4500L);'''
if needle not in s:
    raise SystemExit('modeButton insertion point not found')
s = s.replace(needle, replacement, 1)

# Add paints used by the clean alert banners.
s = s.replace(
    '        private final Paint lanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n',
    '        private final Paint lanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n'
    '        private final Paint minimalTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n'
    '        private final Paint minimalBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n'
    '        private final Paint minimalStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n'
)

needle = '''            lanePaint.setStyle(Paint.Style.STROKE);
            lanePaint.setStrokeWidth(4f);
            lanePaint.setStrokeCap(Paint.Cap.ROUND);'''
replacement = '''            lanePaint.setStyle(Paint.Style.STROKE);
            lanePaint.setStrokeWidth(4f);
            lanePaint.setStrokeCap(Paint.Cap.ROUND);

            minimalTextPaint.setColor(Color.WHITE);
            minimalTextPaint.setTextSize(28f);
            minimalTextPaint.setFakeBoldText(true);
            minimalBgPaint.setStyle(Paint.Style.FILL);
            minimalBgPaint.setColor(Color.argb(185, 0, 0, 0));
            minimalStrokePaint.setStyle(Paint.Style.STROKE);
            minimalStrokePaint.setStrokeWidth(4f);'''
if needle not in s:
    raise SystemExit('paint constructor block not found')
s = s.replace(needle, replacement, 1)

# In normal mode, draw no object boxes at all: only one/two compact alert banners.
needle = '''            if (debugMode) drawLane(canvas, scale, dx, dy);

            if (boxes == null || boxes.isEmpty()) return;
            List<RectF> occupiedLabels = new ArrayList<>();'''
replacement = '''            if (debugMode) drawLane(canvas, scale, dx, dy);

            if (boxes == null || boxes.isEmpty()) return;
            if (!debugMode) {
                drawMinimalAlerts(canvas);
                return;
            }
            List<RectF> occupiedLabels = new ArrayList<>();'''
if needle not in s:
    raise SystemExit('onDraw normal-mode block not found')
s = s.replace(needle, replacement, 1)

minimal_method = '''
        private void drawMinimalAlerts(Canvas canvas) {
            if (boxes == null || boxes.isEmpty()) return;
            float density = getResources().getDisplayMetrics().density;
            float top = 18f * density;
            float gap = 8f * density;
            float padX = 16f * density;
            float padY = 9f * density;
            float corner = 12f * density;

            int count = Math.min(2, boxes.size());
            for (int i = 0; i < count; i++) {
                TrackedBox box = boxes.get(i);
                int accent = box.vulnerable ? Color.YELLOW : Color.rgb(0, 255, 210);
                String prefix = box.vulnerable ? "★ " : "";
                String text = prefix + box.label + " " + box.position;
                float textWidth = minimalTextPaint.measureText(text);
                float h = minimalTextPaint.getTextSize() + padY * 2f + 4f;
                float w = Math.min(getWidth() - 24f * density, textWidth + padX * 2f);
                float left = (getWidth() - w) * 0.5f;
                RectF r = new RectF(left, top, left + w, top + h);

                minimalBgPaint.setColor(Color.argb(i == 0 ? 205 : 165, 0, 0, 0));
                minimalStrokePaint.setColor(accent);
                canvas.drawRoundRect(r, corner, corner, minimalBgPaint);
                canvas.drawRoundRect(r, corner, corner, minimalStrokePaint);
                minimalTextPaint.setColor(accent);
                canvas.drawText(text,
                        r.left + padX,
                        r.top + padY + minimalTextPaint.getTextSize(),
                        minimalTextPaint);
                top += h + gap;
            }
        }

'''
s = s.replace('        private void drawLane(Canvas canvas, float scale, float dx, float dy) {',
              minimal_method + '        private void drawLane(Canvas canvas, float scale, float dx, float dy) {', 1)

out.write_text(s, encoding='utf-8')
print(f'Generated {out} ({len(s)} chars)')
