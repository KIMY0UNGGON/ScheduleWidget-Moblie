package com.schedulewidget.mobile.pet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Runs in the test APK's own UID, whose runtime contains only the Android platform classes. */
public class FloatingPetBoundsBackgroundActivity extends Activity {
    private int taps;

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("finish", false)) finish();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        TextView text = new TextView(this);
        text.setText("BACKGROUND_TAPS:0");
        text.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.BLACK);
        text.setOnClickListener(view -> text.setText("BACKGROUND_TAPS:" + ++taps));
        setContentView(text);
    }
}
