package com.android.acerem.xemgp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.android.acerem.xemgp.auth.AuthManager;
import com.android.acerem.xemgp.ui.DataSyncActivity;
import com.android.acerem.xemgp.ui.StartupLoadingActivity;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Class<?> destination = AuthManager.hasRememberedSession(this)
                ? StartupLoadingActivity.class
                : DataSyncActivity.class;
        startActivity(new Intent(this, destination));
        finish();
    }
}
