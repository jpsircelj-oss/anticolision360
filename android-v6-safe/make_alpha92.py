from pathlib import Path

base = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha91.java')
out = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha92.java')
s = base.read_text(encoding='utf-8')

if 'public final class MainActivityAlpha91' not in s:
    raise SystemExit('Alpha91 generated base class not found')

s = s.replace('MainActivityAlpha91', 'MainActivityAlpha92')
s = s.replace('Alpha 9.1', 'Alpha 9.2')
s = s.replace('ALPHA 9.1', 'ALPHA 9.2')

s = s.replace(
    'import android.Manifest;\n',
    'import android.Manifest;\nimport android.app.AlertDialog;\n'
)
s = s.replace(
    'import android.widget.TextView;\n',
    'import android.widget.TextView;\nimport android.widget.Toast;\n'
)

# Separate alert HUD: normal mode gets no DetectionOverlay boxes at all.
s = s.replace(
    '    private TextView telemetryView;\n',
    '    private TextView telemetryView;\n    private TextView alertView;\n'
)

needle = '''        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);'''
replacement = '''        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        alertView = new TextView(this);
        alertView.setTextColor(Color.rgb(0, 255, 210));
        alertView.setTextSize(18f);
        alertView.setGravity(Gravity.CENTER);
        alertView.setBackgroundColor(0xB0000000);
        alertView.setPadding(18, 10, 18, 10);
        alertView.setVisibility(View.GONE);
        FrameLayout.LayoutParams alertParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        alertParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        alertParams.topMargin = (int) (12f * getResources().getDisplayMetrics().density);
        root.addView(alertView, alertParams);

        statusView = new TextView(this);'''
if needle not in s:
    raise SystemExit('alert HUD insertion point not found')
s = s.replace(needle, replacement, 1)

# Replace gear behavior with explicit menu: shortcut or debug.
old_click = '''        modeButton.setOnClickListener(v -> {
            debugMode = !debugMode;
            statusView.setVisibility(debugMode ? View.VISIBLE : View.GONE);
            modeButton.setText(debugMode ? "DBG" : "⚙");
            overlayView.setDebugMode(debugMode);
            updateTelemetry();
        });'''
new_click = '''        modeButton.setOnClickListener(v -> showControlMenu());'''
if old_click not in s:
    raise SystemExit('modeButton click block not found')
s = s.replace(old_click, new_click, 1)

# Replace auto shortcut request with no automatic prompt. User controls it explicitly.
s = s.replace('        requestHomeShortcutOnce();\n', '')

# Add explicit control menu + shortcut request.
insert_point = '    private void requestHomeShortcutOnce() {'
if insert_point not in s:
    raise SystemExit('requestHomeShortcutOnce method not found')
menu_methods = '''    private void showControlMenu() {
        final String debugLabel = debugMode ? "SALIR DE DEBUG" : "ACTIVAR DEBUG";
        new AlertDialog.Builder(this)
                .setTitle("AntiColisión 360")
                .setItems(new String[]{"CREAR ACCESO DIRECTO", debugLabel, "CANCELAR"},
                        (dialog, which) -> {
                            if (which == 0) {
                                createHomeShortcut();
                            } else if (which == 1) {
                                debugMode = !debugMode;
                                statusView.setVisibility(debugMode ? View.VISIBLE : View.GONE);
                                alertView.setVisibility(View.GONE);
                                modeButton.setText(debugMode ? "DBG" : "⚙");
                                overlayView.setDebugMode(debugMode);
                                updateTelemetry();
                            }
                        })
                .show();
    }

    private void createHomeShortcut() {
        boolean modernRequested = false;
        try {
            ShortcutManager manager = getSystemService(ShortcutManager.class);
            if (manager != null && manager.isRequestPinShortcutSupported()) {
                Intent intent = new Intent(this, MainActivityAlpha92.class);
                intent.setAction(Intent.ACTION_MAIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "ac360-drive-92")
                        .setShortLabel("AntiColisión 360")
                        .setLongLabel("Abrir AntiColisión 360")
                        .setIntent(intent)
                        .build();
                modernRequested = manager.requestPinShortcut(shortcut, null);
            }
        } catch (Throwable ignored) {
        }

        if (modernRequested) {
            Toast.makeText(this,
                    "Confirmá Añadir a pantalla de inicio si Android lo solicita.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Intent launchIntent = new Intent(this, MainActivityAlpha92.class);
            launchIntent.setAction(Intent.ACTION_MAIN);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Intent addIntent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent);
            addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, "AntiColisión 360");
            addIntent.putExtra("duplicate", false);
            sendBroadcast(addIntent);
            Toast.makeText(this,
                    "Se solicitó el acceso directo. Revisá la pantalla principal.",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this,
                    "Tu launcher no permite crearlo automáticamente. Mantené pulsado el icono de AntiColisión 360 y arrastralo al inicio.",
                    Toast.LENGTH_LONG).show();
        }
    }

'''
s = s.replace(insert_point, menu_methods + insert_point, 1)

# In normal mode, no boxes are ever handed to the DetectionOverlay.
needle = '''            runOnUiThread(() -> {
                overlayView.setResults(visibleBoxes, laneState, sourceWidth, sourceHeight, debugMode);'''
replacement = '''            runOnUiThread(() -> {
                List<TrackedBox> overlayBoxes = debugMode
                        ? visibleBoxes : Collections.emptyList();
                overlayView.setResults(overlayBoxes, laneState, sourceWidth, sourceHeight, debugMode);
                updateAlertHud(debugMode ? Collections.emptyList() : visibleBoxes);'''
if needle not in s:
    raise SystemExit('overlay UI call not found')
s = s.replace(needle, replacement, 1)

# Add normal-mode HUD updater. Maximum two messages, no boxes.
insert_point = '    private static boolean plausibleRoadDetection('
if insert_point not in s:
    raise SystemExit('plausibleRoadDetection insertion point not found')
alert_method = '''    private void updateAlertHud(List<TrackedBox> alerts) {
        if (alertView == null) return;
        if (debugMode || alerts == null || alerts.isEmpty()) {
            alertView.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder();
        boolean vulnerable = false;
        int count = Math.min(2, alerts.size());
        for (int i = 0; i < count; i++) {
            TrackedBox box = alerts.get(i);
            if (i > 0) text.append("   ·   ");
            if (box.vulnerable) {
                text.append("★ ");
                vulnerable = true;
            }
            text.append(box.label).append(' ').append(box.position);
        }
        alertView.setText(text.toString());
        alertView.setTextColor(vulnerable ? Color.YELLOW : Color.rgb(0, 255, 210));
        alertView.setVisibility(View.VISIBLE);
    }

'''
s = s.replace(insert_point, alert_method + insert_point, 1)

# Absolutely prevent normal-mode drawing even if future code accidentally passes boxes.
needle = '''            if (boxes == null || boxes.isEmpty()) return;
            if (!debugMode) {
                drawMinimalAlerts(canvas);
                return;
            }'''
replacement = '''            if (!debugMode) return;
            if (boxes == null || boxes.isEmpty()) return;'''
if needle not in s:
    raise SystemExit('normal overlay guard not found')
s = s.replace(needle, replacement, 1)

out.write_text(s, encoding='utf-8')
print(f'Generated {out} ({len(s)} chars)')
