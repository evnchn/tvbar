package com.evnchn.tvbar;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// Holds a WebView strip over everything; the page decides what to show and when to take the remote.
// Skyworth's PhoneWindowManager only allows overlay windows from a process at foreground importance,
// so show() is called from Launch (a transparent activity) and this service just keeps the process alive.
public class BarService extends Service {
    // Pages ship inside the APK so the UI works offline; they fetch data from the tvbar server and cache it.
    static final String DEFAULT_URL = "file:///android_asset/index.html";
    // Collapsed = hidden: hold Back opens the panel and ◀ the guide, so nothing needs to sit on screen.
    static final int COLLAPSED_DP = 0, EXPANDED_DP = 420;
    static WebView web;
    static boolean expanded, guide;
    static WindowManager.LayoutParams lp;
    static final Handler ui = new Handler(Looper.getMainLooper());

    static class Bar extends WebView {
        Bar(Context c) { super(c); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            // Collapse natively so Back always returns the remote, even if the page never loaded.
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) setMode(getContext().getApplicationContext(), false);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    // Called from page JS as TVBar.*; hops to the UI thread because WindowManager needs it.
    static class Bridge {  // also extended by Home.HomeBridge
        final Context c;
        Bridge(Context c) { this.c = c; }
        @JavascriptInterface public void expand() { ui.post(new Runnable() { public void run() { setMode(c, true); } }); }
        @JavascriptInterface public void collapse() { ui.post(new Runnable() { public void run() { setMode(c, false); } }); }
        @JavascriptInterface public void openGuide() { ui.post(new Runnable() { public void run() { setGuide(c); } }); }
        @JavascriptInterface public int curChannel() { return BarService.curChannel(c); }
        @JavascriptInterface public String eit(int num, int day) { return BarService.eit(c, num, day); }
        @JavascriptInterface public String frequent() { return Watch.frequent(c); }
        @JavascriptInterface public void lat(String m) { android.util.Log.i("tvbar-lat", m); }
        @JavascriptInterface public void play(final int num) {
            ui.post(new Runnable() { public void run() { tv(c, "playChannelByNum", num); setMode(c, false); } });
        }
    }

    // Realtek's android.app.TvManager ("tv_manager") is not in the SDK; reach it by reflection. Getters and playChannelByNum only.
    static Object tv(Context c, String method, Object... args) {
        try {
            Object tm = c.getSystemService("tv_manager");
            for (java.lang.reflect.Method m : tm.getClass().getMethods())
                if (m.getName().equals(method) && m.getParameterTypes().length == args.length) return m.invoke(tm, args);
        } catch (Exception e) { android.util.Log.w("tvbar", "tv " + method + ": " + e); }
        return null;
    }

    static int curChannel(Context c) {
        Object ci = tv(c, "getCurChannel");
        try { return ci == null ? -1 : ci.getClass().getField("iChNum").getInt(ci); } catch (Exception e) { return -1; }
    }

    // Broadcast (DVB EIT) EPG for channel number `num`, as JSON. The channel index is the line position in getChannelNameList
    // ("Ch 81 翡翠台" ...). EpgData times read 16 h early here; measured against epg.pw (which matches the screen), so add 57600 s.
    static String eit(Context c, int num, int day) {
        org.json.JSONArray out = new org.json.JSONArray();
        try {
            Object names = tv(c, "getChannelNameList", 0, 64, false);
            int idx = -1, i = 0;
            for (String line : String.valueOf(names).split("\n")) {
                if (line.trim().startsWith("Ch " + num + " ")) { idx = i; break; }
                if (line.trim().length() > 0) i++;
            }
            Object list = idx < 0 ? null : tv(c, "getEpgDailyListByChIdx", idx, day);
            if (list == null) return "[]";
            for (int k = 0; k < java.lang.reflect.Array.getLength(list); k++) {
                Object e = java.lang.reflect.Array.get(list, k);
                Class<?> t = e.getClass();
                String ext = (String) t.getField("m_eventExtendedDesc").get(e), shrt = (String) t.getField("m_eventShortDesc").get(e);
                out.put(new org.json.JSONObject()
                    .put("start", t.getField("m_startTime").getLong(e) + 57600)
                    .put("stop", t.getField("m_endTime").getLong(e) + 57600)
                    .put("title", t.getField("m_eventTitle").get(e))
                    .put("desc", ext != null && ext.length() > 0 ? ext : shrt));
            }
        } catch (Exception ex) { android.util.Log.w("tvbar", "eit " + num + ": " + ex); }
        return out.toString();
    }

    static void settings(WebView w) {
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);           // localStorage cache of the last good data
        w.getSettings().setAllowUniversalAccessFromFileURLs(true);  // file:// pages fetch from the tvbar server
    }

    // While Back is held the page draws a filling strip along its bottom edge. The window goes straight to panel height
    // (still unfocusable, transparent above the strip) so opening needs no second resize: a 6 dp window cost ~230 ms more.
    static void hold(Context c, boolean on) {
        if (web == null || expanded) return;
        lp.height = on ? (int) (EXPANDED_DP * c.getResources().getDisplayMetrics().density) : 0;
        ((WindowManager) c.getSystemService(WINDOW_SERVICE)).updateViewLayout(web, lp);
        web.loadUrl("javascript:window.tvbar&&tvbar.hold(" + on + ")");
    }

    // One window resize straight to full screen; going via the panel height cost a second layout.
    static void setGuide(Context c) { setMode(c, true, true); }

    static void setMode(Context c, boolean expanded) { setMode(c, expanded, false); }

    static void setMode(Context c, boolean expanded, boolean full) {
        if (web == null) return;
        android.util.Log.i("tvbar-lat", "java " + (full ? "guide" : expanded ? "panel" : "close"));
        BarService.expanded = expanded;
        guide = full;
        float d = c.getResources().getDisplayMetrics().density;
        lp.height = full ? WindowManager.LayoutParams.MATCH_PARENT : (int) ((expanded ? EXPANDED_DP : COLLAPSED_DP) * d);
        // Overlay windows don't inherit GPU rendering; without this flag the WebView paints in software (laggy).
        lp.flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | (expanded ? 0
            : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        ((WindowManager) c.getSystemService(WINDOW_SERVICE)).updateViewLayout(web, lp);
        if (expanded) web.requestFocus();
        web.loadUrl(full ? "javascript:window.tvbar&&tvbar.guide()" : "javascript:window.tvbar&&tvbar.mode(" + expanded + ")");
    }

    static void show(Context c, Intent i) {
        if (web != null) { setMode(c, true); return; }
        final String url = DEFAULT_URL;  // Launch is exported: never load a URL another app passes in
        WindowManager wm = (WindowManager) c.getSystemService(WINDOW_SERVICE);
        if (web != null) wm.removeView(web);
        web = new Bar(c);
        web.setBackgroundColor(Color.TRANSPARENT);
        settings(web);
        web.addJavascriptInterface(new Bridge(c), "TVBar");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) { v.loadUrl("javascript:window.tvbar&&tvbar.mode(" + expanded + ")"); }
            // Server down: show nothing rather than WebView's error page, and retry in a minute.
            @Override public void onReceivedError(final WebView v, int code, String desc, String failing) {
                v.loadUrl("about:blank");
                ui.postDelayed(new Runnable() { public void run() { if (v == web) v.loadUrl(url); } }, 60000);
            }
        });
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, 0,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, 0, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        wm.addView(web, lp);
        web.loadUrl(url);
        setMode(c, i.getBooleanExtra("expand", false));
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (web != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(web);
        web = null;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
