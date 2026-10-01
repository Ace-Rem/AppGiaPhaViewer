package com.android.acerem.xemgp.ui;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.android.acerem.xemgp.auth.AuthManager;
import com.android.acerem.xemgp.data.DataSyncManager;
import com.android.acerem.xemgp.data.FamilyData;
import com.android.acerem.xemgp.data.ImageRepository;
import com.android.acerem.xemgp.util.Ui;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loading gate for a remembered session. Login is never created behind this screen. */
public final class StartupLoadingActivity extends Activity {
    private TextView status;
    private Button retry;
    private ObjectAnimator decorationAnimation;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.configure(this);
        Ui.applyBars(this);
        build();
        beginStartup();
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        View orb = new View(this);
        orb.setAlpha(.18f);
        orb.setBackground(Ui.bg(Ui.ACCENT_SOFT, Ui.dp(this, 100)));
        FrameLayout.LayoutParams orbParams = new FrameLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 150), Gravity.TOP | Gravity.START);
        orbParams.leftMargin = Ui.dp(this, -58);
        orbParams.topMargin = Ui.dp(this, 76);
        root.addView(orb, orbParams);

        View card = new View(this);
        card.setAlpha(.26f);
        card.setRotation(-16f);
        card.setBackground(Ui.outline(Ui.SURFACE, Ui.LINE, Ui.dp(this, 18)));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 42), Gravity.BOTTOM | Gravity.END);
        cardParams.rightMargin = Ui.dp(this, -24);
        cardParams.bottomMargin = Ui.dp(this, 112);
        root.addView(card, cardParams);

        LinearLayoutBox content = new LinearLayoutBox(this);
        TextView eyebrow = Ui.text(this, "GIA PHẢ · FAMILY ARCHIVE", 11, Ui.ACCENT);
        eyebrow.setGravity(Gravity.CENTER);
        content.add(eyebrow, 34);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        content.add(spinner, 64);

        TextView heading = Ui.heading(this, "Đang mở\nkhông gian gia đình.", 26);
        heading.setGravity(Gravity.CENTER);
        content.add(heading, 82);

        status = Ui.text(this, "Đang kiểm tra dữ liệu…", 13, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        content.add(status, 52);

        retry = Ui.button(this, "Thử lại", true);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> beginStartup());
        content.add(retry, 52);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        contentParams.leftMargin = Ui.dp(this, 28);
        contentParams.rightMargin = Ui.dp(this, 28);
        root.addView(content, contentParams);
        setContentView(root);

        decorationAnimation = ObjectAnimator.ofFloat(card, View.ROTATION, -16f, -7f, -16f);
        decorationAnimation.setDuration(4200);
        decorationAnimation.setRepeatCount(ObjectAnimator.INFINITE);
        decorationAnimation.setInterpolator(new AccelerateDecelerateInterpolator());
        decorationAnimation.start();
    }

    private void beginStartup() {
        retry.setVisibility(View.GONE);
        setStatus("Đang kiểm tra dữ liệu…");
        executor.execute(() -> {
            // A remembered session with valid local data opens immediately.
            // ImageRepository performs only a background refresh afterwards.
            if (DataSyncManager.hasUsableLocalData(this)) {
                try {
                    FamilyData data = AuthManager.restore(this);
                    ImageRepository repository = ImageRepository.get(getApplicationContext());
                    repository.prepareFamilyIndex(data);
                    repository.printImageStorageStatus();
                    repository.syncForFamilyAsync(data);
                    postToScreen(this::openViewer);
                    return;
                } catch (Exception localSessionError) {
                    // Fall through to the normal source sync/login recovery.
                }
            }
            DataSyncManager.Result result = DataSyncManager.sync(this, this::setStatus);
            if (!result.ready) {
                postToScreen(() -> {
                    setStatus(result.message);
                    retry.setVisibility(View.VISIBLE);
                });
                return;
            }
            postToScreen(() -> setStatus("Đang khôi phục phiên đăng nhập…"));
            try {
                FamilyData data = AuthManager.restore(this);
                ImageRepository repository = ImageRepository.get(getApplicationContext());
                repository.prepareFamilyIndex(data);
                repository.printImageStorageStatus();
                if (result.online) {
                    repository.syncForFamilyAsync(data);
                }
                postToScreen(this::openViewer);
            } catch (Exception invalidSession) {
                AuthManager.clearActive();
                AuthManager.clearRemembered(this);
                postToScreen(this::openLogin);
            }
        });
    }

    private void openViewer() {
        startActivity(new Intent(this, ViewerActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void openLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void setStatus(String message) { postToScreen(() -> { if (status != null) status.setText(message); }); }
    private void postToScreen(Runnable action) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }

    @Override public void onBackPressed() { /* Keep the startup gate deterministic while synchronization is running. */ }
    @Override protected void onDestroy() {
        if (decorationAnimation != null) decorationAnimation.cancel();
        executor.shutdownNow();
        super.onDestroy();
    }

    /** Small vertical layout helper kept local so the loading screen does not alter the shared UI system. */
    private static final class LinearLayoutBox extends android.widget.LinearLayout {
        LinearLayoutBox(Activity context) { super(context); setOrientation(VERTICAL); setGravity(Gravity.CENTER); }
        void add(View child, int heightDp) { addView(child, new android.widget.LinearLayout.LayoutParams(-1, Ui.dp(getContext(), heightDp))); }
    }
}
