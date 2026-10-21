package com.github.susanaluis877.apex.flow;

import static com.github.susanaluis877.apex.action.Actions.back;
import static com.github.susanaluis877.apex.action.Actions.click;
import static com.github.susanaluis877.apex.match.Match.allOf;
import static com.github.susanaluis877.apex.match.Match.has;
import static com.github.susanaluis877.apex.match.Match.pkg;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.action.Coord;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Match;

import org.json.JSONObject;
import org.junit.Test;

public class FlowWriterTest {

    @Test
    public void demoFlowRoundTripsAndOmitsDefaults() throws Exception {
        Flow flow = DslJsonEquivalenceTest.demoClaimDsl();
        String json = FlowWriter.toJson(flow);
        assertEquals(flow, FlowParser.parse(json));
        JSONObject o = new JSONObject(json);
        JSONObject home = o.getJSONArray("states").getJSONObject(0);
        // pkg + has 合并成一个对象，更像手写的 JSON
        assertEquals("com.github.susanaluis877.apex.demo", home.getJSONObject("match").getString("package"));
        assertTrue(home.getJSONObject("match").has("has"));
        assertFalse("和 defaults 相同的超时不写", home.has("timeoutMs"));
        assertEquals(15_000, o.getJSONArray("states").getJSONObject(1).getLong("timeoutMs"));
        assertEquals(3, o.getJSONArray("interceptors").getJSONObject(0).getInt("maxTimes"));
    }

    @Test
    public void nonCanonicalOrderFallsBackToAllOfArray() throws Exception {
        Flow flow = new Flow.Builder("f").start("a")
                .state("a", s -> s.match(allOf(has(By.text("x")), pkg("p"), has(By.text("y")))).endSuccess())
                .build();
        JSONObject match = new JSONObject(FlowWriter.toJson(flow)).getJSONArray("states").getJSONObject(0)
                .getJSONObject("match");
        assertTrue(match.toString(), match.has("allOf"));
    }

    @Test
    public void inlineCodeIsReportedWithItsLocation() {
        Flow inlineAction = new Flow.Builder("f").start("a")
                .state("a", s -> s.match(Match.always()).action(back(), Actions.run(ctx -> true)).endSuccess())
                .build();
        assertRejected(inlineAction, "state 'a' action 2: run(ctx -> ...) cannot be written as JSON");
        Flow inlineMatch = new Flow.Builder("f").start("a")
                .state("a", s -> s.match(Match.custom(screen -> true)).endSuccess())
                .build();
        assertRejected(inlineMatch, "state 'a' match: inline custom(screen -> ...) cannot be written as JSON");
        Flow mixedSwipe = new Flow.Builder("f").start("a")
                .state("a", s -> s.match(Match.always())
                        .action(Actions.swipe(Coord.px(1, 2), Coord.ratio(0.5, 0.5), 300)).endSuccess())
                .build();
        assertRejected(mixedSwipe, "state 'a' action 1: swipe mixes pixel and ratio coordinates");
    }

    @Test
    public void flowLevelSettingsRoundTrip() throws Exception {
        Flow flow = new Flow.Builder("f").start("a").timeoutMs(9_000).maxSteps(7).maxInterceptions(3)
                .defaults(d -> d.timeoutMs(4_000).actionGapMs(50).interceptorCooldownMs(0))
                .useGlobalInterceptors(false).excludeGlobalInterceptors("ad")
                .state("a", s -> s.match(Match.always()).action(click(By.text("go").visible(false))).next("b")
                        .timeoutMs(4_000).maxRetries(0))
                .state("b", s -> s.match(has(By.id("x").hasDescendant(By.text("1"), By.text("2"))
                        .inside(By.className("List")))).endFailure())
                .interceptor("i", i -> i.match(Match.not(has(By.text("z")))).action(back()).maxTimes(9))
                .build();
        assertEquals(flow, FlowParser.parse(FlowWriter.toJson(flow)));
    }

    private static void assertRejected(Flow flow, String messagePrefix) {
        try {
            FlowWriter.toJson(flow);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(messagePrefix));
        }
    }
}
