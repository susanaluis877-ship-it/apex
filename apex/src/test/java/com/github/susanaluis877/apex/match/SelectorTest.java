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

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import org.junit.Test;

import java.util.regex.PatternSyntaxException;

public class SelectorTest {

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

    @Test(expected = PatternSyntaxException.class)
    public void badRegexFailsFast() {
        By.textMatches("(");
    }
}
