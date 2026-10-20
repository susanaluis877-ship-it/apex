package com.github.susanaluis877.apex.internal.engine;

import static com.github.susanaluis877.apex.action.Actions.back;
import static com.github.susanaluis877.apex.action.Actions.click;
import static com.github.susanaluis877.apex.action.Actions.custom;
import static com.github.susanaluis877.apex.action.Actions.launchApp;
import static com.github.susanaluis877.apex.action.Actions.setText;
import static com.github.susanaluis877.apex.action.Actions.sleep;
import static com.github.susanaluis877.apex.match.Match.always;
import static com.github.susanaluis877.apex.match.Match.has;
import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.screen;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.susanaluis877.apex.FlowListener;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.RunResult.Status;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FlowEngineTest {

    private final FakeDevice device = new FakeDevice();
    private final App app = new App();
    private final List<String> events = new ArrayList<>();
    private final FlowListener listener = new FlowListener() {
        @Override
        public void onStateEntered(String stateId, int step) {
            events.add("enter:" + stateId + ":" + step);
        }

        @Override
        public void onInterceptor(String interceptorId, int count) {
            events.add("interceptor:" + interceptorId + ":" + count);
        }

        @Override
        public void onFinished(RunResult result) {
            throw new AssertionError("engine must not call onFinished");
        }
    };

    @After
    public void tearDown() {
        Registry.get().clear();
    }

    /** 被自动化的假 App：首页 → 任务页（可能有广告弹窗）→ 完成页，或首页 → 售罄页。 */
    private final class App {
        String page = "home";
        boolean soldOut;
        boolean ad;
        boolean adSticky;
        boolean claimEnabled = true;
        boolean claimClickable = true;
        int claimClicksToIgnore;
        int loadingTicks = -1;

        App() {
            device.show(this::render);
            device.onClick("start", () -> update(() -> page = soldOut ? "sold" : "task"));
            device.onClick("ad_close", () -> update(() -> ad = adSticky));
            device.onClick("claim", () -> {
                if (claimClicksToIgnore > 0) {
                    claimClicksToIgnore--;
                } else if (loadingTicks >= 0) {
                    startLoading();
                } else {
                    update(() -> page = "done");
                }
            });
        }

        void update(Runnable change) {
            change.run();
            device.show(this::render);
        }

        /** 点击后显示一个不停变化的“加载中”，8 秒后完成。 */
        void startLoading() {
            for (int i = 1; i <= 16; i++) {
                int tick = i;
                device.after(500L * i, () -> update(() -> loadingTicks = tick));
            }
            device.after(8_000, () -> update(() -> page = "done"));
        }

        ScreenSnapshot render() {
            switch (page) {
                case "home":
                    return screen(frame(text("Home"), button("start", "Start")));
                case "task": {
                    UiNode.Builder claim = button("claim", "Claim").enabled(claimEnabled).clickable(claimClickable);
                    UiNode.Builder content = loadingTicks > 0
                            ? frame(text("Task"), claim, text("Loading " + loadingTicks))
                            : frame(text("Task"), claim);
                    return ad ? screen(frame(text("AD"), button("ad_close", "Close")), content) : screen(content);
                }
                case "done":
                    return screen(frame(text("Reward claimed!")));
                case "sold":
                    return screen(frame(text("Sold out")));
                default:
                    return screen(frame(text("Somewhere else")));
            }
        }
    }

    private static Flow.Builder demo(int taskMaxRetries) {
        return new Flow.Builder("demo").start("home")
                .defaults(d -> d.timeoutMs(10_000).retryIntervalMs(3_000).maxRetries(2))
                .state("home", s -> s.match(has(By.id("start"))).action(click(By.id("start")))
                        .next("task", "sold_out"))
                .state("task", s -> s.match(has(By.id("claim").enabled(true))).action(click(By.id("claim")))
                        .next("done").maxRetries(taskMaxRetries))
                .state("done", s -> s.match(has(By.textContains("Reward claimed"))).endSuccess())
                .state("sold_out", s -> s.match(has(By.text("Sold out"))).action(back()).endFailure())
                .interceptor("close_ad", i -> i.match(has(By.text("AD"), By.id("ad_close")))
                        .action(click(By.id("ad_close"))).maxTimes(3));
    }

    private RunResult run(Flow flow) {
        return new FlowEngine(flow, device, device, device, device, listener, EngineConfig.DEFAULT).run();
    }

    // ---- 基本路径 ----

    @Test
    public void linearSuccess() {
        RunResult r = run(demo(2).build());
        assertEquals(r.toString(), Status.SUCCESS, r.getStatus());
        assertEquals(Arrays.asList("home", "task", "done"), r.getPath());
        assertEquals("done", r.getEndStateId());
        assertEquals(Arrays.asList("click:start", "click:claim"), device.log);
        assertEquals(Arrays.asList("enter:home:1", "enter:task:2", "enter:done:3"), events);
    }

    @Test
    public void branchToFailureTerminal() {
        app.soldOut = true;
        RunResult r = run(demo(2).build());
        assertEquals(Status.FAILURE, r.getStatus());
        assertEquals("sold_out", r.getEndStateId());
        assertEquals(Arrays.asList("click:start", "back"), device.log);
    }

    // ---- 拦截器 ----

    @Test
    public void interceptorFiresWhileWaitingThenFlowResumes() {
        app.ad = true;
        app.claimEnabled = false;
        device.at(4_000, () -> app.update(() -> app.claimEnabled = true));
        RunResult r = run(demo(2).build());
        assertEquals(r.toString(), Status.SUCCESS, r.getStatus());
        assertEquals(Arrays.asList("click:start", "click:ad_close", "click:claim"), device.log);
        assertEquals(Collections.singletonMap("close_ad", 1), r.getInterceptorCounts());
        assertEquals(Arrays.asList("enter:home:1", "interceptor:close_ad:1", "enter:task:2", "enter:done:3"), events);
    }

    @Test
    public void interceptorWinsOverMatchingCandidate() {
        app.ad = true;
        RunResult r = run(demo(2).build());
        assertEquals(Status.SUCCESS, r.getStatus());
        assertEquals("广告挡住时不能先点下面的按钮",
                Arrays.asList("click:start", "click:ad_close", "click:claim"), device.log);
    }

    @Test
    public void interceptorRespectsCooldownAndMaxTimes() {
        app.ad = true;
        app.adSticky = true;
        RunResult r = run(demo(2).build());
        assertEquals(r.toString(), Status.SUCCESS, r.getStatus());
        assertEquals(Collections.singletonMap("close_ad", 3), r.getInterceptorCounts());
        List<Long> closeTimes = new ArrayList<>();
        for (int i = 0; i < device.log.size(); i++) {
            if (device.log.get(i).equals("click:ad_close")) {
                closeTimes.add(device.logTimes.get(i));
            }
        }
        assertEquals(3, closeTimes.size());
        for (int i = 1; i < closeTimes.size(); i++) {
            assertTrue("cooldown violated: " + closeTimes, closeTimes.get(i) - closeTimes.get(i - 1) >= 1_500);
        }
        assertEquals("click:claim", device.log.get(device.log.size() - 1));
    }

    // ---- 重试与续跑 ----

    @Test
    public void lostClickIsRetriedAfterInterval() {
        app.claimClicksToIgnore = 1;
        RunResult r = run(demo(2).build());
        assertEquals(r.toString(), Status.SUCCESS, r.getStatus());
        assertEquals(Arrays.asList("click:start", "click:claim", "click:claim"), device.log);
        long gap = device.logTimes.get(2) - device.logTimes.get(1);
        assertTrue("retried too early: " + gap, gap >= 3_000);
        assertEquals("重试不算进入新状态", Arrays.asList("home", "task", "done"), r.getPath());
    }

    @Test
    public void noRetryWhileScreenKeepsChanging() {
        app.loadingTicks = 0;
        RunResult r = run(demo(2).build());
        assertEquals(r.toString(), Status.SUCCESS, r.getStatus());
        assertEquals(Arrays.asList("click:start", "click:claim"), device.log);
    }

    @Test
    public void retriesAreBoundedThenTimeout() {
        app.claimClicksToIgnore = 100;
        RunResult r = run(demo(2).build());
        assertEquals(Status.TIMEOUT, r.getStatus());
        assertEquals(Arrays.asList("click:start", "click:claim", "click:claim", "click:claim"), device.log);
        assertEquals("task", r.getEndStateId());
        assertTrue(r.getMessage(), r.getMessage().contains("retries=2"));
        assertTrue(r.getMessage(), r.getMessage().contains("texts=[Task, Claim]"));
    }

    @Test
    public void maxRetriesZeroNeverRepeats() {
        app.claimClicksToIgnore = 100;
        RunResult r = run(demo(0).build());
        assertEquals(Status.TIMEOUT, r.getStatus());
        assertEquals(Arrays.asList("click:start", "click:claim"), device.log);
    }

    @Test
    public void resumesFromFailedActionIndex() {
        device.show(screen(frame(button("a", "A"))));
        device.after(1_000, () -> device.show(screen(frame(button("a", "A"), button("b", "B")))));
        device.onClick("b", () -> device.show(screen(frame(text("finished")))));
        Flow flow = new Flow.Builder("resume").start("form")
                .state("form", s -> s.match(has(By.id("a"))).action(click(By.id("a")), click(By.id("b")))
                        .next("end"))
                .state("end", s -> s.match(has(By.text("finished"))).endSuccess())
                .build();
        RunResult r = run(flow);
        assertEquals(r.toString(), Status.SUCCESS, r.getStatus());
        assertEquals(Arrays.asList("click:a", "click:b"), device.log);
    }

    // ---- 超时、步数、取消、服务断开 ----

    @Test
    public void startStateTimeout() {
        app.page = "elsewhere";
        RunResult r = run(demo(2).build());
        assertEquals(Status.TIMEOUT, r.getStatus());
        assertNull(r.getEndStateId());
        assertTrue(r.getMessage(), r.getMessage().startsWith("start state 'home' did not appear within 10000ms"));
        assertTrue(r.getDurationMs() >= 10_000 && r.getDurationMs() < 11_000);
    }

    @Test
    public void flowTimeout() {
        app.page = "elsewhere";
        RunResult r = run(demo(2).timeoutMs(3_000).build());
        assertEquals(Status.TIMEOUT, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().startsWith("flow timed out after 3000ms"));
    }

    @Test
    public void maxStepsStopsCycles() {
        device.show(screen(frame(button("x", "A"))));
        device.onClick("x", () -> {
            ScreenSnapshot now = device.snapshot();
            boolean onA = By.text("A").findFirst(now) != null;
            device.show(screen(frame(button("x", onA ? "B" : "A"))));
        });
        Flow flow = new Flow.Builder("cycle").start("a").maxSteps(5)
                .state("a", s -> s.match(has(By.text("A"))).action(click(By.id("x"))).next("b", "end"))
                .state("b", s -> s.match(has(By.text("B"))).action(click(By.id("x"))).next("a"))
                .state("end", s -> s.match(has(By.text("END"))).endSuccess())
                .build();
        RunResult r = run(flow);
        assertEquals(Status.MAX_STEPS, r.getStatus());
        assertEquals(Arrays.asList("a", "b", "a", "b", "a"), r.getPath());
    }

    @Test
    public void cancelWhileWaiting() {
        app.page = "elsewhere";
        device.at(3_000, device::cancel);
        RunResult r = run(demo(2).build());
        assertEquals(Status.CANCELLED, r.getStatus());
        assertTrue(r.getDurationMs() <= 2_500);
    }

    @Test
    public void cancelInterruptsSleepAction() {
        device.show(screen(frame(text("anything"))));
        device.at(2_000, device::cancel);
        Flow flow = new Flow.Builder("sleepy").start("a")
                .state("a", s -> s.match(always()).action(sleep(60_000)).next("b"))
                .state("b", s -> s.match(has(By.text("never"))).endSuccess())
                .build();
        RunResult r = run(flow);
        assertEquals(Status.CANCELLED, r.getStatus());
        assertTrue("took " + r.getDurationMs(), r.getDurationMs() <= 1_000);
    }

    @Test
    public void serviceLossEndsRunAfterGrace() {
        device.onClick("start", () -> device.serviceDown(true));
        RunResult r = run(demo(2).build());
        assertEquals(Status.SERVICE_UNAVAILABLE, r.getStatus());
        assertEquals(Collections.singletonList("home"), r.getPath());
    }

    // ---- 动作语义 ----

    @Test
    public void hardFailEndsRunUnlessOptional() {
        device.launchResult(ActionResult.hard("no launch intent for com.bar"));
        Flow.Builder base = new Flow.Builder("launch").start("a")
                .state("b", s -> s.match(has(By.id("start"))).endSuccess());
        RunResult r = run(base.state("a", s -> s.match(always()).action(launchApp("com.bar")).next("b")).build());
        assertEquals(Status.ACTION_FAILED, r.getStatus());
        assertEquals("no launch intent for com.bar", r.getMessage());

        Flow optional = new Flow.Builder("launch").start("a")
                .state("a", s -> s.match(always()).action(launchApp("com.bar").optional()).next("b"))
                .state("b", s -> s.match(has(By.id("start"))).endSuccess())
                .build();
        assertEquals(Status.SUCCESS, run(optional).getStatus());
    }

    @Test
    public void setTextOnNonEditableFails() {
        Flow flow = new Flow.Builder("text").start("a")
                .state("a", s -> s.match(has(By.id("start"))).action(setText(By.id("start"), "hi")).next("b"))
                .state("b", s -> s.match(has(By.text("never"))).endSuccess())
                .build();
        RunResult r = run(flow);
        assertEquals(Status.ACTION_FAILED, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().startsWith("target is not editable"));
    }

    @Test
    public void clickFallsBackToGestureWhenNodeClickFails() {
        device.clickResult("claim", ActionResult.soft("performAction returned false"));
        RunResult r = run(demo(2).build());
        assertEquals(r.toString(), Status.SUCCESS, r.getStatus());
        assertEquals(Arrays.asList("click:start", "click:claim", "tap:200,230"), device.log);
    }

    @Test
    public void clickUsesGestureWhenNothingIsClickable() {
        app.claimClickable = false;
        RunResult r = run(demo(2).build());
        assertEquals(Status.SUCCESS, r.getStatus());
        assertEquals(Arrays.asList("click:start", "tap:200,230"), device.log);
    }

    @Test
    public void customActionComposesBuiltIns() {
        Registry.get().registerAction("claimTwice", (ctx, args) ->
                ctx.perform(click(By.id(args.get("id")))) && ctx.screen() != null);
        Flow flow = new Flow.Builder("custom").start("home")
                .state("home", s -> s.match(has(By.id("start")))
                        .action(custom("claimTwice", Collections.singletonMap("id", "start"))).next("task"))
                .state("task", s -> s.match(has(By.id("claim"))).endSuccess())
                .build();
        RunResult r = run(flow);
        assertEquals(Status.SUCCESS, r.getStatus());
        assertEquals(Collections.singletonList("click:start"), device.log);
    }

    @Test
    public void customActionExceptionEndsWithError() {
        Registry.get().registerAction("boom", (ctx, args) -> {
            throw new IllegalStateException("kaboom");
        });
        Flow flow = new Flow.Builder("custom").start("home")
                .state("home", s -> s.match(has(By.id("start"))).action(custom("boom").optional()).next("task"))
                .state("task", s -> s.match(has(By.id("claim"))).endSuccess())
                .build();
        RunResult r = run(flow);
        assertEquals(Status.ERROR, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().contains("kaboom"));
    }

    @Test
    public void customMatcherExceptionEndsWithError() {
        Registry.get().registerMatcher("bad", (screen, args) -> {
            throw new IllegalStateException("matcher bug");
        });
        Flow flow = new Flow.Builder("custom").start("home")
                .state("home", s -> s.match(com.github.susanaluis877.apex.match.Match.custom("bad")).endSuccess())
                .build();
        RunResult r = run(flow);
        assertEquals(Status.ERROR, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().contains("matcher bug"));
    }
}
