package com.github.susanaluis877.apex.demo;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 流程运行器：列出可运行的流程（代码里用 Java DSL 写的、assets 里的 JSON、从电脑推送的 JSON），点「运行」执行，
 * 显示进度和失败时的完整轨迹。
 * 下面的「内置演示」用 demo 自带的目标页面演示拦截器、失败分支和重试。
 *
 * <p>也可以用 adb 自动运行：
 * {@code adb shell am start -n com.github.susanaluis877.apex.demo/.MainActivity --es autorun flow --es flow brightness_max}
 * <ul>
 *   <li>{@code autorun}：{@code flow}（运行 {@code flow} 指定的流程）、{@code dsl}、{@code json}（内置演示）、
 *       {@code open}（只打开演示页面，供 apex.py run 使用）、{@code dump}（导出当前屏幕）</li>
 *   <li>{@code ad}、{@code soldOut}、{@code loseFirstClick}：内置演示的场景开关</li>
 *   <li>{@code cancelAfterMs}：开始运行后多久取消，用来演示取消</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final String TAG = "ApexDemo";
    private static final long SERVICE_WAIT_MS = 5_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout flows;
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
            autorun(autorun, getIntent().getStringExtra("flow"), System.currentTimeMillis());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshFlows();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        column.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column);
        setContentView(scroll);

        status = new TextView(this);
        column.addView(status);
        button(column, "打开无障碍设置", () -> Apex.openAccessibilitySettings(this));

        header(column, "流程");
        flows = new LinearLayout(this);
        flows.setOrientation(LinearLayout.VERTICAL);
        column.addView(flows);
        button(column, "刷新流程列表", this::refreshFlows);

        header(column, "内置演示");
        ad = checkBox(column, "弹出广告（演示拦截器）");
        soldOut = checkBox(column, "已售罄（演示失败分支）");
        loseFirstClick = checkBox(column, "第一次点击无效（演示重试）");
        button(column, "运行 Java DSL 流程", () -> startDemo(false));
        button(column, "运行 JSON 流程", () -> startDemo(true));

        header(column, "运行");
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

    /** 每个流程一行：名称、来源、状态数，点「运行」执行；解析或构建失败的显示原因。 */
    private void refreshFlows() {
        flows.removeAllViews();
        List<FlowStore.Entry> entries = new ArrayList<>();
        for (String name : CodeFlows.names()) {
            try {
                entries.add(new FlowStore.Entry(name, "代码", CodeFlows.ALL.get(name).get(), null));
            } catch (RuntimeException e) {
                entries.add(new FlowStore.Entry(name, "代码", null, e.getMessage()));
            }
        }
        entries.addAll(FlowStore.list(this));
        for (FlowStore.Entry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            String source = entry.source;
            name.setText(entry.flow != null
                    ? entry.name + "\n" + source + " · " + entry.flow.getStates().size() + " 个状态"
                    : entry.name + "\n" + source + " · 无法解析：" + entry.error);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            if (entry.flow != null) {
                Button run = new Button(this);
                run.setAllCaps(false);
                run.setText("运行");
                run.setContentDescription("运行 " + entry.name);
                run.setOnClickListener(v -> runFlow(entry.flow));
                row.addView(run);
            }
            flows.addView(row);
        }
        if (flows.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有流程。在 CodeFlows 里登记 Java DSL 流程，或用 apex.py push flow.json 从电脑推送 JSON。");
            flows.addView(empty);
        }
    }

    /** 等服务连接上再运行，最多等 {@link #SERVICE_WAIT_MS}。 */
    private void autorun(String mode, String flowName, long startedAt) {
        if (mode.equals("open")) {
            // 只打开演示页面不运行流程，供 apex.py run 从电脑上运行（这些页面没有导出，adb 打不开）
            startActivity(HomeActivity.intent(this, ad.isChecked(), soldOut.isChecked(), loseFirstClick.isChecked()));
        } else if (!Apex.isServiceConnected() && System.currentTimeMillis() - startedAt <= SERVICE_WAIT_MS) {
            handler.postDelayed(() -> autorun(mode, flowName, startedAt), 200);
        } else if (mode.equals("dump")) {
            Apex.dumpScreen((snapshot, text) -> {
                append(text);
                Log.i(TAG, "dump " + (snapshot != null ? "ok nodes=" + snapshot.nodes().size() : text));
            });
        } else if (mode.equals("flow")) {
            FlowStore.Entry entry = null;
            if (flowName != null && CodeFlows.ALL.containsKey(flowName)) {
                entry = new FlowStore.Entry(flowName, "代码", CodeFlows.ALL.get(flowName).get(), null);
            } else if (flowName != null) {
                entry = FlowStore.find(this, flowName);
            }
            if (entry == null || entry.flow == null) {
                String why = entry == null ? "没有找到流程 " + flowName : "流程 " + flowName + " 无法解析：" + entry.error;
                append(why);
                Log.e(TAG, why);
                return;
            }
            runFlow(entry.flow);
        } else {
            startDemo(mode.equals("json"));
        }
    }

    private void startDemo(boolean json) {
        Flow flow;
        try {
            flow = json ? DemoFlows.json(this) : DemoFlows.dsl();
        } catch (Exception e) {
            append("流程定义错误：" + e.getMessage());
            Log.e(TAG, "invalid flow", e);
            return;
        }
        append("内置演示（ad=" + ad.isChecked() + ", soldOut=" + soldOut.isChecked()
                + ", loseFirstClick=" + loseFirstClick.isChecked() + "）");
        startActivity(HomeActivity.intent(this, ad.isChecked(), soldOut.isChecked(), loseFirstClick.isChecked()));
        runFlow(flow);
    }

    private void runFlow(Flow flow) {
        append("开始 " + flow.getId());
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
                // 没有成功时显示完整轨迹：每一步做了什么、为什么在等、最后一屏的节点树
                append(result.isSuccess() || result.getStatus() == RunResult.Status.FAILURE
                        ? "结束：" + result
                        : result.formatTrace());
                Log.i(TAG, "finished flow=" + result.getFlowId() + " status=" + result.getStatus() + " path="
                        + result.getPath() + " interceptors=" + result.getInterceptorCounts() + " message="
                        + result.getMessage());
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

    private void header(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(4));
        parent.addView(view);
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void append(String line) {
        log.append(line + "\n");
    }
}
