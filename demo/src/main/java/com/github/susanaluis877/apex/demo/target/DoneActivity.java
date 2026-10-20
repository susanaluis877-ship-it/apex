package com.github.susanaluis877.apex.demo.target;

import android.app.Activity;
import android.os.Bundle;

/**
 * 目标完成页。
 */
public class DoneActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Pages.column(this, "Reward claimed ✓");
    }
}
