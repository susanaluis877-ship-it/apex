package com.github.susanaluis877.apex.internal.devtools;

import static com.github.susanaluis877.apex.action.Actions.back;
import static com.github.susanaluis877.apex.action.Actions.click;
import static com.github.susanaluis877.apex.match.Match.has;
import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.screen;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.Interceptor;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Match;
import com.github.susanaluis877.apex.match.ScreenMatcher;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FlowCheckTest {

    private static final ScreenSnapshot HOME = screen(frame(button("start", "Start"), text("Home")));
    private static final ScreenSnapshot TASK = screen(frame(button("claim", "Claim"), text("Task")));
    private static final ScreenSnapshot DONE = screen(frame(text("Reward claimed")));
    private static final ScreenSnapshot SOLD = screen(frame(text("Sold out"), text("Task")));
    private static final ScreenSnapshot AD = screen(frame(text("AD"), button("ad_close", "x")), frame(text("Task")));

    @After
    public void tearDown() {
        Registry.get().clear();
    }

    @Test
    public void goodFlowWithItsSamplesHasNoWarnings() throws Exception {
        JSONObject r = FlowCheck.check(flow(has(By.id("claim"))), Collections.emptyList(), samples(), false);
        assertEquals(r.toString(2), 0, r.getJSONArray("warnings").length());
        JSONObject task = r.getJSONArray("states").getJSONObject(1);
        assertEquals("task", task.getString("id"));
        assertEquals(Arrays.asList("task"), list(task.getJSONArray("matches")));
        assertEquals(Arrays.asList("close_ad"), list(r.getJSONArray("interceptors").getJSONObject(0)
                .getJSONArray("matches")));
        assertEquals("close_ad", r.getJSONArray("samples").getJSONObject(0).getString("label"));
    }

    @Test
    public void reportsTheWrongCandidateWinning() throws Exception {
        // task 只认 "Task" 文本，售罄页也有，所以 home 之后售罄页会被当成 task
        JSONObject r = FlowCheck.check(flow(has(By.text("Task"))), Collections.emptyList(), samples(), false);
        List<String> kinds = kinds(r);
        assertTrue(kinds.toString(), kinds.contains("wrong-winner"));
        assertTrue(r.toString(), message(r, "wrong-winner").contains("sample 'sold_out' of 'sold_out' would enter 'task'"));
    }

    @Test
    public void reportsAmbiguousUnlabelledSamples() throws Exception {
        Map<String, ScreenSnapshot> samples = new LinkedHashMap<>();
        samples.put("page1", SOLD);
        JSONObject r = FlowCheck.check(flow(has(By.text("Task"))), Collections.emptyList(), samples, false);
        assertTrue(r.toString(), message(r, "ambiguous").contains("matches [task, sold_out]; 'task' wins"));
    }

    @Test
    public void reportsSampleNotMatchedByItsOwnState() throws Exception {
        Map<String, ScreenSnapshot> samples = new LinkedHashMap<>();
        samples.put("task.1", HOME);
        JSONObject r = FlowCheck.check(flow(has(By.id("claim"))), Collections.emptyList(), samples, false);
        String m = message(r, "expected-mismatch");
        assertTrue(m, m.startsWith("sample 'task.1' belongs to 'task' but 'task' does not match it: has{id=\"claim\"}"));
    }

    @Test
    public void reportsGlobalInterceptorFiringOnNormalPages() throws Exception {
        List<Interceptor> globals = new ArrayList<>();
        globals.add(Interceptor.create("perm", i -> i.match(has(By.text("Task"))).action(back())));
        JSONObject r = FlowCheck.check(flow(has(By.id("claim"))), globals, samples(), false);
        assertTrue(r.toString(), r.getJSONArray("warnings").toString()
                .contains("interceptor 'perm' matches 'task' (a sample of state 'task')"));
        assertTrue(r.getJSONArray("interceptors").getJSONObject(1).getBoolean("global"));

        Flow excluded = new Flow.Builder("f").start("home")
                .state("home", s -> s.match(has(By.id("start"))).endSuccess())
                .excludeGlobalInterceptors("perm").build();
        JSONObject r2 = FlowCheck.check(excluded, globals, samples(), false);
        assertEquals(0, r2.getJSONArray("interceptors").length());
    }

    @Test
    public void customMatcherErrorsStayInTheirCell() throws Exception {
        Registry.get().registerMatcher("boom", (screen, args) -> {
            throw new IllegalStateException("bad");
        });
        Flow flow = new Flow.Builder("f").start("home")
                .state("home", s -> s.match(has(By.id("start"))).action(click(By.id("start"))).next("x"))
                .state("x", s -> s.match(Match.custom("boom")).endSuccess())
                .build();
        Map<String, ScreenSnapshot> samples = new LinkedHashMap<>();
        samples.put("home", HOME);
        samples.put("x", TASK);
        JSONObject r = FlowCheck.check(flow, Collections.emptyList(), samples, false);
        JSONObject x = r.getJSONArray("states").getJSONObject(1);
        assertTrue(x.getJSONObject("errors").getString("x").contains("bad"));
        assertEquals(Arrays.asList("home"), list(r.getJSONArray("states").getJSONObject(0).getJSONArray("matches")));
        assertTrue(message(r, "expected-mismatch").contains("error: java.lang.IllegalStateException: bad"));
    }

    @Test
    public void explainAllGivesEveryMismatchReason() throws Exception {
        Map<String, ScreenSnapshot> samples = new LinkedHashMap<>();
        samples.put("live", DONE);
        JSONObject r = FlowCheck.check(flow(has(By.id("claim"))), Collections.emptyList(), samples, true);
        JSONArray states = r.getJSONArray("states");
        assertTrue(states.getJSONObject(0).getJSONObject("mismatch").getString("live").startsWith("has{id=\"start\"}"));
        assertEquals(Arrays.asList("live"), list(states.getJSONObject(2).getJSONArray("matches")));
        assertFalse(states.getJSONObject(2).has("mismatch"));
        assertEquals("success", states.getJSONObject(2).getString("end"));
    }

    @Test
    public void flagsTooBroadStatesAndUnmatchedSamples() throws Exception {
        Flow flow = new Flow.Builder("f").start("home")
                .state("home", s -> s.match(has(By.className("FrameLayout"))).action(back()).next("done"))
                .state("done", s -> s.match(has(By.text("nothing like this"))).endSuccess())
                .build();
        JSONObject r = FlowCheck.check(flow, Collections.emptyList(), samples(), false);
        assertTrue(kinds(r).contains("too-broad"));

        Flow launch = new Flow.Builder("f").start("launch")
                .state("launch", s -> s.match(Match.always()).action(back()).next("done"))
                .state("done", s -> s.match(has(By.text("nothing like this"))).endSuccess())
                .build();
        assertFalse("always 是有意的，不算太宽",
                kinds(FlowCheck.check(launch, Collections.emptyList(), samples(), false)).contains("too-broad"));

        JSONObject r2 = FlowCheck.check(flow(has(By.id("claim"))), Collections.emptyList(),
                Collections.singletonMap("mystery", screen(frame(text("??")))), false);
        assertTrue(kinds(r2).contains("unmatched"));
    }

    @Test
    public void labelPrefersLongestId() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(Arrays.asList("task", "task.done", "a"));
        assertEquals("task", FlowCheck.labelOf("task#2", ids));
        assertEquals("task.done", FlowCheck.labelOf("task.done.1", ids));
        assertEquals(null, FlowCheck.labelOf("tasks", ids));
    }

    private static Flow flow(ScreenMatcher taskMatcher) {
        return new Flow.Builder("demo").start("home")
                .state("home", s -> s.match(has(By.id("start"))).action(click(By.id("start"))).next("task", "sold_out"))
                .state("task", s -> s.match(taskMatcher).action(click(By.id("claim"))).next("done"))
                .state("done", s -> s.match(has(By.textContains("Reward claimed"))).endSuccess())
                .state("sold_out", s -> s.match(has(By.text("Sold out"))).action(back()).endFailure())
                .interceptor("close_ad", i -> i.match(Match.allOf(has(By.text("AD")), has(By.id("ad_close"))))
                        .action(click(By.id("ad_close"))))
                .build();
    }

    private static Map<String, ScreenSnapshot> samples() {
        Map<String, ScreenSnapshot> m = new LinkedHashMap<>();
        m.put("close_ad", AD);
        m.put("done", DONE);
        m.put("home", HOME);
        m.put("sold_out", SOLD);
        m.put("task", TASK);
        return m;
    }

    private static List<String> kinds(JSONObject r) throws Exception {
        List<String> kinds = new ArrayList<>();
        JSONArray w = r.getJSONArray("warnings");
        for (int i = 0; i < w.length(); i++) {
            kinds.add(w.getJSONObject(i).getString("kind"));
        }
        return kinds;
    }

    private static String message(JSONObject r, String kind) throws Exception {
        JSONArray w = r.getJSONArray("warnings");
        for (int i = 0; i < w.length(); i++) {
            if (w.getJSONObject(i).getString("kind").equals(kind)) {
                return w.getJSONObject(i).getString("message");
            }
        }
        throw new AssertionError("no " + kind + " warning in " + r.toString(2));
    }

    private static List<String> list(JSONArray a) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            out.add(a.getString(i));
        }
        return out;
    }
}
