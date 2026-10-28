package com.evnchn.tvbar;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// Our home screen: a full-screen page (home.html, bundled) with the live-TV tile and app tiles.
// The activity is translucent so Skyworth's SkyLauncher below only pauses: on onStop it runs releaseTV, which mutes
// the speaker and switches the tuner away (measured 2026-10-23). Translucent keeps live TV playing, like the stock home.
public class Home extends Activity {
    static final String URL = "file:///android_asset/home.html";
    static boolean resumed;
    WebView web;

    class HomeBridge extends BarService.Bridge {
        HomeBridge() { super(getApplicationContext()); }
        @JavascriptInterface public void launch(final String pkg) {
            runOnUiThread(new Runnable() { public void run() {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startActivity(i);
            } });
        }
        @JavascriptInterface public void open(final String pkg, final String cls) {
            runOnUiThread(new Runnable() { public void run() {
                try { startActivity(new Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { launch(pkg); }
            } });
        }
        @JavascriptInterface public void watch(final int num) {
            runOnUiThread(new Runnable() { public void run() {
                startActivity(new Intent().setClassName("com.tianci.tv", "com.tianci.tv.SkyLauncher").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                if (num > 0) BarService.tv(getApplicationContext(), "playChannelByNum", num);
            } });
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.Log.i("tvbar-lat", "java home create");
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        BarService.settings(web);
        web.addJavascriptInterface(new HomeBridge(), "TVBar");
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl(URL);
    }

    // The overlay bar would sit on top of this screen; hide it while Home is in front.
    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (BarService.web != null) BarService.web.setVisibility(android.view.View.VISIBLE);
    }

    @Override protected void onResume() {
        super.onResume();
        android.util.Log.i("tvbar-lat", "java home resume");
        resumed = true;
        if (BarService.web != null) BarService.web.setVisibility(android.view.View.GONE);
        web.loadUrl("javascript:window.home&&home.refresh()");
    }

    // Back from Home returns to live TV rather than leaving a blank task.
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            startActivity(new Intent().setClassName("com.tianci.tv", "com.tianci.tv.SkyLauncher").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
