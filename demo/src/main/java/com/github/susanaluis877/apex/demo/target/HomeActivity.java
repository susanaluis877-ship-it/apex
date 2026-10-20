package com.github.susanaluis877.apex.demo.target;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import com.github.susanaluis877.apex.demo.R;

/**
 * 目标首页：一个“Start task”按钮。
 */
public class HomeActivity extends Activity {

    /** 构造进入首页的 intent，参数决定这次运行的场景。 */
    public static Intent intent(Context context, boolean ad, boolean soldOut, boolean loseFirstClick) {
        return new Intent(context, HomeActivity.class)
                .putExtra(Pages.EXTRA_AD, ad)
                .putExtra(Pages.EXTRA_SOLD_OUT, soldOut)
                .putExtra(Pages.EXTRA_LOSE_FIRST_CLICK, loseFirstClick);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Pages.column(this, "Home");
        root.addView(Pages.button(this, R.id.demo_start, "Start task"));
        findViewById(R.id.demo_start).setOnClickListener(v ->
                startActivity(Pages.forward(this, TaskActivity.class)));
    }
}
