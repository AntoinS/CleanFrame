package com.cleanframe.addon.appleremote;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** All user-facing management belongs to the signed CleanTV frontend. */
public final class SetupActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new Intent().setComponent(new ComponentName("com.cleanframe.philips", "com.cleanframe.philips.MainActivity"))
                .putExtra("cleanframeAddon", "apple-remote").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException unavailable) {
            android.widget.Toast.makeText(this, "Open or install CleanTV 0.8.0 to manage this add-on.", android.widget.Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
