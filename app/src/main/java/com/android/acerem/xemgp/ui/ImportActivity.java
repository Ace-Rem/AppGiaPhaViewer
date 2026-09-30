package com.android.acerem.xemgp.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Compatibility entry point for existing navigation links; manual file picking was removed. */
public final class ImportActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, DataSyncActivity.class));
        finish();
    }
}
