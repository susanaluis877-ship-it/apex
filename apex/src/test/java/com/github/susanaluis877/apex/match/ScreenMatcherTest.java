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

    @Test(expected = IllegalStateException.class)
    public void unregisteredCustomMatcherFailsLoudly() {
        custom("missing").matches(screen);
    }
}
