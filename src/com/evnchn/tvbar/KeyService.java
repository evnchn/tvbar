package com.evnchn.tvbar;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

// Hold Back (0.45 s) anywhere to open the bar. The TV uses every key on live TV, but Back only acts on release,
// so we hold the press and either open the bar or replay a normal Back via the global action.
public class KeyService extends AccessibilityService {
    static final int HOLD_MS = 450;
    final Handler h = new Handler(Looper.getMainLooper());
    boolean held, opened, leftTaken, homeTaken;
    static final int HINT_MS = 150;  // a normal Back tap is shorter, so it shows nothing
    final Runnable open = new Runnable() { public void run() { opened = true; BarService.setMode(getApplicationContext(), true); } };
    final Runnable hint = new Runnable() { public void run() { hinted = true; BarService.hold(getApplicationContext(), true); } };
    boolean hinted;

    @Override protected boolean onKeyEvent(KeyEvent k) {
        Log.i("tvbar-key", k.getAction() + " " + KeyEvent.keyCodeToString(k.getKeyCode()) + " dev=" + k.getDeviceId());
        if (k.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) return false;
        if (k.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) return onLeft(k);
        // Home opens our TV Home first; Home again from TV Home falls through to Skyworth's real home.
        if (k.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            // A release follows whatever its press did; Home.resumed flips in between when the press passes through.
            // Open on the press, not the release: saves the press duration (~0.1-0.25 s). The release is swallowed.
            if (k.getAction() == KeyEvent.ACTION_DOWN) {
                if (k.getRepeatCount() > 0) return homeTaken;
                homeTaken = !Home.resumed;
                if (!homeTaken) return false;
                if (BarService.expanded) BarService.setMode(getApplicationContext(), false);
                startActivity(new android.content.Intent(this, Home.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
            if (!homeTaken) return false;
            homeTaken = false;
            return true;
        }
        if (k.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        // The release of a press we swallowed is ours too, even if the bar opened meanwhile (else the bar sees it and closes).
        if (k.getAction() == KeyEvent.ACTION_UP && held) {
            held = false;
            h.removeCallbacks(open);
            h.removeCallbacks(hint);
            if (hinted && !opened) BarService.hold(getApplicationContext(), false);
            hinted = false;
            if (!opened) performGlobalAction(GLOBAL_ACTION_BACK);
            return true;
        }
        // Pass through Back while the bar has the remote (it closes the panel), or when there is no bar.
        if (BarService.expanded || BarService.web == null) return false;
        if (k.getAction() == KeyEvent.ACTION_DOWN && k.getRepeatCount() == 0) {
            held = true; opened = false; hinted = false; h.postDelayed(hint, HINT_MS); h.postDelayed(open, HOLD_MS);
        }
        return true;
    }

    // ◀ on bare live TV opens our guide instead of Skyworth's. Vendor menus draw inside the same live-TV window,
    // so "bare" means: the active window is com.tianci.tv and has no text and no focused view (measured: bare = 13 empty
    // nodes; with the menu open = 44 nodes, text, 1 focused).
    boolean onLeft(KeyEvent k) {
        if (k.getAction() == KeyEvent.ACTION_DOWN) {
            if (k.getRepeatCount() > 0) return leftTaken;
            leftTaken = !BarService.expanded && BarService.web != null && bareLiveTv();
            Log.i("tvbar-key", "left: guide=" + leftTaken);
            if (leftTaken) BarService.setGuide(getApplicationContext());  // on the press; the release is swallowed below
            return leftTaken;
        }
        if (!leftTaken) return false;
        leftTaken = false;
        return true;
    }

    boolean bareLiveTv() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !"com.tianci.tv".contentEquals(root.getPackageName())) return false;
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        for (int seen = 0; !q.isEmpty() && seen < 80; seen++) {
            AccessibilityNodeInfo n = q.poll();
            if (n.isFocused() || (n.getText() != null && n.getText().length() > 0)) return false;
            for (int i = 0; i < n.getChildCount(); i++) if (n.getChild(i) != null) q.add(n.getChild(i));
        }
        return true;
    }

    // Once a minute while live TV is the active window, count the channel per (weekend?, hour) on the TV itself,
    // so the guide can offer "此時常看". Nothing leaves the TV.
    final Runnable sample = new Runnable() { public void run() {
        h.postDelayed(this, 60000);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !"com.tianci.tv".contentEquals(root.getPackageName())) return;
        int ch = BarService.curChannel(getApplicationContext());
        if (ch <= 0) return;
        android.content.SharedPreferences sp = getSharedPreferences("watch", MODE_PRIVATE);
        String key = Watch.slot(java.util.Calendar.getInstance(), 0) + ch;
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply();
        Log.i("tvbar-watch", key + "=" + sp.getInt(key, 0));
    } };

    // Enabled accessibility services are bound at boot, unlike our BOOT_COMPLETED receiver (the vendor disables it),
    // so this is what brings the bar back after a power-cycle without an external watchdog.
    @Override protected void onServiceConnected() {
        h.removeCallbacks(sample);
        h.postDelayed(sample, 60000);
        if (BarService.web == null)
            startActivity(new android.content.Intent(this, Launch.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override public void onInterrupt() {}
}
