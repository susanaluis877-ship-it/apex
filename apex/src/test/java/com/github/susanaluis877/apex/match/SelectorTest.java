package com.github.susanaluis877.apex.match;

import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.screen;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.susanaluis877.apex.node.Range;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import org.junit.Test;

import java.util.regex.PatternSyntaxException;

public class SelectorTest {

    @Test
    public void progressAndStateDescription() {
        UiNode.Builder max = new UiNode.Builder().className("android.widget.SeekBar").viewId("p:id/slider")
                .range(new Range(Range.Type.INT, 0, 8187, 8187)).stateDescription("100%").bounds(0, 0, 10, 10);
        UiNode.Builder half = new UiNode.Builder().className("android.widget.SeekBar").viewId("p:id/volume")
                .range(new Range(Range.Type.INT, 0, 100, 50)).bounds(0, 20, 10, 30);
        ScreenSnapshot s = screen(frame(max, half, text("plain")));
        assertEquals("slider", shortId(By.id("slider").progressAtLeast(1).findFirst(s)));
        assertNull(By.id("volume").progressAtLeast(0.6).findFirst(s));
        assertEquals("volume", shortId(By.progressAtMost(0.5).findFirst(s)));
        assertEquals("slider", shortId(By.stateDesc("100%").findFirst(s)));
        assertEquals("slider", shortId(By.stateDescContains("100").findFirst(s)));
        assertNull("没有范围的节点不匹配", By.text("plain").progressAtMost(1).findFirst(s));
        String why = By.id("volume").progressAtLeast(0.9).explainMismatch(s);
        assertTrue(why, why.contains("all but progressAtLeast=0.9") && why.contains("progress=0.5"));
        assertEquals(By.progressAtLeast(1).stateDesc("x"), By.stateDesc("x").progressAtLeast(1));
        try {
            By.progressAtLeast(1.5);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 比例必须在 0~1
        }
    }

    @Test
    public void relationalConditionsKeepTwoTextsInTheSameItem() {
        // 两个闹钟：07:30 叫「起床」，08:00 叫「Apex」。分开写两个 has 会误判“07:30 的 Apex 已存在”
        ScreenSnapshot s = screen(frame(
                alarm("a1", "07:30", "法定工作日 | 起床", false),
                alarm("a2", "08:00", "法定工作日 | Apex", true)));
        Selector wanted = By.id("alarm_item").hasDescendant(By.text("07:30"), By.textContains("Apex"));
        assertNull(wanted.findFirst(s));
        assertEquals("08:00 那一行", "a2", tag(By.id("alarm_item").hasDescendant(By.text("08:00"),
                By.textContains("Apex")).findFirst(s)));
        UiNode toggle = By.id("switch").inside(By.id("alarm_item").hasDescendant(By.text("08:00"))).findFirst(s);
        assertTrue(toggle.isChecked());
        assertNull(By.id("switch").inside(By.text("nothing")).findFirst(s));
        String why = wanted.explainMismatch(s);
        assertTrue(why, why.contains("match all but hasDescendant{"));
        assertEquals(By.id("x").hasDescendant(By.text("a")).hasDescendant(By.text("b")),
                By.id("x").hasDescendant(By.text("a"), By.text("b")));
    }

    private static UiNode.Builder alarm(String tag, String time, String label, boolean on) {
        return new UiNode.Builder().className("android.widget.LinearLayout").viewId("p:id/alarm_item").desc(tag)
                .clickable(true).bounds(0, 0, 1080, 200)
                .child(new UiNode.Builder().className("android.widget.TextView").text(time).bounds(0, 0, 400, 100))
                .child(new UiNode.Builder().className("android.widget.TextView").text(label).bounds(0, 100, 400, 200))
                .child(new UiNode.Builder().className("android.widget.Switch").viewId("p:id/switch").checkable(true)
                        .checked(on).bounds(900, 50, 1000, 150));
    }

    private static String tag(UiNode n) {
        return n == null ? null : n.getDesc();
    }

    private static String shortId(UiNode n) {
        return n == null ? null : n.getViewId().substring(n.getViewId().indexOf(":id/") + 4);
    }

    @Test
    public void textVariants() {
        UiNode node = text("领取奖励").build();
        assertTrue(By.text("领取奖励").matches(node));
        assertFalse(By.text("领取").matches(node));
        assertTrue(By.textContains("奖励").matches(node));
        assertTrue(By.textMatches("领取.*").matches(node));
        assertFalse("textMatches 要求整段匹配", By.textMatches("奖励").matches(node));
    }

    @Test
    public void descVariants() {
        UiNode node = text(null).desc("关闭广告").build();
        assertTrue(By.desc("关闭广告").matches(node));
        assertTrue(By.descContains("广告").matches(node));
        assertTrue(By.descMatches(".*广告").matches(node));
        assertFalse(By.text("关闭广告").matches(node));
    }

    @Test
    public void shortAndFullId() {
        UiNode node = button("submit", "OK").build();
        assertTrue(By.id("submit").matches(node));
        assertTrue(By.id("com.foo:id/submit").matches(node));
        assertFalse(By.id("com.bar:id/submit").matches(node));
        assertFalse("短名不能只匹配后缀的一部分", By.id("mit").matches(node));
    }

    @Test
    public void simpleAndFullClassName() {
        UiNode node = button("submit", "OK").build();
        assertTrue(By.className("Button").matches(node));
        assertTrue(By.className("android.widget.Button").matches(node));
        assertFalse(By.className("TextView").matches(node));
    }

    @Test
    public void booleanFilters() {
        UiNode node = button("submit", "OK").enabled(false).checked(true).build();
        assertTrue(By.id("submit").clickable(true).enabled(false).checked(true).matches(node));
        assertFalse(By.id("submit").enabled(true).matches(node));
        assertFalse(By.id("submit").selected(true).matches(node));
        assertFalse(By.id("submit").scrollable(true).matches(node));
    }

    @Test
    public void invisibleNodesAreSkippedByDefault() {
        UiNode node = button("submit", "OK").visible(false).build();
        assertFalse(By.id("submit").matches(node));
        assertTrue(By.id("submit").visible(false).matches(node));
    }

    @Test
    public void findFirstSearchesTopWindowFirstThenPreOrder() {
        ScreenSnapshot screen = screen(
                frame(text("OK").viewId("com.foo:id/dialog_ok")),
                frame(frame(text("OK").viewId("com.foo:id/deep")), text("OK").viewId("com.foo:id/shallow")));
        assertEquals("com.foo:id/dialog_ok", By.text("OK").findFirst(screen).getViewId());
        assertEquals(3, By.text("OK").findAll(screen).size());
        assertEquals("com.foo:id/deep", By.text("OK").findAll(screen).get(1).getViewId());
        assertNull(By.text("Cancel").findFirst(screen));
    }

    @Test
    public void parentLinks() {
        ScreenSnapshot screen = screen(frame(frame(button("submit", "OK"))));
        UiNode node = By.id("submit").findFirst(screen);
        assertSame(screen.getWindows().get(0).getRoot(), node.getParent().getParent());
    }

    @Test
    public void selectorsAreImmutableValues() {
        Selector base = By.id("submit");
        Selector enabled = base.enabled(true);
        assertNotEquals(base, enabled);
        assertEquals(By.id("submit").enabled(true), enabled);
        assertEquals(By.id("submit").enabled(true).hashCode(), enabled.hashCode());
        assertEquals(By.textMatches("a+"), By.textMatches("a+"));
    }

    @Test
    public void explainsNearMiss() {
        ScreenSnapshot screen = screen(frame(text("Task"), button("claim", "Loading").enabled(false)));
        assertNull(By.id("claim").explainMismatch(screen));
        assertEquals("1 node(s) match all but enabled=true, e.g. Button id=com.foo:id/claim text=\"Loading\""
                        + " [100,200][300,260] (enabled=false)",
                By.id("claim").enabled(true).explainMismatch(screen));
        assertEquals("1 node(s) match all but text=\"Claim\", e.g. Button id=com.foo:id/claim text=\"Loading\""
                        + " [100,200][300,260] (text=\"Loading\")",
                By.id("claim").text("Claim").explainMismatch(screen));
    }

    @Test
    public void explainsInvisibleTarget() {
        ScreenSnapshot screen = screen(frame(button("ok", "OK").visible(false)));
        String why = By.text("OK").explainMismatch(screen);
        assertTrue(why, why.startsWith("1 node(s) match all but visible=true"));
        assertTrue(why, why.endsWith("(visible=false)"));
    }

    @Test
    public void explainsMissingNodeWithIdentityCounts() {
        ScreenSnapshot screen = screen(frame(text("Home"), button("start", "Start")));
        assertEquals("no node matches (text=\"Sold out\": 0 node(s))", By.text("Sold out").explainMismatch(screen));
        // 两个文本类条件分别命中，但没有节点同时满足，而且差的不止一个条件
        assertEquals("no node matches (text=\"Home\": 1 node(s), id=\"start\": 1 node(s))",
                By.text("Home").id("start").enabled(false).explainMismatch(screen));
    }

    @Test(expected = PatternSyntaxException.class)
    public void badRegexFailsFast() {
        By.textMatches("(");
    }
}
