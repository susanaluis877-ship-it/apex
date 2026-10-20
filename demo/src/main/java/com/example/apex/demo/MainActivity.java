package com.example.apex.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.example.apex.Apex;
import com.example.apex.ApexCallback;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        TextView text = new TextView(this);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        root.addView(text);
        setContentView(root);

        text.setText("Apex " + Apex.getVersion() + "\ninitialized=" + Apex.isInitialized());
        Apex.ping(new ApexCallback() {
            @Override
            public void onSuccess(String result) {
                text.append("\nping: " + result);
            }

            @Override
            public void onError(int code, String message) {
                text.append("\nping error " + code + ": " + message);
            }
        });
    }
}
