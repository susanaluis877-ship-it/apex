package com.github.susanaluis877.apex.demo;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.susanaluis877.apex.Apex;
import com.github.susanaluis877.apex.FlowListener;
import com.github.susanaluis877.apex.RunHandle;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.demo.target.HomeActivity;
import com.github.susanaluis877.apex.flow.Flow;

/**
 * Demo 控制台：查看服务状态、选择场景、运行流程、导出当前屏幕的节点树。
 *
 * <p>也可以用 adb 自动运行：
 * {@code adb shell am start -n com.github.susanaluis877.apex.demo/.MainActivity --es autorun json --ez ad true}
 * <ul>
 *   <li>{@code autorun}：{@code dsl}、{@code json}，或 {@code dump}（导出当前屏幕）</li>
 *   <li>{@code ad}、{@code soldOut}、{@code loseFirstClick}：场景开关</li>
 *   <li>{@code cancelAfterMs}：开始运行后多久取消，用来演示取消</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final String TAG = "ApexDemo";
    private static final long SERVICE_WAIT_MS = 5_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView log;
    private CheckBox ad;
    private CheckBox soldOut;
    private CheckBox loseFirstClick;
    private RunHandle handle;
    private long cancelAfterMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        String autorun = getIntent().getStringExtra("autorun");
        if (autorun != null) {
            ad.setChecked(getIntent().getBooleanExtra("ad", false));
            soldOut.setChecked(getIntent().getBooleanExtra("soldOut", false));
            loseFirstClick.setChecked(getIntent().getBooleanExtra("loseFirstClick", false));
            cancelAfterMs = getIntent().getLongExtra("cancelAfterMs", 0);
            autorun(autorun, System.currentTimeMillis());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column);
        setContentView(scroll);

        status = new TextView(this);
        column.addView(status);

        ad = checkBox(column, "弹出广告（演示拦截器）");
        soldOut = checkBox(column, "已售罄（演示失败分支）");
        loseFirstClick = checkBox(column, "第一次点击无效（演示重试）");

        button(column, "打开无障碍设置", () -> Apex.openAccessibilitySettings(this));
        button(column, "运行 Java DSL 流程", () -> start(false));
        button(column, "运行 JSON 流程", () -> start(true));
        button(column, "取消", () -> {
            if (handle != null) {
                handle.cancel();
            }
        });
        button(column, "5 秒后导出当前屏幕", () -> {
            append("5 秒后导出，请切到要查看的页面…");
            handler.postDelayed(() -> Apex.dumpScreen((snapshot, text) -> append(text)), 5_000);
        });

        log = new TextView(this);
        log.setTextIsSelectable(true);
        column.addView(log);
    }

    private void refreshStatus() {
        status.setText("Apex " + Apex.getVersion()
                + "\n服务已开启：" + Apex.isServiceEnabled(this)
                + "\n服务已连接：" + Apex.isServiceConnected()
                + "\n正在运行：" + Apex.isRunning());
    }

    /** 等服务连接上再运行，最多等 {@link #SERVICE_WAIT_MS}。 */
    private void autorun(String mode, long startedAt) {
        if (!Apex.isServiceConnected() && System.currentTimeMillis() - startedAt <= SERVICE_WAIT_MS) {
            handler.postDelayed(() -> autorun(mode, startedAt), 200);
        } else if (mode.equals("dump")) {
            Apex.dumpScreen((snapshot, text) -> {
                append(text);
                Log.i(TAG, "dump " + (snapshot != null ? "ok nodes=" + snapshot.nodes().size() : text));
            });
        } else {
            start(mode.equals("json"));
        }
    }

    private void start(boolean json) {
        Flow flow;
        try {
            flow = json ? DemoFlows.json(this) : DemoFlows.dsl();
        } catch (Exception e) {
            append("流程定义错误：" + e.getMessage());
            Log.e(TAG, "invalid flow", e);
            return;
        }
        append("开始 " + (json ? "JSON" : "DSL") + " 流程 " + flow.getId()
                + "（ad=" + ad.isChecked() + ", soldOut=" + soldOut.isChecked()
                + ", loseFirstClick=" + loseFirstClick.isChecked() + "）");
        startActivity(HomeActivity.intent(this, ad.isChecked(), soldOut.isChecked(), loseFirstClick.isChecked()));
        handle = Apex.run(flow, new FlowListener() {
            @Override
            public void onStateEntered(String stateId, int step) {
                append("  进入 " + stateId + "（第 " + step + " 步）");
            }

            @Override
            public void onInterceptor(String interceptorId, int count) {
                append("  拦截器 " + interceptorId + "（第 " + count + " 次）");
            }

            @Override
            public void onFinished(RunResult result) {
                append("结束：" + result);
                Log.i(TAG, "finished status=" + result.getStatus() + " path=" + result.getPath()
                        + " interceptors=" + result.getInterceptorCounts() + " message=" + result.getMessage());
                Toast.makeText(MainActivity.this, "Apex: " + result.getStatus(), Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        });
        if (cancelAfterMs > 0) {
            RunHandle toCancel = handle;
            handler.postDelayed(toCancel::cancel, cancelAfterMs);
            cancelAfterMs = 0;
        }
        refreshStatus();
    }

    private CheckBox checkBox(LinearLayout parent, String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        parent.addView(box);
        return box;
    }

    private void button(LinearLayout parent, String text, Runnable onClick) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(v -> onClick.run());
        parent.addView(button);
    }

    private void append(String line) {
        log.append(line + "\n");
    }
}
