package com.github.susanaluis877.apex.internal.devtools;

import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.screen;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.susanaluis877.apex.RunHandle;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Match;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.internal.engine.Clock;
import com.github.susanaluis877.apex.internal.engine.EngineConfig;
import com.github.susanaluis877.apex.internal.engine.FakeDevice;
import com.github.susanaluis877.apex.internal.engine.FlowEngine;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class DispatcherTest {

    private static final ScreenSnapshot HOME = screen(frame(button("start", "Start"), text("Home")));
    private static final ScreenSnapshot DONE = screen(frame(text("Reward claimed")));
    private static final String FLOW = "{\"id\": \"demo\", \"start\": \"home\", \"states\": ["
            + "{\"id\": \"home\", \"match\": {\"has\": {\"id\": \"start\"}},"
            + " \"actions\": [{\"type\": \"click\", \"target\": {\"id\": \"start\"}}], \"next\": \"done\"},"
            + "{\"id\": \"done\", \"match\": {\"has\": {\"textContains\": \"Reward\"}}, \"end\": \"success\"}]}";

    private final FakeDevice device = new FakeDevice();
    private final FakeBackend backend = new FakeBackend();
    private final Dispatcher dispatcher = new Dispatcher(backend);

    @After
    public void tearDown() {
        Registry.get().clear();
    }

    @Test
    public void rejectsMalformedRequests() throws Exception {
        assertError(call("not json"), DevtoolsException.BAD_REQUEST);
        assertError(call("{}"), DevtoolsException.BAD_REQUEST);
        JSONObject r = call("{\"cmd\": \"jump\"}");
        assertError(r, DevtoolsException.BAD_REQUEST);
        assertTrue(r.getJSONObject("error").getString("message").contains("supported: info"));
    }

    @Test
    public void infoIncludesProtocolAndRegistry() throws Exception {
        Registry.get().registerAction("pay", (ctx, args) -> true);
        JSONObject r = call("{\"cmd\": \"info\"}");
        assertTrue(r.getBoolean("ok"));
        assertEquals(Dispatcher.PROTOCOL, r.getInt("protocol"));
        assertEquals("pay", r.getJSONArray("customActions").getString(0));
        assertTrue(r.getJSONObject("device").getBoolean("fake"));
    }

    @Test
    public void snapshotFindAndAct() throws Exception {
        device.show(HOME);
        device.onClick("start", () -> device.show(DONE));
        JSONObject snap = call("{\"cmd\": \"snapshot\"}");
        assertEquals(SnapshotJson.FORMAT, snap.getJSONObject("snapshot").getInt("format"));
        long seq = snap.getJSONObject("snapshot").getLong("seq");

        JSONObject found = call("{\"cmd\": \"find\", \"seq\": " + seq + ", \"selectors\": [{\"id\": \"start\"}]}");
        assertEquals(1, found.getJSONArray("results").getJSONObject(0).getInt("count"));
        JSONObject bad = call("{\"cmd\": \"find\", \"selectors\": [{\"id\": \"a\"}, {\"idd\": \"b\"}]}");
        assertError(bad, DevtoolsException.PARSE_ERROR);
        assertEquals("selectors[1].idd", bad.getJSONObject("error").getString("path"));

        JSONObject acted = call("{\"cmd\": \"act\", \"action\": {\"type\": \"click\", \"target\": {\"id\": \"start\"}}}");
        assertEquals("OK", acted.getString("status"));
        assertTrue(acted.getBoolean("changed"));
        JSONObject badAction = call("{\"cmd\": \"act\", \"action\": {\"type\": \"tapp\"}}");
        assertEquals("action.type", badAction.getJSONObject("error").getString("path"));
    }

    @Test
    public void busyEngineIsReported() throws Exception {
        backend.busy = true;
        assertError(call("{\"cmd\": \"snapshot\"}"), DevtoolsException.BUSY);
    }

    @Test
    public void validateReturnsAllProblemsWithPaths() throws Exception {
        JSONObject ok = call("{\"cmd\": \"validate\", \"flow\": " + FLOW + "}");
        assertTrue(ok.getBoolean("valid"));
        assertEquals("home", ok.getJSONObject("flow").getString("start"));
        assertEquals(2, ok.getJSONObject("flow").getJSONArray("states").length());

        String broken = "{\"id\": \"f\", \"start\": \"a\", \"states\": ["
                + "{\"id\": \"a\", \"match\": {\"always\": true}, \"next\": [\"zzz\"]},"
                + "{\"id\": \"b\", \"match\": {\"always\": true}, \"next\": [\"a\", \"yyy\"]}]}";
        JSONObject r = call("{\"cmd\": \"validate\", \"flow\": " + JSONObject.quote(broken) + "}");
        assertTrue(r.getBoolean("ok"));
        assertFalse(r.getBoolean("valid"));
        String problems = r.getJSONArray("problems").toString();
        assertTrue(problems, problems.contains("states[0].next[0]"));
        assertTrue(problems, problems.contains("states[1].next[1]"));

        JSONObject syntax = call("{\"cmd\": \"validate\", \"flow\": {\"id\": \"f\", \"stats\": []}}");
        assertFalse(syntax.getBoolean("valid"));
        assertEquals("stats", syntax.getString("path"));
    }

    @Test
    public void checkUsesSamplesAndLiveScreen() throws Exception {
        device.show(DONE);
        JSONObject samples = new JSONObject().put("home", SnapshotJson.write(HOME));
        JSONObject r = call(new JSONObject().put("cmd", "check").put("flow", new JSONObject(FLOW))
                .put("snapshots", samples).put("live", true).toString());
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals("home", r.getJSONArray("samples").getJSONObject(0).getString("name"));
        assertEquals("live", r.getJSONArray("samples").getJSONObject(1).getString("name"));
        assertEquals("live", r.getJSONArray("states").getJSONObject(1).getJSONArray("matches").getString(0));

        JSONObject parse = call("{\"cmd\": \"check\", \"live\": true, \"flow\": {\"id\": \"f\"}}");
        assertError(parse, DevtoolsException.PARSE_ERROR);
        assertEquals("flow.states", parse.getJSONObject("error").getString("path"));
    }

    @Test
    public void runStreamsTraceThenResult() throws Exception {
        device.show(HOME);
        device.onClick("start", () -> device.show(DONE));
        List<String> lines = new ArrayList<>();
        dispatcher.handle("{\"cmd\": \"run\", \"flow\": " + FLOW + "}", lines::add);
        JSONObject first = new JSONObject(lines.get(0));
        assertEquals("trace", first.getString("ev"));
        assertEquals("RUN_START", first.getString("type"));
        JSONObject last = new JSONObject(lines.get(lines.size() - 1));
        assertEquals("result", last.getString("ev"));
        assertEquals("SUCCESS", last.getJSONObject("result").getString("status"));
        assertEquals("[\"home\",\"done\"]", last.getJSONObject("result").getJSONArray("path").toString());
        for (String line : lines.subList(1, lines.size() - 1)) {
            assertEquals("trace", new JSONObject(line).getString("ev"));
        }
    }

    @Test
    public void runIsCancelledWhenClientGoesAway() throws Exception {
        backend.hang = true;
        try {
            dispatcher.handle("{\"cmd\": \"run\", \"flow\": " + FLOW + "}", line -> {
                throw new IOException("broken pipe");
            });
            fail("expected IOException");
        } catch (IOException expected) {
            // 写失败会继续抛出，连接线程据此关闭连接
        }
        assertTrue(backend.cancelled);
    }

    @Test
    public void dslFlowsRunByReference() throws Exception {
        device.show(HOME);
        device.onClick("start", () -> device.show(DONE));
        String ref = HostFlows.class.getName() + "#demo";
        List<String> lines = new ArrayList<>();
        dispatcher.handle("{\"cmd\": \"run\", \"flowRef\": \"" + ref + "\"}", lines::add);
        JSONObject last = new JSONObject(lines.get(lines.size() - 1));
        assertEquals("SUCCESS", last.getJSONObject("result").getString("status"));

        JSONObject valid = call("{\"cmd\": \"validate\", \"flowRef\": \"" + SuppliedFlow.class.getName() + "\"}");
        assertTrue(valid.getBoolean("valid"));
        assertEquals("demo", valid.getJSONObject("flow").getString("id"));

        JSONObject broken = call("{\"cmd\": \"validate\", \"flowRef\": \"" + HostFlows.class.getName() + "#broken\"}");
        assertFalse(broken.getBoolean("valid"));
        assertTrue(broken.toString(), broken.getJSONArray("problems").toString().contains("states[0].next[0]"));

        JSONObject missing = call("{\"cmd\": \"run\", \"flowRef\": \"com.nope.Flows#x\"}");
        assertError(missing, DevtoolsException.INVALID_FLOW);
        assertTrue(missing.toString(), missing.getJSONObject("error").getString("message").contains("rebuilt"));
    }

    @Test
    public void exportWritesJsonOrExplainsWhyNot() throws Exception {
        JSONObject r = call("{\"cmd\": \"export\", \"flowRef\": \"" + HostFlows.class.getName() + "#demo\"}");
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals(HostFlows.demo(), com.github.susanaluis877.apex.flow.FlowParser.parse(r.getString("json")));

        JSONObject code = call("{\"cmd\": \"export\", \"flowRef\": \"" + HostFlows.class.getName() + "#withCode\"}");
        assertError(code, DevtoolsException.NOT_EXPORTABLE);
        assertTrue(code.toString(), code.getJSONObject("error").getString("message").startsWith("state 'a' match"));
    }

    private JSONObject call(String request) throws Exception {
        List<String> lines = new ArrayList<>();
        dispatcher.handle(request, lines::add);
        assertEquals(lines.toString(), 1, lines.size());
        return new JSONObject(lines.get(0));
    }

    private static void assertError(JSONObject r, String code) throws Exception {
        assertFalse(r.toString(), r.getBoolean("ok"));
        assertEquals(r.toString(), code, r.getJSONObject("error").getString("code"));
    }

    /** 在调用线程上同步执行一切的假平台。 */
    private final class FakeBackend implements Backend {

        final DevtoolsSession session = new DevtoolsSession(device, device, device, EngineConfig.DEFAULT);
        boolean busy;
        boolean hang;
        boolean cancelled;

        @Override
        public JSONObject info() throws org.json.JSONException {
            return new JSONObject().put("fake", true);
        }

        @Override
        public <T> T call(Task<T> task, long timeoutMs) throws DevtoolsException {
            if (busy) {
                throw new DevtoolsException(DevtoolsException.BUSY, "a flow is running");
            }
            try {
                return task.run(session, device);
            } catch (DevtoolsException e) {
                throw e;
            } catch (Exception e) {
                throw new DevtoolsException(DevtoolsException.INTERNAL, e.toString());
            }
        }

        @Override
        public RunHandle run(Flow flow, Consumer<TraceEvent> sink, Consumer<RunResult> onFinished) {
            if (hang) {
                sink.accept(new TraceEvent(0, TraceEvent.Type.RUN_START, 4, null, "start"));
            } else {
                FlowEngine engine = new FlowEngine("demo#1", flow, Collections.emptyList(), device, device,
                        (Clock) device, device, null, EngineConfig.DEFAULT);
                engine.setTraceSink(sink);
                onFinished.accept(engine.run());
            }
            return new RunHandle() {
                @Override
                public String getFlowId() {
                    return flow.getId();
                }

                @Override
                public boolean isRunning() {
                    return hang && !cancelled;
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            };
        }

        @Override
        public boolean cancelRunning() {
            return false;
        }

        @Override
        public ClassLoader classLoader() {
            return DispatcherTest.class.getClassLoader();
        }
    }

    /** 宿主代码里用 Java DSL 写的流程（测试替身）。 */
    public static final class HostFlows {

        public static Flow demo() {
            return new Flow.Builder("demo").start("home")
                    .state("home", s -> s.match(Match.has(By.id("start"))).action(Actions.click(By.id("start")))
                            .next("done"))
                    .state("done", s -> s.match(Match.has(By.textContains("Reward"))).endSuccess())
                    .build();
        }

        public static Flow withCode() {
            return new Flow.Builder("code").start("a")
                    .state("a", s -> s.match(Match.custom(screen -> true)).endSuccess())
                    .build();
        }

        public static Flow broken() {
            return new Flow.Builder("broken").start("a")
                    .state("a", s -> s.match(Match.always()).next("missing"))
                    .build();
        }
    }

    public static final class SuppliedFlow implements java.util.function.Supplier<Flow> {
        @Override
        public Flow get() {
            return HostFlows.demo();
        }
    }
}
