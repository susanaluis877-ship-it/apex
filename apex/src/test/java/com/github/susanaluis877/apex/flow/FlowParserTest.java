package com.github.susanaluis877.apex.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Match;

import org.junit.Test;

public class FlowParserTest {

    private static final String STATES =
            "[{\"id\": \"a\", \"match\": {\"always\": true}, \"actions\": [{\"type\": \"back\"}], \"next\": \"b\"},"
                    + " {\"id\": \"b\", \"match\": {\"has\": {\"text\": \"ok\"}}, \"end\": \"success\"}]";

    @Test
    public void minimalFlow() throws Exception {
        Flow flow = FlowParser.parse("{\"id\": \"f\", \"start\": \"a\", \"states\": " + STATES + "}");
        assertEquals("f", flow.getId());
        assertEquals(2, flow.getStates().size());
        assertEquals(FlowDefaults.DEFAULT_STATE_TIMEOUT_MS, flow.getState("a").getTimeoutMs());
    }

    @Test
    public void globalInterceptorOptions() throws Exception {
        Flow flow = FlowParser.parse("{\"id\": \"f\", \"start\": \"a\", \"useGlobalInterceptors\": false,"
                + " \"excludeGlobalInterceptors\": [\"ad\", \"perm\"], \"states\": " + STATES + "}");
        assertEquals(false, flow.isUseGlobalInterceptors());
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("ad", "perm")), flow.getExcludedGlobalInterceptors());
        assertError("{\"id\": \"f\", \"start\": \"a\", \"excludeGlobalInterceptors\": [1], \"states\": " + STATES + "}",
                "excludeGlobalInterceptors[0]", "expected string");
    }

    @Test
    public void explicitNullIsTreatedAsMissing() throws Exception {
        Flow flow = FlowParser.parse("{\"id\": \"f\", \"start\": \"a\", \"timeoutMs\": null, \"states\": " + STATES + "}");
        assertEquals(Flow.DEFAULT_TIMEOUT_MS, flow.getTimeoutMs());
    }

    @Test
    public void errorPaths() {
        assertError("not json", "", "invalid JSON");
        assertError("{\"start\": \"a\", \"states\": []}", "id", "required");
        assertError("{\"id\": \"f\", \"schema\": 2, \"states\": []}", "schema", "unsupported schema 2");
        assertError("{\"id\": \"f\", \"stats\": []}", "stats", "unknown key");
        assertError("{\"id\": \"f\", \"start\": \"a\"}", "states", "required");
        assertError("{\"id\": \"f\", \"timeoutMs\": \"10\", \"states\": []}", "timeoutMs", "expected integer");
        assertError("{\"id\": \"f\", \"timeoutMs\": 1.5, \"states\": []}", "timeoutMs", "expected integer");
        assertError(flow("{\"id\": \"a\", \"mtach\": {}}"), "states[0].mtach", "unknown key");
        assertError(flow("{\"id\": \"a\", \"match\": {}}"), "states[0].match",
                "matcher needs at least one condition");
        assertError(flow("{\"id\": \"a\", \"match\": {\"has\": [{\"text\": \"x\"}, {\"idd\": \"y\"}]}}"),
                "states[0].match.has[1].idd", "unknown key");
        assertError(flow("{\"id\": \"a\", \"match\": {\"has\": {\"textMatches\": \"(\"}}}"),
                "states[0].match.has.textMatches", "invalid regex");
        assertError(flow("{\"id\": \"a\", \"match\": {\"has\": {\"visible\": false}}}"),
                "states[0].match.has", "selector needs at least one condition");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": false}}"), "states[0].match.always",
                "expected true");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true}, \"actions\": [{\"type\": \"tapp\"}]}"),
                "states[0].actions[0].type", "unknown action type 'tapp'");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true}, \"actions\": [{\"type\": \"click\"}]}"),
                "states[0].actions[0].target", "required");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true},"
                        + " \"actions\": [{\"type\": \"click\", \"target\": {\"id\": \"x\"}, \"mode\": \"fast\"}]}"),
                "states[0].actions[0].mode", "expected one of [auto, node, gesture]");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true},"
                        + " \"actions\": [{\"type\": \"back\", \"target\": {\"id\": \"x\"}}]}"),
                "states[0].actions[0].target", "unknown key");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true},"
                        + " \"actions\": [{\"type\": \"tap\", \"xRatio\": 1.5, \"yRatio\": 0.5}]}"),
                "states[0].actions[0]", "ratio must be within [0, 1]");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true}, \"actions\": [{\"type\": \"setProgress\","
                + " \"target\": {\"id\": \"s\"}, \"value\": 1, \"ratio\": 0.5}]}"), "states[0].actions[0]",
                "give exactly one of value and ratio");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true}, \"actions\": [{\"type\": \"setProgress\","
                + " \"target\": {\"id\": \"s\"}, \"ratio\": 2}]}"), "states[0].actions[0]", "ratio must be within [0, 1]");
        assertError(flow("{\"id\": \"a\", \"match\": {\"has\": {\"hasDescendant\": []}}}"),
                "states[0].match.has.hasDescendant", "expected at least one selector");
        assertError(flow("{\"id\": \"a\", \"match\": {\"has\": {\"inside\": {\"txt\": \"a\"}}}}"),
                "states[0].match.has.inside.txt", "unknown key");
        assertError(flow("{\"id\": \"a\", \"match\": {\"has\": {\"progressAtLeast\": 2}}}"),
                "states[0].match.has.progressAtLeast", "expected a number within [0, 1]");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true}, \"actions\": [{\"type\": \"scrollTo\","
                + " \"target\": {\"id\": \"s\"}, \"maxSwipes\": 0}]}"), "states[0].actions[0].maxSwipes", "must be >= 1");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true}, \"end\": \"ok\"}"),
                "states[0].end", "expected \"success\" or \"failure\"");
        assertError(flow("{\"id\": \"a\", \"match\": {\"always\": true}, \"next\": [1]}"),
                "states[0].next[0]", "expected string");
    }

    @Test
    public void validationProblemsMapToJsonPaths() {
        assertError("{\"id\": \"f\", \"start\": \"a\", \"states\": ["
                        + "{\"id\": \"a\", \"match\": {\"always\": true}, \"end\": \"success\"},"
                        + "{\"id\": \"b\", \"match\": {\"always\": true}, \"next\": [\"a\", \"zzz\"]}]}",
                "states[1].next[1]", "unknown state 'zzz'");
        assertError("{\"id\": \"f\", \"start\": \"nope\", \"states\": " + STATES + "}",
                "start", "unknown state 'nope'");
        assertError("{\"id\": \"f\", \"start\": \"a\", \"states\": " + STATES
                        + ", \"interceptors\": [{\"id\": \"i\", \"match\": {\"always\": true}}]}",
                "interceptors[0].actions", "interceptor needs at least one action");
        assertError("{\"id\": \"f\", \"start\": \"a\", \"defaults\": {\"timeoutMs\": 0}, \"states\": " + STATES + "}",
                "defaults.timeoutMs", "must be > 0");
        assertError("{\"id\": \"f\", \"start\": \"a\", \"states\": ["
                        + "{\"id\": \"a\", \"match\": {\"custom\": \"nope\"}, \"end\": \"success\"}]}",
                "states[0].match", "custom matcher 'nope' is not registered");
    }

    @Test
    public void standaloneParsersMatchDsl() throws Exception {
        assertEquals(By.id("ok").enabled(true), FlowParser.parseSelector("{\"id\": \"ok\", \"enabled\": true}"));
        assertEquals(Match.allOf(Match.pkg("p"), Match.has(By.text("a"))),
                FlowParser.parseMatcher("{\"package\": \"p\", \"has\": {\"text\": \"a\"}}"));
        assertEquals(Actions.click(By.desc("go")).optional(),
                FlowParser.parseAction("{\"type\": \"click\", \"target\": {\"desc\": \"go\"}, \"optional\": true}"));
        // 单独解析时不检查自定义名字是否已注册，交给运行时或流程构建
        assertEquals(Match.custom("nope"), FlowParser.parseMatcher("{\"custom\": \"nope\"}"));
    }

    @Test
    public void standaloneParserErrorPathsAreRelative() {
        assertStandaloneError(() -> FlowParser.parseSelector("{\"idd\": \"x\"}"), "idd", "unknown key");
        assertStandaloneError(() -> FlowParser.parseMatcher("{\"has\": [{\"text\": 1}]}"), "has[0].text",
                "expected string");
        assertStandaloneError(() -> FlowParser.parseAction("{\"type\": \"swipe\"}"), "fromRatio", "required");
        assertStandaloneError(() -> FlowParser.parseAction("[]"), "", "invalid JSON");
    }

    private interface Parse {
        Object run() throws FlowParseException;
    }

    private static void assertStandaloneError(Parse parse, String path, String reasonPrefix) {
        try {
            parse.run();
            fail("expected FlowParseException");
        } catch (FlowParseException e) {
            assertEquals(e.getMessage(), path, e.getPath());
            assertTrue(e.getMessage(), e.getReason().startsWith(reasonPrefix));
        }
    }

    private static String flow(String firstState) {
        return "{\"id\": \"f\", \"start\": \"a\", \"states\": [" + firstState + "]}";
    }

    private static void assertError(String json, String path, String reasonPrefix) {
        try {
            FlowParser.parse(json);
            fail("expected FlowParseException for " + json);
        } catch (FlowParseException e) {
            assertEquals(e.getMessage(), path, e.getPath());
            assertTrue(e.getMessage(), e.getReason().startsWith(reasonPrefix));
        }
    }
}
