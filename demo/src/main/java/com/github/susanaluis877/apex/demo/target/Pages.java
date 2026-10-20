package com.github.susanaluis877.apex.demo.target;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 目标页面共用的布局和参数。场景由 intent extra 控制，保证每次运行结果确定。
 */
final class Pages {

    /** 任务页弹出广告对话框。 */
    static final String EXTRA_AD = "ad";
    /** 点击开始后进入“已售罄”页面。 */
    static final String EXTRA_SOLD_OUT = "soldOut";
    /** 领取按钮第一次点击无效，用来演示重试。 */
    static final String EXTRA_LOSE_FIRST_CLICK = "loseFirstClick";

    private Pages() {
    }

    static LinearLayout column(Activity activity, String title) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setFitsSystemWindows(true);
        int padding = dp(activity, 24);
        root.setPadding(padding, padding, padding, padding);
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextSize(24);
        root.addView(heading);
        activity.setContentView(root);
        return root;
    }

    static Button button(Activity activity, int id, String text) {
        Button button = new Button(activity);
        button.setId(id);
        // 关掉全大写，否则部分主题下无障碍读到的文本是 "CLAIM"
        button.setAllCaps(false);
        button.setText(text);
        return button;
    }

    /** 把场景参数原样传给下一个页面。 */
    static Intent forward(Activity from, Class<? extends Activity> to) {
        Intent intent = new Intent(from, to);
        Intent current = from.getIntent();
        intent.putExtra(EXTRA_AD, current.getBooleanExtra(EXTRA_AD, false));
        intent.putExtra(EXTRA_SOLD_OUT, current.getBooleanExtra(EXTRA_SOLD_OUT, false));
        intent.putExtra(EXTRA_LOSE_FIRST_CLICK, current.getBooleanExtra(EXTRA_LOSE_FIRST_CLICK, false));
        return intent;
    }

    static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
