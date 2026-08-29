package com.anticolision360.v6safe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemUi();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.rgb(7, 12, 18));

        TextView title = new TextView(this);
        title.setText("AntiColisión 360\nV6.0 Alpha 2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 36);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView ok = new TextView(this);
        ok.setText("ARRANQUE SEGURO ✓");
        ok.setTextColor(Color.rgb(80, 235, 180));
        ok.setTextSize(26f);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, 0, 0, 24);
        root.addView(ok, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(this);
        info.setText("Si estás viendo esta pantalla, la aplicación Android nativa abrió correctamente en tu teléfono.\n\nEn la siguiente versión activaremos la cámara trasera. Después agregaremos GPS/IMU y la inteligencia artificial por etapas.");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(18f);
        info.setGravity(Gravity.CENTER);
        root.addView(info, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
