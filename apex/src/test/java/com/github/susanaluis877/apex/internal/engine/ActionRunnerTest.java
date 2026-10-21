package com.github.susanaluis877.apex.internal.engine;

import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.PKG;
import static com.github.susanaluis877.apex.testutil.Trees.screen;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.node.Range;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ActionRunnerTest {

    private final FakeDevice device = new FakeDevice();

    @After
    public void tearDown() {
        Registry.get().clear();
    }

    @Test
    public void runOnceClicksTargetAndReportsWhatItDid() {
        ScreenSnapshot s = screen(frame(button("go", "Go")));
        device.show(s);
        ActionResult r = ActionRunner.runOnce(Actions.click(By.id("go")), s, device, device, device, device, null);
        assertTrue(r.toString(), r.isOk());
        assertTrue(r.getMessage(), r.getMessage().startsWith("ACTION_CLICK on "));
        assertEquals(Collections.singletonList("click:go"), device.log);
    }

    @Test
    public void runOnceReportsMissingTargetAsSoftFailure() {
        ScreenSnapshot s = screen(frame(button("go", "Go")));
        device.show(s);
        ActionResult r = ActionRunner.runOnce(Actions.click(By.id("nope")), s, device, device, device, device, null);
        assertEquals(ActionResult.Status.SOFT_FAIL, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().contains("target not found"));
    }

    @Test
    public void directionalScrollFallsBackToForwardThenSwipe() {
        ScreenSnapshot s = screen(frame(list("list", 0, 300, 1080, 2100)));
        device.show(s);
        device.scrollResult(Action.Direction.DOWN, ActionResult.soft("performAction returned false"));
        ActionResult r = run(Actions.scroll(By.id("list"), Action.Direction.DOWN), s);
        assertTrue(r.toString(), r.isOk());
        assertTrue(r.getMessage(), r.getMessage().startsWith("ACTION_SCROLL_FORWARD on "));
        assertEquals(Arrays.asList("scroll:list:DOWN", "scroll:list:FORWARD"), device.log);

        device.log.clear();
        device.scrollResult(Action.Direction.FORWARD, ActionResult.soft("performAction returned false"));
        r = run(Actions.scroll(By.id("list"), Action.Direction.DOWN), s);
        assertTrue(r.toString(), r.isOk());
        // 手指从容器 3/4 高度滑到 1/4 高度，看下面的内容
        assertEquals("swipe:540,1650->540,750", device.log.get(2));
    }

    @Test
    public void scrollToScrollsUntilTargetAppears() {
        int[] hour = {11};
        device.show(() -> screen(frame(picker(hour[0] + "小时选择器"))));
        device.onScroll(() -> hour[0]--);
        Action action = Actions.scrollTo(By.textMatches("7小时选择器").visible(false),
                By.textMatches("\\d+小时选择器").visible(false), Action.Direction.BACKWARD, 24);
        ActionResult r = run(action, device.snapshot());
        assertTrue(r.toString(), r.isOk());
        assertTrue(r.getMessage(), r.getMessage().endsWith("after 4 scroll(s)"));
        assertEquals(7, hour[0]);
        assertEquals(4, device.log.size());
    }

    @Test
    public void scrollToListsTheValuesItSawWhenItFails() {
        int[] hour = {11};
        device.show(() -> screen(frame(picker(String.format("%02d小时选择器", hour[0])))));
        device.onScroll(() -> hour[0] = (hour[0] + 23) % 24);
        ActionResult r = run(Actions.scrollTo(By.textMatches("7小时选择器").visible(false),
                By.textMatches("\\d+小时选择器").visible(false), Action.Direction.BACKWARD, 5), device.snapshot());
        assertEquals(ActionResult.Status.SOFT_FAIL, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().endsWith("container showed 11小时选择器, 10小时选择器, 09小时选择器, "
                + "08小时选择器, 07小时选择器, 06小时选择器"));
    }

    @Test
    public void scrollToStopsAtTheEndOrAfterMaxSwipes() {
        device.show(screen(frame(list("list", 0, 300, 1080, 2100))));
        ActionResult end = run(Actions.scrollTo(By.text("missing")), device.snapshot());
        assertEquals(ActionResult.Status.SOFT_FAIL, end.getStatus());
        assertTrue(end.getMessage(), end.getMessage().startsWith("reached the end of"));
        assertEquals(1, device.log.size());

        int[] page = {0};
        device.show(() -> screen(frame(list("list", 0, 300, 1080, 2100).child(text("row " + page[0])))));
        device.onScroll(() -> page[0]++);
        ActionResult max = run(Actions.scrollTo(By.text("missing"), null, Action.Direction.FORWARD, 3),
                device.snapshot());
        assertTrue(max.getMessage(), max.getMessage().startsWith("target not found after 3 scroll(s)"));
    }

    @Test
    public void scrollToDetectsTheEndEvenWhileListBounces() {
        int[] offset = {0};
        // 滚到头时回弹：每次快照位置都不同，但内容不变
        device.show(() -> screen(frame(list("list", 0, 300 + offset[0]++, 1080, 2100).child(text("last row")))));
        ActionResult r = run(Actions.scrollTo(By.text("missing")), device.snapshot());
        assertTrue(r.getMessage(), r.getMessage().startsWith("reached the end of"));
        assertEquals(1, device.log.size());
    }

    @Test
    public void scrollStopsWhenNodeSaysItIsAtTheEnd() {
        ScreenSnapshot s = screen(frame(list("list", 0, 300, 1080, 2100).canScrollBackward(true)));
        device.show(s);
        device.scrollResult(Action.Direction.FORWARD, ActionResult.soft("performAction returned false"));
        ActionResult r = run(Actions.scroll(By.id("list"), Action.Direction.FORWARD), s);
        assertTrue(r.getMessage(), r.getMessage().startsWith("already at the end of"));
        assertEquals("不再滑动", 1, device.log.size());

        device.log.clear();
        ActionResult to = run(Actions.scrollTo(By.text("missing")), s);
        assertTrue(to.getMessage(), to.getMessage().startsWith("reached the end of"));
        assertTrue("到头了就不再滚", device.log.isEmpty());
    }

    @Test
    public void scrollDoesNotFlipTheOuterPagerWhenTheListFitsOnScreen() {
        // ColorOS 便签：「笔记 / 待办」是 ViewPager2，里面的笔记列表一屏放得下时不可滚动
        ScreenSnapshot s = screen(frame(notesPager(false)));
        device.show(s);
        ActionResult to = run(Actions.scrollTo(By.text("missing"), By.id("note_list"), Action.Direction.FORWARD, 10), s);
        assertEquals(ActionResult.Status.SOFT_FAIL, to.getStatus());
        assertTrue(to.getMessage(), to.getMessage().contains("is not scrollable now"));
        ActionResult scroll = run(Actions.scroll(By.text("row"), Action.Direction.FORWARD), s);
        assertEquals(ActionResult.Status.SOFT_FAIL, scroll.getStatus());
        assertTrue("没有滚动任何东西", device.log.isEmpty());
    }

    @Test
    public void defaultScrollContainerSkipsPagers() {
        ScreenSnapshot s = screen(frame(notesPager(true)));
        device.show(s);
        run(Actions.scroll(Action.Direction.FORWARD), s);
        assertEquals(Collections.singletonList("scroll:note_list:FORWARD"), device.log);

        // 列表不可滚动时，屏幕上能滚的只有翻页容器：不滚它
        ScreenSnapshot pagerOnly = screen(frame(notesPager(false)));
        device.show(pagerOnly);
        ActionResult r = run(Actions.scrollTo(By.text("missing")), pagerOnly);
        assertEquals(ActionResult.Status.SOFT_FAIL, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().contains("which flips between pages"));
        assertEquals(1, device.log.size());
        run(Actions.scroll(By.id("view_pager"), Action.Direction.FORWARD), pagerOnly);
        assertEquals("写成 container 时照常滚", "scroll:view_pager:FORWARD", device.log.get(1));
    }

    @Test
    public void clickTapsTheTargetWhenOnlyAContainerAboveItIsClickable() {
        // 列表项本身不可点击，点击由 RecyclerView 的触摸监听处理；对 RecyclerView ACTION_CLICK 没反应
        ScreenSnapshot s = screen(frame(notesPager(false)));
        device.show(s);
        ActionResult r = run(Actions.click(By.text("row")), s);
        assertTrue(r.toString(), r.isOk());
        assertEquals(Collections.singletonList("tap:498,930"), device.log);
        assertTrue(r.getMessage(), r.getMessage().contains("it is a container"));

        // 日历月视图：日期格是虚拟节点，可点击的是整个月视图
        UiNode.Builder cell = new UiNode.Builder().className("android.view.View").desc("10月28日")
                .bounds(616, 1386, 770, 1628);
        ScreenSnapshot month = screen(frame(new UiNode.Builder().className("com.coloros.calendar.MonthView")
                .viewId(PKG + ":id/month").bounds(0, 660, 1080, 2112).clickable(true).child(cell)));
        device.show(month);
        assertTrue(run(Actions.click(By.desc("10月28日")), month).isOk());
        assertEquals("tap:693,1507", device.log.get(1));

        // 普通的一行：可点击的是这一行，照常 ACTION_CLICK
        ScreenSnapshot row = screen(frame(new UiNode.Builder().className("android.widget.LinearLayout")
                .viewId(PKG + ":id/row").bounds(0, 400, 1080, 600).clickable(true)
                .child(text("显示与亮度").bounds(48, 450, 400, 520))));
        device.show(row);
        assertTrue(run(Actions.click(By.text("显示与亮度")), row).isOk());
        assertEquals("click:row", device.log.get(2));
    }

    /** ViewPager2 → 横向的 RecyclerView → 一页 → 笔记列表 note_list → 一行 note_item。 */
    private static UiNode.Builder notesPager(boolean listScrollable) {
        UiNode.Builder item = new UiNode.Builder().className("android.widget.FrameLayout").packageName(PKG)
                .viewId(PKG + ":id/note_item").bounds(48, 882, 1032, 1087)
                .child(text("row").bounds(96, 900, 900, 960));
        UiNode.Builder notes = list("note_list", 0, 107, 1080, 2280).scrollable(listScrollable).clickable(true)
                .child(item);
        UiNode.Builder inner = new UiNode.Builder().className("androidx.recyclerview.widget.RecyclerView")
                .packageName(PKG).bounds(0, 0, 1080, 2280).scrollable(true)
                .child(new UiNode.Builder().className("android.widget.FrameLayout").packageName(PKG)
                        .bounds(0, 0, 1080, 2280).child(notes));
        return new UiNode.Builder().className("androidx.viewpager2.widget.ViewPager2").packageName(PKG)
                .viewId(PKG + ":id/view_pager").bounds(0, 0, 1080, 2280).scrollable(true).child(inner);
    }

    @Test
    public void coordinateFallbackRefusesTargetsOutsideTheScreen() {
        // 页面滑入动画中，节点还在屏幕右侧
        ScreenSnapshot s = screen(frame(new UiNode.Builder().className("android.view.View").text("Go")
                .bounds(1176, 1382, 2064, 1490)));
        device.show(s);
        ActionResult r = run(Actions.click(By.text("Go")), s);
        assertEquals(ActionResult.Status.SOFT_FAIL, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().startsWith("target is outside the screen"));
        assertTrue(device.log.isEmpty());
    }

    @Test
    public void setProgressUsesNodeActionOrTapsAtRatio() {
        UiNode.Builder slider = new UiNode.Builder().className("android.widget.SeekBar")
                .viewId(PKG + ":id/brightness").bounds(100, 1000, 1100, 1100)
                .range(new Range(Range.Type.INT, 0, 8187, 4481)).canSetProgress(true);
        ScreenSnapshot s = screen(frame(new UiNode.Builder().className("android.widget.LinearLayout")
                .desc("亮度").clickable(true).child(slider)));
        device.show(s);
        ActionResult r = run(Actions.setProgressRatio(By.desc("亮度"), 1.0), s);
        assertTrue(r.toString(), r.isOk());
        assertEquals("setProgress:brightness=8187.0", device.log.get(0));
        run(Actions.setProgress(By.id("brightness"), 99999), s);
        assertEquals("超出范围时取边界", "setProgress:brightness=8187.0", device.log.get(1));

        UiNode.Builder plain = new UiNode.Builder().className("android.widget.SeekBar").viewId(PKG + ":id/vol")
                .bounds(0, 500, 1000, 600).range(new Range(Range.Type.INT, 0, 100, 10));
        ScreenSnapshot s2 = screen(frame(plain));
        device.show(s2);
        r = run(Actions.setProgressRatio(By.id("vol"), 0.5), s2);
        assertTrue(r.toString(), r.isOk());
        assertEquals("tap:500,550", device.log.get(2));

        r = run(Actions.setProgress(By.id("vol"), 10), s2);
        assertTrue(r.getMessage(), r.getMessage().startsWith("already at 10"));
        assertEquals("已经到位时不操作", 3, device.log.size());

        r = run(Actions.setProgress(By.text("Go"), 3), screen(frame(button("go", "Go"))));
        assertEquals(ActionResult.Status.HARD_FAIL, r.getStatus());
    }

    private ActionResult run(Action action, ScreenSnapshot s) {
        return ActionRunner.runOnce(action, s, device, device, device, device, null);
    }

    private static UiNode.Builder list(String id, int l, int t, int r, int b) {
        return new UiNode.Builder().className("androidx.recyclerview.widget.RecyclerView").packageName(PKG)
                .viewId(PKG + ":id/" + id).bounds(l, t, r, b).scrollable(true);
    }

    private static UiNode.Builder picker(String text) {
        return new UiNode.Builder().text(text).bounds(24, 427, 540, 832).scrollable(true).clickable(true)
                .visible(false);
    }

    @Test
    public void runOnceCollectsNestedOperationsOfCustomActions() {
        Registry.get().registerAction("twice", (ctx, args) -> ctx.perform(Actions.back()) && ctx.perform(Actions.back()));
        ScreenSnapshot s = screen(frame(button("go", "Go")));
        device.show(s);
        List<TraceEvent> trace = new ArrayList<>();
        ActionResult r = ActionRunner.runOnce(Actions.custom("twice"), s, device, device, device, device, trace);
        assertTrue(r.toString(), r.isOk());
        assertEquals(2, trace.size());
        assertEquals(java.util.Arrays.asList("back", "back"), device.log);
    }
}
