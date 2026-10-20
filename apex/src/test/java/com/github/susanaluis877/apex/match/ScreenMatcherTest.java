package com.github.susanaluis877.apex.match;

import static com.github.susanaluis877.apex.match.Match.activity;
import static com.github.susanaluis877.apex.match.Match.allOf;
import static com.github.susanaluis877.apex.match.Match.always;
import static com.github.susanaluis877.apex.match.Match.anyOf;
import static com.github.susanaluis877.apex.match.Match.custom;
import static com.github.susanaluis877.apex.match.Match.has;
import static com.github.susanaluis877.apex.match.Match.not;
import static com.github.susanaluis877.apex.match.Match.pkg;
import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.screen;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ScreenMatcherTest {

    private final ScreenSnapshot screen = screen("com.foo", "com.foo.ui.HomeActivity",
            frame(text("首页"), button("tab_mine", "我的")));

    @After
    public void tearDown() {
        Registry.get().clear();
    }

    @Test
    public void packageAndActivity() {
        assertTrue(pkg("com.foo").matches(screen));
        assertFalse(pkg("com.bar").matches(screen));
        assertTrue(activity("com.foo.ui.HomeActivity").matches(screen));
        assertTrue(activity(".ui.HomeActivity").matches(screen));
        assertTrue(activity("HomeActivity").matches(screen));
        assertFalse(activity("Home").matches(screen));
    }

    @Test
    public void composition() {
        assertTrue(has(By.text("首页"), By.id("tab_mine")).matches(screen));
        assertFalse(has(By.text("首页"), By.id("tab_cart")).matches(screen));
        assertTrue(anyOf(has(By.id("tab_cart")), pkg("com.foo")).matches(screen));
        assertTrue(not(has(By.text("设置"))).matches(screen));
        assertTrue(always().matches(screen));
    }

    @Test
    public void allOfIsNormalized() {
        ScreenMatcher a = allOf(pkg("com.foo"), has(By.text("x"), By.text("y")));
        ScreenMatcher b = allOf(pkg("com.foo"), allOf(has(By.text("x")), has(By.text("y"))));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        ScreenMatcher single = pkg("com.foo");
        assertSame(single, allOf(single));
    }

    @Test
    public void customMatcherUsesRegistry() {
        Registry.get().registerMatcher("textCount", (s, args) ->
                s.topTexts(100).size() == Integer.parseInt(args.get("n")));
        assertTrue(custom("textCount", Collections.singletonMap("n", "2")).matches(screen));
        assertFalse(custom("textCount", Collections.singletonMap("n", "3")).matches(screen));

        Set<String> names = new HashSet<>();
        allOf(pkg("com.foo"), not(custom("a")), anyOf(custom("b"), always())).collectCustomNames(names);
        assertEquals(new HashSet<>(java.util.Arrays.asList("a", "b")), names);
    }

    @Test
    public void inlineConditionSeesWholeScreen() {
        ScreenMatcher twoButtons = custom(s -> By.clickable(true).findAll(s).size() == 1
                && "com.foo".equals(s.getPackageName()));
        assertTrue(twoButtons.matches(screen));
        assertFalse(custom(s -> By.text("设置").findFirst(s) != null).matches(screen));
    }

    @Test
    public void inlineConditionComposesAndNeedsNoRegistration() {
        ScreenCondition isHome = s -> s.getActivity() != null && s.getActivity().endsWith("HomeActivity");
        assertTrue(allOf(pkg("com.foo"), custom(isHome)).matches(screen));
        assertFalse(not(custom(isHome)).matches(screen));
        assertTrue(anyOf(custom(s -> false), has(By.text("首页"))).matches(screen));

        Set<String> names = new HashSet<>();
        allOf(custom(isHome), custom(s -> true)).collectCustomNames(names);
        assertTrue(names.isEmpty());
    }

    @Test
    public void inlineConditionsAreEqualOnlyForSameFunction() {
        ScreenCondition c = s -> true;
        assertEquals(custom(c), custom(c));
        assertEquals(custom(c).hashCode(), custom(c).hashCode());
        assertFalse(custom(c).equals(custom(s -> true)));
        assertEquals("custom(<inline>)", custom(c).toString());
    }

    @Test
    public void explainsEveryMatcherKind() {
        assertNull(allOf(pkg("com.foo"), has(By.text("首页"))).explainMismatch(screen));
        assertEquals("package=\"com.bar\": screen package is \"com.foo\"", pkg("com.bar").explainMismatch(screen));
        assertEquals("activity=\"LoginActivity\": current activity is \"com.foo.ui.HomeActivity\"",
                activity("LoginActivity").explainMismatch(screen));
        assertEquals("has{text=\"设置\"}: no node matches (text=\"设置\": 0 node(s))",
                has(By.text("设置")).explainMismatch(screen));
        assertEquals("not(has{text=\"首页\"}): the inner condition matched",
                not(has(By.text("首页"))).explainMismatch(screen));
        assertEquals("allOf 报告第一个不满足的条件", "package=\"com.bar\": screen package is \"com.foo\"",
                allOf(has(By.text("首页")), pkg("com.bar"), pkg("com.baz")).explainMismatch(screen));
        assertEquals("none of anyOf: package=\"com.bar\": screen package is \"com.foo\""
                        + " | custom(<inline>) returned false",
                anyOf(pkg("com.bar"), custom(s -> false)).explainMismatch(screen));
        assertNull(always().explainMismatch(screen));
    }

    @Test(expected = IllegalStateException.class)
    public void unregisteredCustomMatcherFailsLoudly() {
        custom("missing").matches(screen);
    }
}
