package com.android.acerem.xemgp.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.acerem.xemgp.data.DataRepository;
import com.android.acerem.xemgp.data.DataSyncManager;
import com.android.acerem.xemgp.util.Ui;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Startup screen for automatic archive synchronization. */
public final class DataSyncActivity extends Activity {
    private TextView status;
    private Button retry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.configure(this);
        Ui.applyBars(this);
        build();
        sync();
    }

    private void build() {
        LinearLayout root = Ui.column(this);
        root.setGravity(Gravity.CENTER);
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));
        root.setBackgroundColor(Ui.BG);
        TextView eyebrow = Ui.text(this, "GIA PHẢ · FAMILY ARCHIVE", 12, Ui.ACCENT);
        eyebrow.setGravity(Gravity.CENTER);
        root.addView(eyebrow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 36)));
        TextView heading = Ui.heading(this, "Đang chuẩn bị\ndữ liệu gia đình.", 30);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, new LinearLayout.LayoutParams(-1, Ui.dp(this, 92)));
        status = Ui.text(this, "Đang kiểm tra kết nối Internet…", 14, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));
        retry = Ui.button(this, "Thử lại", true);
        retry.setVisibility(android.view.View.GONE);
        retry.setOnClickListener(v -> sync());
        root.addView(retry, new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));
        setContentView(root);
    }

    private void sync() {
        retry.setVisibility(android.view.View.GONE);
        status.setText("Đang kiểm tra kết nối Internet…");
        executor.execute(() -> {
            DataSyncManager.Result result = DataSyncManager.sync(this, message -> runOnUiThread(() -> status.setText(message)));
            runOnUiThread(() -> finishSync(result));
        });
    }

    private void finishSync(DataSyncManager.Result result) {
        status.setText(result.message);
        if (result.ready) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        } else {
            retry.setVisibility(android.view.View.VISIBLE);
        }
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
