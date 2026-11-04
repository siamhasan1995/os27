package com.feedblocker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    public static final String PREFS = "feedblocker";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setTextSize(16);
        info.setText("Choose which apps to calm, then turn on \"Feed Blocker\" in Accessibility settings.\n\n"
                + "When an app opens on its Home tab, Feed Blocker switches to another tab "
                + "(YouTube: Subscriptions, Facebook: Notifications, Instagram: Messages).\n");
        root.addView(info);

        addBox(root, sp, "yt", "YouTube: skip Home feed");
        addBox(root, sp, "fb", "Facebook: skip Home feed");
        addBox(root, sp, "ig", "Instagram: skip Home feed");
        addBox(root, sp, "short", "Also skip Shorts / Reels / Video tabs");

        Button open = new Button(this);
        open.setText("Open Accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void addBox(LinearLayout root, final SharedPreferences sp, final String key, String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(sp.getBoolean(key, true));
        cb.setOnCheckedChangeListener((b, checked) -> sp.edit().putBoolean(key, checked).apply());
        root.addView(cb);
    }
}
