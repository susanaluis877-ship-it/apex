package com.example.apex.demo;

import android.app.Application;

import com.example.apex.Apex;
import com.example.apex.ApexConfig;

/**
 * 模拟宿主：在 Application 中初始化 SDK。
 */
public class DemoApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Apex.init(this, new ApexConfig.Builder().setDebug(true).build());
    }
}
