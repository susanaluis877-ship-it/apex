package com.github.susanaluis877.apex.flow;

import static com.github.susanaluis877.apex.action.Actions.back;
import static com.github.susanaluis877.apex.action.Actions.click;
import static com.github.susanaluis877.apex.action.Actions.custom;
import static com.github.susanaluis877.apex.action.Actions.tapRatio;
import static com.github.susanaluis877.apex.match.Match.always;
import static com.github.susanaluis877.apex.match.Match.has;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.flow.FlowValidationException.Problem;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Match;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FlowBuilderTest {

    @After
    public void tearDown() {
        Registry.get().clear();
    }

    private static Flow.Builder valid() {
        return new Flow.Builder("f")
                .start("a")
                .defaults(d -> d.timeoutMs(5_000).maxRetries(1))
                .state("a", s -> s.match(always()).action(click(By.id("go"))).next("b", "c"))
                .state("b", s -> s.match(has(By.text("done"))).endSuccess())
                .state("c", s -> s.match(has(By.text("sold out"))).action(back()).endFailure().timeoutMs(1_000))
                .interceptor("ad", i -> i.match(has(By.id("ad_close"))).action(click(By.id("ad_close"))));
    }

    @Test
    public void buildsWithDefaultsApplied() {
        Flow flow = valid().build();
        assertEquals("a", flow.getStartState().getId());
        assertEquals(3, flow.getStates().size());
        assertEquals(5_000, flow.getState("a").getTimeoutMs());
        assertEquals(1, flow.getState("a").getMaxRetries());
        assertEquals(FlowDefaults.DEFAULT_RETRY_INTERVAL_MS, flow.getState("a").getRetryIntervalMs());
        assertEquals(1_000, flow.getState("c").getTimeoutMs());
        assertTrue(flow.getState("b").isTerminal());
        assertFalse(flow.getState("a").isTerminal());
        assertEquals(FlowDefaults.DEFAULT_INTERCEPTOR_MAX_TIMES, flow.getInterceptors().get(0).getMaxTimes());
        assertEquals(Flow.DEFAULT_MAX_STEPS, flow.getMaxSteps());
    }

    @Test
    public void flowsAreValues() {
        assertEquals(valid().build(), valid().build());
        assertEquals(valid().build().hashCode(), valid().build().hashCode());
        assertNotEquals(valid().build(), valid().maxSteps(3).build());
        assertNotEquals(valid().build(), valid().useGlobalInterceptors(false).build());
        assertNotEquals(valid().build(), valid().excludeGlobalInterceptors("x").build());
    }

    @Test
    public void effectiveInterceptorsMergeGlobalsAfterOwn() {
        Interceptor perm = Interceptor.create("perm", i -> i.match(always()).action(back()));
        Interceptor globalAd = Interceptor.create("ad", i -> i.match(always()).action(back()));
        Interceptor upgrade = Interceptor.create("upgrade", i -> i.match(always()).action(back()));
        Interceptor notice = Interceptor.create("notice", i -> i.match(always()).action(back()));
        List<Interceptor> globals = java.util.Arrays.asList(perm, globalAd, upgrade, notice);

        Flow flow = valid().excludeGlobalInterceptors("upgrade").build();
        List<Interceptor> effective = flow.effectiveInterceptors(globals);
        assertEquals("流程自己的在前、同 id 用流程的、排除的跳过",
                java.util.Arrays.asList(flow.getInterceptors().get(0), perm, notice), effective);
        assertEquals(flow.getInterceptors(), valid().useGlobalInterceptors(false).build().effectiveInterceptors(globals));
    }

    @Test
    public void standaloneInterceptorIsValidatedAndUsesSdkDefaults() {
        Interceptor ok = Interceptor.create("x", i -> i.match(always()).action(back()));
        assertEquals(FlowDefaults.DEFAULT_INTERCEPTOR_MAX_TIMES, ok.getMaxTimes());
        assertEquals(FlowDefaults.DEFAULT_INTERCEPTOR_COOLDOWN_MS, ok.getCooldownMs());
        try {
            Interceptor.create("bad", i -> i.match(Match.custom("nope")).maxTimes(0));
            fail();
        } catch (FlowValidationException e) {
            List<String> messages = new ArrayList<>();
            for (Problem p : e.getProblems()) {
                messages.add(p.toString());
            }
            assertTrue(messages.toString(), messages.contains("interceptor 'bad'.match: custom matcher 'nope' is not registered"));
            assertTrue(messages.contains("interceptor 'bad'.actions: interceptor needs at least one action"));
            assertTrue(messages.contains("interceptor 'bad'.maxTimes: must be > 0"));
        }
    }

    @Test
    public void actionsAreValues() {
        Action a = click(By.id("x")).optional().delayMs(200);
        assertEquals(click(By.id("x")).optional().delayMs(200), a);
        assertNotEquals(click(By.id("x")), a);
        assertEquals(tapRatio(0.5, 0.9), tapRatio(0.5, 0.9));
        assertEquals("click{id=\"x\"}?+200ms", a.toString());

        com.github.susanaluis877.apex.action.ActionFunction fn = ctx -> true;
        assertEquals(com.github.susanaluis877.apex.action.Actions.run(fn),
                com.github.susanaluis877.apex.action.Actions.run(fn));
        assertNotEquals(com.github.susanaluis877.apex.action.Actions.run(fn),
                com.github.susanaluis877.apex.action.Actions.run(ctx -> true));
        assertEquals("run(<inline>)?", com.github.susanaluis877.apex.action.Actions.run(fn).optional().toString());
    }

    @Test
    public void reportsAllProblems() {
        Flow.Builder b = new Flow.Builder("f")
                .start("a")
                .state("a", s -> s.match(always()).next("a", "missing", "b"))
                .state("b", s -> s.next("a"))
                .state("b", s -> s.match(always()).endSuccess().next("a"))
                .state("orphan", s -> s.match(always()).endSuccess().maxRetries(-1))
                .interceptor("ad", i -> i.match(always()));
        List<String> messages = problems(b);
        assertTrue(messages.toString(), messages.contains("state 'a'.next[0]: state cannot list itself in next "
                + "(retries already cover staying on a page)"));
        assertTrue(messages.contains("state 'a'.next[1]: unknown state 'missing'"));
        assertTrue(messages.contains("state 'b'.match: match is required"));
        assertTrue(messages.contains("state 'b'.id: duplicate state id"));
        assertTrue(messages.contains("state 'b'.next: terminal state cannot have next"));
        assertTrue(messages.contains("state 'orphan'.maxRetries: must be >= 0"));
        assertTrue(messages.contains("state 'orphan': unreachable from start state 'a'"));
        assertTrue(messages.contains("interceptor 'ad'.actions: interceptor needs at least one action"));
    }

    @Test
    public void startAndTerminalAreRequired() {
        List<String> messages = problems(new Flow.Builder("f")
                .state("a", s -> s.match(always()).next("b"))
                .state("b", s -> s.match(always()).next("a")));
        assertTrue(messages.contains("flow.start: start state is required"));
        assertTrue(messages.contains("flow.states: flow needs at least one terminal state (end)"));

        messages = problems(valid().start("nope"));
        assertTrue(messages.contains("flow.start: unknown state 'nope'"));
    }

    @Test
    public void nonTerminalNeedsNext() {
        List<String> messages = problems(new Flow.Builder("f").start("a")
                .state("a", s -> s.match(always()))
                .state("b", s -> s.match(always()).endSuccess()));
        assertTrue(messages.contains(
                "state 'a'.next: non-terminal state needs at least one next state (or set end)"));
    }

    @Test
    public void customNamesMustBeRegistered() {
        Flow.Builder b = new Flow.Builder("f").start("a")
                .state("a", s -> s.match(Match.custom("isHome")).action(custom("doIt")).endSuccess());
        List<String> messages = problems(b);
        assertTrue(messages.contains("state 'a'.match: custom matcher 'isHome' is not registered"));
        assertTrue(messages.contains("state 'a'.actions[0]: custom action 'doIt' is not registered"));

        Registry.get().registerMatcher("isHome", (screen, args) -> true);
        Registry.get().registerAction("doIt", (ctx, args) -> true);
        b.build();
    }

    @Test
    public void problemsCarryOrdinalsForJsonPaths() {
        try {
            new Flow.Builder("f").start("a")
                    .state("a", s -> s.match(always()).endSuccess())
                    .state("b", s -> s.match(always()).next("zzz"))
                    .build();
            fail();
        } catch (FlowValidationException e) {
            Problem p = e.getProblems().get(0);
            assertEquals(Problem.STATE, p.getScope());
            assertEquals(1, p.getOrdinal());
            assertEquals("next", p.getField());
            assertEquals(0, p.getIndex());
        }
    }

    private static List<String> problems(Flow.Builder b) {
        try {
            b.build();
        } catch (FlowValidationException e) {
            List<String> list = new ArrayList<>();
            for (Problem p : e.getProblems()) {
                list.add(p.toString());
            }
            return list;
        }
        throw new AssertionError("expected FlowValidationException");
    }
}
