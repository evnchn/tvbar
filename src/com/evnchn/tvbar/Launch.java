package com.evnchn.tvbar;

import android.app.Activity;
import android.content.Intent;

public class Launch extends Activity {
    @Override protected void onResume() {
        super.onResume();
        BarService.show(getApplicationContext(), getIntent());
        startService(new Intent(this, BarService.class));
        finish();
    }
}
