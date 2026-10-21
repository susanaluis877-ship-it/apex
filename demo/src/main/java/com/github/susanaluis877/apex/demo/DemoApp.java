package com.github.susanaluis877.apex.demo;

import android.app.Application;

import com.github.susanaluis877.apex.Apex;
import com.github.susanaluis877.apex.ApexConfig;

/**
 * 模拟宿主：在 Application 中初始化 SDK。
 */
public class DemoApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Apex.init(this, new ApexConfig.Builder().setDebug(true).setStatusOverlay(true).build());
    }
}
