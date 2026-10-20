package com.github.susanaluis877.apex.demo.target;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.susanaluis877.apex.demo.R;

/**
 * 目标任务页：领取按钮先禁用 1.5 秒（模拟加载）；可选弹出广告对话框；售罄时只显示 “Sold out”。
 */
public class TaskActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog adDialog;
    private int ignoredClicks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Pages.column(this, "Task");
        if (getIntent().getBooleanExtra(Pages.EXTRA_SOLD_OUT, false)) {
            TextView soldOut = new TextView(this);
            soldOut.setText("Sold out");
            soldOut.setTextSize(20);
            root.addView(soldOut);
            return;
        }

        Button claim = Pages.button(this, R.id.demo_claim, "Loading…");
        claim.setEnabled(false);
        root.addView(claim);
        handler.postDelayed(() -> {
            claim.setText("Claim reward");
            claim.setEnabled(true);
        }, 1_500);

        boolean loseFirstClick = getIntent().getBooleanExtra(Pages.EXTRA_LOSE_FIRST_CLICK, false);
        claim.setOnClickListener(v -> {
            if (loseFirstClick && ignoredClicks == 0) {
                ignoredClicks++;
                return;
            }
            startActivity(Pages.forward(this, DoneActivity.class));
            finish();
        });

        if (getIntent().getBooleanExtra(Pages.EXTRA_AD, false)) {
            handler.postDelayed(this::showAd, 500);
        }
    }

    private void showAd() {
        if (isFinishing()) {
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = Pages.dp(this, 24);
        content.setPadding(padding, padding, padding, padding);
        TextView label = new TextView(this);
        label.setText("AD");
        label.setTextSize(32);
        content.addView(label);
        Button close = Pages.button(this, R.id.demo_ad_close, "Close");
        content.addView(close);
        adDialog = new AlertDialog.Builder(this).setView(content).setCancelable(false).create();
        close.setOnClickListener(v -> adDialog.dismiss());
        adDialog.show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (adDialog != null) {
            adDialog.dismiss();
        }
        super.onDestroy();
    }
}
