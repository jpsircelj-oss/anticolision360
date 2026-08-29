package com.anticolision360.v6safe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView view = new TextView(this);
        view.setText("AntiColisión 360 V6\n\nARRANQUE BÁSICO OK");
        view.setTextSize(28f);
        view.setTextColor(Color.BLACK);
        view.setBackgroundColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(32, 32, 32, 32);
        setContentView(view);
    }
}
