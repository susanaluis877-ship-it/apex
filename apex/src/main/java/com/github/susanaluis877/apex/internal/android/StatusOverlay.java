package com.github.susanaluis877.apex.internal.android;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.susanaluis877.apex.ApexLogger;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.internal.ApexLog;
import com.github.susanaluis877.apex.internal.engine.RunMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行状态悬浮窗：收起时是一个可拖动的小标签（颜色表示状态，文字是当前状态和步数），点一下展开成面板，
 * 显示流程、状态、步数、用时、失败原因和最近的轨迹，运行中可以取消。
 *
 * <ul>
 *   <li>用无障碍服务的 {@code TYPE_ACCESSIBILITY_OVERLAY} 窗口，不需要悬浮窗权限。快照只取应用窗口，所以它不会出现在快照里。</li>
 *   <li>它的视图把无障碍类名报成 {@link #A11Y_CLASS}，服务收到这类事件直接忽略，刷新悬浮窗不会唤醒引擎。</li>
 *   <li>坐标点击、滑动经过它时，先让它暂时不接收触摸（{@link #stepAside}），手势才会落到下面的 App 上。
 *       节点动作（ACTION_CLICK 等）不经过触摸，不受影响。</li>
 * </ul>
 *
 * <p>除了 {@link #stepAside} 和 {@link #setVisible} 可以在任意线程调用，其他方法都在主线程调用。
 */
public final class StatusOverlay {

    /** 悬浮窗视图报给无障碍服务的类名。 */
    public static final String A11Y_CLASS = "com.github.susanaluis877.apex.StatusOverlay";

    /**
     * 背景是一层半透明（50%）的白色衬底加细边框：能看到下面的页面，又不至于和下面的文字叠成一片（30% 时还是乱）。
     * 文字用深色加白色光晕，浅色页面上清楚，深色页面上靠衬底和光晕也看得清。
     */
    private static final int BACKING = 0x80FFFFFF;
    private static final int BORDER = 0x99888888;
    private static final int TEXT = 0xFF212121;
    private static final int HALO = 0xFFFFFFFF;
    private static final int BLUE = 0xFF1E88E5;
    private static final int GREEN = 0xFF43A047;
    private static final int RED = 0xFFD50000;
    private static final int GREY = 0xFF9E9E9E;
    /** 轨迹按步骤轮流用这些颜色，相邻步骤颜色不同；不含红色，红色留给错误。 */
    private static final int[] STEP_COLORS = {0xFF1565C0, 0xFFE65100, 0xFF2E7D32, 0xFF6A1B9A, 0xFF00838F,
            0xFF6D4C41};
    private static final Pattern STEP = Pattern.compile("\\(step (\\d+)\\)");

    private static final long REFRESH_DELAY_MS = 150;
    /** 改了窗口的触摸标志后等它生效再派发手势。 */
    private static final long TOUCH_UPDATE_MS = 80;
    /** 隐藏后等新的一帧画出来再截图。 */
    private static final long HIDE_SETTLE_MS = 120;
    /** 隐藏后自动恢复的时间，防止截图的客户端中途断开后悬浮窗一直不显示。 */
    private static final long AUTO_SHOW_MS = 5_000;
    private static final int TRACE_LINES = 60;
    private static final int TRACE_LINE_CHARS = 200;

    /** 进程内记住拖动后的位置，服务重连后还在原处。 */
    private static int pillX = -1;
    private static int pillY = -1;
    private static int panelY = -1;

    private final AccessibilityService service;
    private final RunMonitor monitor;
    private final Runnable cancel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private final AtomicBoolean refreshPosted = new AtomicBoolean();
    private final Runnable refreshTask = () -> {
        refreshPosted.set(false);
        refresh();
    };
    private final Runnable onChange = () -> {
        if (refreshPosted.compareAndSet(false, true)) {
            main.postDelayed(refreshTask, REFRESH_DELAY_MS);
        }
    };
    private final Runnable autoShow = () -> setVisibleOnMain(true);

    private WindowManager.LayoutParams params;
    private FrameLayout root;
    private TextView pill;
    private LinearLayout panel;
    private TextView title;
    private TextView cancelButton;
    private TextView statusLine;
    private TextView message;
    private ScrollView traceScroll;
    private TextView trace;
    private boolean expanded;
    /** 窗口在屏幕上的位置 {left, top, right, bottom}，布局后更新，引擎线程读。 */
    private volatile int[] rect;

    public StatusOverlay(AccessibilityService service, RunMonitor monitor, Runnable cancel) {
        this.service = service;
        this.monitor = monitor;
        this.cancel = cancel;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 显示悬浮窗。失败（例如服务正在断开）只记日志。 */
    public void attach() {
        if (root != null || windowManager == null) {
            return;
        }
        build();
        params = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int[] size = DisplayInfo.realSize(service);
        if (pillY < 0) {
            pillX = 0;
            pillY = Math.round(size[1] * 0.62f);
        }
        params.x = pillX;
        params.y = pillY;
        try {
            windowManager.addView(root, params);
        } catch (RuntimeException e) {
            ApexLog.w("status overlay not shown: " + e);
            root = null;
            return;
        }
        monitor.addListener(onChange);
        refresh();
    }

    public void detach() {
        monitor.removeListener(onChange);
        main.removeCallbacks(refreshTask);
        main.removeCallbacks(autoShow);
        if (root != null) {
            try {
                windowManager.removeView(root);
            } catch (RuntimeException e) {
                ApexLog.d("status overlay remove failed: " + e);
            }
        }
        root = null;
        rect = null;
    }

    /**
     * 手势的范围（起点到终点的矩形）碰到悬浮窗时，让它暂时不接收触摸，返回恢复用的回调；碰不到时返回 null。
     * 在引擎线程上、派发手势前调用，会阻塞到改动生效。
     */
    public Runnable stepAside(int x1, int y1, int x2, int y2) {
        int[] r = rect;
        int pad = dp(8);
        if (r == null || Math.max(x1, x2) < r[0] - pad || Math.min(x1, x2) > r[2] + pad
                || Math.max(y1, y2) < r[1] - pad || Math.min(y1, y2) > r[3] + pad) {
            return null;
        }
        if (!runOnMain(() -> setTouchable(false))) {
            return null;
        }
        SystemClock.sleep(TOUCH_UPDATE_MS);
        return () -> main.post(() -> setTouchable(true));
    }

    /** 暂时隐藏（截图前）或恢复。隐藏会在 {@link #AUTO_SHOW_MS} 后自动恢复。 */
    public void setVisible(boolean visible) {
        if (runOnMain(() -> setVisibleOnMain(visible)) && !visible) {
            SystemClock.sleep(HIDE_SETTLE_MS);
        }
    }

    // ---- 主线程 ----

    private void setVisibleOnMain(boolean visible) {
        main.removeCallbacks(autoShow);
        if (root == null) {
            return;
        }
        root.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (!visible) {
            main.postDelayed(autoShow, AUTO_SHOW_MS);
        }
    }

    private void setTouchable(boolean touchable) {
        if (root == null) {
            return;
        }
        if (touchable) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        updateLayout();
    }

    private void updateLayout() {
        try {
            windowManager.updateViewLayout(root, params);
        } catch (RuntimeException e) {
            ApexLog.d("status overlay update failed: " + e);
        }
        // 只挪位置时不一定重新布局，等窗口移动生效后再量一次
        root.postDelayed(this::updateRect, 50);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void build() {
        root = new FrameLayout(service) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return A11Y_CLASS;
            }
        };
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::updateRect);

        pill = label(12, TEXT);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setMaxWidth(dp(180));
        pill.setPadding(dp(10), dp(6), dp(12), dp(6));
        pill.setBackground(rounded(dp(16)));
        pill.setOnTouchListener(new DragListener(this::toggle));
        // FrameLayout 默认让子视图撑满，文字变长时窗口不会跟着变宽，要显式写 WRAP_CONTENT
        root.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        panel = new LinearLayout(service) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return A11Y_CLASS;
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10));
        panel.setBackground(rounded(dp(12)));
        int[] size = DisplayInfo.realSize(service);
        panel.setLayoutParams(new FrameLayout.LayoutParams(Math.min(size[0] - dp(24), dp(420)),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(service) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return A11Y_CLASS;
            }
        };
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = label(13, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(6), dp(8), dp(6));
        title.setOnTouchListener(new DragListener(this::toggle));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cancelButton = button("Cancel");
        cancelButton.setOnClickListener(v -> cancel.run());
        header.addView(cancelButton);
        TextView collapse = button("—");
        collapse.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        header.addView(collapse, lp);
        panel.addView(header);

        statusLine = label(12, TEXT);
        panel.addView(statusLine);
        message = label(12, RED);
        message.setMaxLines(4);
        message.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(message);

        traceScroll = new ScrollView(service) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return A11Y_CLASS;
            }
        };
        trace = label(10, TEXT);
        trace.setTypeface(Typeface.MONOSPACE);
        traceScroll.addView(trace);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(Math.round(size[1] * 0.3f), dp(280)));
        scrollLp.topMargin = dp(6);
        panel.addView(traceScroll, scrollLp);
        panel.setVisibility(View.GONE);
        root.addView(panel);
    }

    private void toggle() {
        if (root == null) {
            return;
        }
        expanded = !expanded;
        if (expanded) {
            pillX = params.x;
            pillY = params.y;
            if (panelY < 0) {
                panelY = dp(72);
            }
            params.x = dp(12);
            params.y = panelY;
        } else {
            panelY = params.y;
            params.x = pillX;
            params.y = pillY;
        }
        pill.setVisibility(expanded ? View.GONE : View.VISIBLE);
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        updateLayout();
        refresh();
    }

    @SuppressLint("SetTextI18n")
    private void refresh() {
        if (root == null) {
            return;
        }
        RunMonitor.View view = monitor.view();
        RunResult result = view.getResult();
        int color = color(view);
        String pillText;
        if (view.getFlowId() == null) {
            pillText = "Apex";
        } else if (view.isRunning()) {
            pillText = (view.getStateId() != null ? view.getStateId() : view.getFlowId()) + " · " + view.getStep();
        } else {
            pillText = result != null ? result.getStatus().name() : "Apex";
        }
        pill.setText(dot(color).append(pillText));
        if (!expanded) {
            return;
        }
        title.setText(dot(color).append(view.getFlowId() != null ? view.getFlowId() : "Apex: no run yet"));
        cancelButton.setVisibility(view.isRunning() ? View.VISIBLE : View.GONE);
        if (view.getFlowId() == null) {
            statusLine.setText("Runs started from code or from the devtools bridge show up here.");
            message.setVisibility(View.GONE);
            trace.setText("");
            return;
        }
        String state = view.isRunning() ? view.getStateId()
                : result != null && result.getEndStateId() != null ? result.getEndStateId() : view.getStateId();
        statusLine.setText((view.isRunning() ? "RUNNING" : result != null ? result.getStatus().name() : "")
                + (state != null ? " · " + state : "") + " · step " + view.getStep()
                + " · " + TraceEvent.formatSeconds(view.getElapsedMs()));
        statusLine.setTextColor(stepColor(view.getStep()));
        boolean failed = result != null && !result.isSuccess() && result.getMessage() != null;
        message.setVisibility(failed ? View.VISIBLE : View.GONE);
        message.setText(failed ? result.getMessage() : "");
        trace.setText(traceText(result != null ? result.getTrace() : view.getRecent(), view.getStep()));
        traceScroll.post(() -> traceScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 最后 {@link #TRACE_LINES} 条轨迹（不含 SCREEN_DUMP），按所属的步骤上色：从「进入状态（step N）」开始到下一次进入状态
     * 之前的行都属于第 N 步，相邻步骤颜色不同。进入状态的行加粗；WARN 加粗并标 ⚠，ERROR 用红色。
     *
     * @param lastStep 没有一条「进入状态」在显示范围内时，这些行属于哪一步
     */
    private static CharSequence traceText(List<TraceEvent> events, int lastStep) {
        List<TraceEvent> shown = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && shown.size() < TRACE_LINES; i--) {
            if (events.get(i).getType() != TraceEvent.Type.SCREEN_DUMP) {
                shown.add(0, events.get(i));
            }
        }
        // 显示范围开头那几行属于第一条「进入状态」的前一步
        int step = lastStep;
        for (TraceEvent e : shown) {
            int entered = enteredStep(e);
            if (entered >= 0) {
                step = entered - 1;
                break;
            }
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (TraceEvent e : shown) {
            int entered = enteredStep(e);
            if (entered >= 0) {
                step = entered;
            } else if (e.getType() == TraceEvent.Type.RUN_START) {
                step = 0;
            }
            String line = e.toString();
            if (line.length() > TRACE_LINE_CHARS) {
                line = line.substring(0, TRACE_LINE_CHARS) + "…";
            }
            boolean warn = e.getPriority() == ApexLogger.WARN;
            if (warn) {
                line = "⚠ " + line;
            }
            int start = sb.length();
            sb.append(line);
            int color = e.getPriority() >= ApexLogger.ERROR ? RED : stepColor(step);
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (warn || entered >= 0 || e.getPriority() >= ApexLogger.ERROR) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append('\n');
        }
        return sb;
    }

    /** 「进入状态」事件的步数，其他事件返回 -1。 */
    private static int enteredStep(TraceEvent e) {
        if (e.getType() != TraceEvent.Type.STATE_ENTER) {
            return -1;
        }
        Matcher m = STEP.matcher(e.getMessage());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 第 0 步（开始前）用默认文字色，之后按步数轮流取色，相邻步骤颜色不同。 */
    private static int stepColor(int step) {
        return step <= 0 ? TEXT : STEP_COLORS[(step - 1) % STEP_COLORS.length];
    }

    private static int color(RunMonitor.View view) {
        if (view.isRunning()) {
            return BLUE;
        }
        RunResult result = view.getResult();
        if (result == null) {
            return GREY;
        }
        switch (result.getStatus()) {
            case SUCCESS:
                return GREEN;
            case CANCELLED:
                return GREY;
            default:
                return RED;
        }
    }

    private static SpannableStringBuilder dot(int color) {
        SpannableStringBuilder sb = new SpannableStringBuilder("● ");
        sb.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void updateRect() {
        if (root == null) {
            return;
        }
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        rect = new int[]{loc[0], loc[1], loc[0] + root.getWidth(), loc[1] + root.getHeight()};
    }

    private boolean runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            return latch.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(service) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return A11Y_CLASS;
            }
        };
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(dp(3), 0, 0, HALO);
        return t;
    }

    @SuppressLint("SetTextI18n")
    private TextView button(String text) {
        TextView b = label(12, TEXT);
        b.setText(text);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(0x00000000);
        bg.setStroke(dp(1), BORDER);
        b.setBackground(bg);
        return b;
    }

    private GradientDrawable rounded(int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(BACKING);
        bg.setStroke(dp(1), BORDER);
        return bg;
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    /** 拖动移动窗口，没拖动就当作点击。 */
    private final class DragListener implements View.OnTouchListener {
        private final Runnable onTap;
        private final int slop = ViewConfiguration.get(service).getScaledTouchSlop();
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;

        DragListener(Runnable onTap) {
            this.onTap = onTap;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                    }
                    if (dragging) {
                        params.x = Math.max(0, startX + Math.round(dx));
                        params.y = Math.max(0, startY + Math.round(dy));
                        updateLayout();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        onTap.run();
                    } else if (expanded) {
                        panelY = params.y;
                    } else {
                        pillX = params.x;
                        pillY = params.y;
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
