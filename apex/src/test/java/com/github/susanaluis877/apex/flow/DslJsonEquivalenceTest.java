package com.github.susanaluis877.apex.flow;

import static com.github.susanaluis877.apex.action.Actions.back;
import static com.github.susanaluis877.apex.action.Actions.click;
import static com.github.susanaluis877.apex.action.Actions.custom;
import static com.github.susanaluis877.apex.action.Actions.home;
import static com.github.susanaluis877.apex.action.Actions.launchApp;
import static com.github.susanaluis877.apex.action.Actions.longClick;
import static com.github.susanaluis877.apex.action.Actions.scroll;
import static com.github.susanaluis877.apex.action.Actions.setText;
import static com.github.susanaluis877.apex.action.Actions.sleep;
import static com.github.susanaluis877.apex.action.Actions.swipe;
import static com.github.susanaluis877.apex.action.Actions.swipeRatio;
import static com.github.susanaluis877.apex.action.Actions.tap;
import static com.github.susanaluis877.apex.action.Actions.tapRatio;
import static com.github.susanaluis877.apex.match.Match.activity;
import static com.github.susanaluis877.apex.match.Match.allOf;
import static com.github.susanaluis877.apex.match.Match.always;
import static com.github.susanaluis877.apex.match.Match.anyOf;
import static com.github.susanaluis877.apex.match.Match.has;
import static com.github.susanaluis877.apex.match.Match.not;
import static com.github.susanaluis877.apex.match.Match.pkg;
import static org.junit.Assert.assertEquals;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Match;

import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class DslJsonEquivalenceTest {

    static final String DEMO_PKG = "com.github.susanaluis877.apex.demo";

    @After
    public void tearDown() {
        Registry.get().clear();
    }

    /** README 和 demo 里用的同一个流程。 */
    static Flow demoClaimDsl() {
        return new Flow.Builder("demo.claim")
                .start("home").timeoutMs(60_000).maxSteps(20)
                .defaults(d -> d.timeoutMs(10_000).retryIntervalMs(3_000).maxRetries(2))
                .state("home", s -> s
                        .match(allOf(pkg(DEMO_PKG), has(By.id("demo_start"))))
                        .action(click(By.id("demo_start")))
                        .next("task", "sold_out"))
                .state("task", s -> s
                        .match(has(By.id("demo_claim").enabled(true)))
                        .action(click(By.id("demo_claim")))
                        .next("done").timeoutMs(15_000))
                .state("done", s -> s.match(has(By.textContains("Reward claimed"))).endSuccess())
                .state("sold_out", s -> s.match(has(By.text("Sold out"))).action(back()).endFailure())
                .interceptor("close_ad", i -> i
                        .match(allOf(has(By.text("AD")), has(By.id("demo_ad_close").clickable(true))))
                        .action(click(By.id("demo_ad_close")))
                        .maxTimes(3))
                .build();
    }

    @Test
    public void demoFlowJsonEqualsDsl() throws Exception {
        assertEquals(demoClaimDsl(), FlowParser.parse(resource("/flows/demo_claim.json")));
    }

    @Test
    public void everyActionAndMatcherKind() throws Exception {
        Registry.get().registerAction("log", (ctx, args) -> true);
        Registry.get().registerMatcher("isReady", (screen, args) -> true);

        String json = "{\"id\": \"all\", \"start\": \"s\", \"maxInterceptions\": 4,"
                + " \"defaults\": {\"actionGapMs\": 100, \"interceptorMaxTimes\": 2, \"interceptorCooldownMs\": 10},"
                + " \"states\": [{\"id\": \"s\","
                + "   \"match\": {\"package\": \"p\", \"activity\": \".Main\","
                + "     \"not\": {\"has\": {\"text\": \"x\"}},"
                + "     \"anyOf\": [{\"has\": {\"className\": \"Button\", \"checked\": false}}, {\"always\": true}],"
                + "     \"custom\": {\"name\": \"isReady\", \"args\": {\"level\": 2, \"strict\": true}}},"
                + "   \"actions\": ["
                + "     {\"type\": \"longClick\", \"target\": {\"desc\": \"d\", \"visible\": false}, \"mode\": \"gesture\"},"
                + "     {\"type\": \"tap\", \"x\": 10, \"y\": 20},"
                + "     {\"type\": \"tap\", \"xRatio\": 0.5, \"yRatio\": 0.9, \"optional\": true},"
                + "     {\"type\": \"swipe\", \"from\": [1, 2], \"to\": [3, 4]},"
                + "     {\"type\": \"swipe\", \"fromRatio\": [0.5, 0.8], \"toRatio\": [0.5, 0.2], \"durationMs\": 500},"
                + "     {\"type\": \"scroll\", \"direction\": \"forward\"},"
                + "     {\"type\": \"scroll\", \"target\": {\"scrollable\": true, \"id\": \"list\"}, \"direction\": \"down\"},"
                + "     {\"type\": \"setText\", \"target\": {\"className\": \"EditText\"}, \"text\": \"hi\"},"
                + "     {\"type\": \"home\", \"delayMs\": 50},"
                + "     {\"type\": \"launchApp\", \"package\": \"com.foo\"},"
                + "     {\"type\": \"sleep\", \"ms\": 1000},"
                + "     {\"type\": \"custom\", \"name\": \"log\", \"args\": {\"msg\": \"hello\", \"ratio\": 1.50}}"
                + "   ],"
                + "   \"next\": [\"t\"], \"retryIntervalMs\": 1000, \"maxRetries\": 0},"
                + "  {\"id\": \"t\", \"match\": {\"has\": {\"textMatches\": \"\\\\d+\", \"descContains\": \"c\"}},"
                + "   \"end\": \"success\"}],"
                + " \"interceptors\": [{\"id\": \"i\", \"match\": {\"allOf\": [{\"has\": {\"selected\": true}},"
                + "   {\"has\": {\"enabled\": false}}]}, \"actions\": [{\"type\": \"back\"}], \"cooldownMs\": 0}]}";

        Map<String, String> matcherArgs = new LinkedHashMap<>();
        matcherArgs.put("level", "2");
        matcherArgs.put("strict", "true");
        Map<String, String> actionArgs = new LinkedHashMap<>();
        actionArgs.put("msg", "hello");
        actionArgs.put("ratio", "1.5");

        Flow dsl = new Flow.Builder("all").start("s").maxInterceptions(4)
                .defaults(d -> d.actionGapMs(100).interceptorMaxTimes(2).interceptorCooldownMs(10))
                .state("s", s -> s
                        .match(allOf(pkg("p"), activity(".Main"), not(has(By.text("x"))),
                                anyOf(has(By.className("Button").checked(false)), always()),
                                Match.custom("isReady", matcherArgs)))
                        .action(longClick(By.desc("d").visible(false), Action.ClickMode.GESTURE),
                                tap(10, 20),
                                tapRatio(0.5, 0.9).optional(),
                                swipe(1, 2, 3, 4),
                                swipe(com.github.susanaluis877.apex.action.Coord.ratio(0.5, 0.8),
                                        com.github.susanaluis877.apex.action.Coord.ratio(0.5, 0.2), 500),
                                scroll(Action.Direction.FORWARD),
                                scroll(By.id("list").scrollable(true), Action.Direction.DOWN),
                                setText(By.className("EditText"), "hi"),
                                home().delayMs(50),
                                launchApp("com.foo"),
                                sleep(1000),
                                custom("log", actionArgs))
                        .next("t").retryIntervalMs(1000).maxRetries(0))
                .state("t", s -> s.match(has(By.textMatches("\\d+").descContains("c"))).endSuccess())
                .interceptor("i", i -> i.match(has(By.selected(true), By.enabled(false)))
                        .action(back()).cooldownMs(0))
                .build();

        assertEquals(dsl, FlowParser.parse(json));
        assertEquals(swipeRatio(0.5, 0.8, 0.5, 0.2), swipeRatio(0.5, 0.8, 0.5, 0.2));
    }

    static String resource(String name) throws Exception {
        try (InputStream in = DslJsonEquivalenceTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
