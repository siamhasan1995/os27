package com.feedblocker;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FeedBlockerService extends AccessibilityService {

    private static final String YT = "com.google.android.youtube";
    private static final String FB = "com.facebook.katana";
    private static final String IG = "com.instagram.android";

    private long lastRun = 0;
    private long lastAction = 0;

    private static class Tab {
        String label;
        boolean selected;
        AccessibilityNodeInfo node;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastRun < 300 || now - lastAction < 1500) return;
        lastRun = now;

        String pkg = event.getPackageName().toString();
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        String prefKey;
        String[] feedLabels = {"home"};
        String[] shortLabels;
        String[] targets;

        switch (pkg) {
            case YT:
                prefKey = "yt";
                shortLabels = new String[]{"shorts"};
                targets = new String[]{"subscriptions"};
                break;
            case FB:
                prefKey = "fb";
                shortLabels = new String[]{"video", "reels"};
                targets = new String[]{"notifications", "menu"};
                break;
            case IG:
                prefKey = "ig";
                shortLabels = new String[]{"reels"};
                targets = new String[]{"messages", "profile"};
                break;
            default:
                return;
        }
        if (!sp.getBoolean(prefKey, true)) return;
        boolean blockShort = sp.getBoolean("short", true);

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<Tab> tabs = new ArrayList<>();
        collect(root, tabs, 0);

        boolean mustLeave = false;
        for (Tab t : tabs) {
            if (!t.selected) continue;
            if (matchesAny(t.label, feedLabels) || (blockShort && matchesAny(t.label, shortLabels))) {
                mustLeave = true;
                break;
            }
        }
        if (!mustLeave) return;

        for (String target : targets) {
            for (Tab t : tabs) {
                if (!t.selected && matches(t.label, target) && click(t.node)) {
                    lastAction = System.currentTimeMillis();
                    return;
                }
            }
        }
    }

    private void collect(AccessibilityNodeInfo n, List<Tab> out, int depth) {
        if (n == null || depth > 25 || out.size() > 400) return;
        String label = null;
        if (n.getText() != null && n.getText().length() > 0) {
            label = n.getText().toString();
        } else if (n.getContentDescription() != null && n.getContentDescription().length() > 0) {
            label = n.getContentDescription().toString();
        }
        if (label != null && label.length() <= 30) {
            Tab t = new Tab();
            t.label = label.toLowerCase(Locale.ROOT).trim();
            AccessibilityNodeInfo parent = n.getParent();
            t.selected = n.isSelected() || (parent != null && parent.isSelected());
            t.node = n;
            out.add(t);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), out, depth + 1);
        }
    }

    private boolean matchesAny(String label, String[] keys) {
        for (String k : keys) if (matches(label, k)) return true;
        return false;
    }

    private boolean matches(String label, String key) {
        return label.equals(key) || label.startsWith(key + ",") || label.startsWith(key + " ");
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        int guard = 0;
        while (c != null && !c.isClickable() && guard++ < 6) {
            c = c.getParent();
        }
        return c != null && c.isClickable()
                && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    @Override
    public void onInterrupt() {
    }
}
