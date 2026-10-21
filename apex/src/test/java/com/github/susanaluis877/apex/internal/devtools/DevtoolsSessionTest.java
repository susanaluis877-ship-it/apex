package com.github.susanaluis877.apex.internal.devtools;

import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.screen;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.internal.engine.EngineConfig;
import com.github.susanaluis877.apex.internal.engine.FakeDevice;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Selector;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DevtoolsSessionTest {

    private static final ScreenSnapshot HOME = screen(frame(button("start", "Start"), text("Home")));
    private static final ScreenSnapshot TASK = screen(frame(button("claim", "Claim"), text("Task")));

    private final FakeDevice device = new FakeDevice();
    private final DevtoolsSession session = new DevtoolsSession(device, device, device, EngineConfig.DEFAULT);

    @Test
    public void snapshotWaitsForQuietScreen() throws Exception {
        device.show(HOME);
        long start = device.now();
        ScreenSnapshot s = session.snapshot(device, 0);
        assertEquals(HOME.getSeq(), s.getSeq());
        assertEquals("没有新事件时等一个轮询间隔，再确认一次页面稳定", 700, device.elapsedSince(start));

        start = device.now();
        device.after(300, () -> device.show(TASK));
        s = session.snapshot(device, 0);
        assertEquals(TASK.getSeq(), s.getSeq());
        assertEquals("事件之后再等安静 200ms，再确认一次页面稳定", 700, device.elapsedSince(start));

        start = device.now();
        session.snapshot(device, 1_000);
        assertEquals(1_700, device.elapsedSince(start));
    }

    @Test
    public void snapshotWaitsUntilAnimationEnds() throws Exception {
        int[] x = {1_080};
        // 页面从右侧滑入，每次快照位置都在变，直到第三次停住
        device.show(() -> screen(frame(button("start", "Start").bounds(x[0], 0, x[0] + 100, 50))));
        device.after(100, () -> x[0] = 500);
        device.after(700, () -> x[0] = 0);
        ScreenSnapshot s = session.snapshot(device, 0);
        assertEquals(0, s.nodes().get(1).getBounds().getLeft());
    }

    @Test
    public void findValidatesSelectorsOnTheLatestSnapshot() throws Exception {
        device.show(HOME);
        ScreenSnapshot s = session.snapshot(device, 0);
        List<Selector> selectors = Arrays.asList(By.id("start"), By.clickable(true), By.text("nope"));
        JSONObject r = session.find(selectors, s.getSeq());
        assertEquals(1, r.getJSONArray("results").getJSONObject(0).getInt("count"));
        assertEquals(1, r.getJSONArray("results").getJSONObject(1).getInt("count"));
        JSONObject miss = r.getJSONArray("results").getJSONObject(2);
        assertEquals(0, miss.getInt("count"));
        assertTrue(miss.getString("explain"), miss.getString("explain").startsWith("no node matches"));

        device.show(TASK);
        session.snapshot(device, 0);
        try {
            session.find(selectors, s.getSeq());
            fail("expected stale snapshot");
        } catch (DevtoolsException e) {
            assertEquals(DevtoolsException.STALE_SNAPSHOT, e.getCode());
        }
        assertEquals(0, session.find(Collections.singletonList(By.id("start")), -1)
                .getJSONArray("results").getJSONObject(0).getInt("count"));
    }

    @Test
    public void actReportsResultChangeAndNewScreen() throws Exception {
        device.show(HOME);
        device.onClick("start", () -> device.show(TASK));
        JSONObject r = session.act(Actions.click(By.id("start")), device, 0);
        assertEquals("OK", r.getString("status"));
        assertTrue(r.getBoolean("changed"));
        assertEquals(HOME.getSeq(), r.getLong("before"));
        assertEquals(TASK.getSeq(), r.getJSONObject("snapshot").getLong("seq"));
        assertEquals(Collections.singletonList("click:start"), device.log);
        assertEquals(TASK.getSeq(), session.last().getSeq());
    }

    @Test
    public void actIncludesTheBeforeScreenOnlyWhenItChangedSinceLastLook() throws Exception {
        device.show(HOME);
        session.snapshot(device, 0);
        JSONObject fresh = session.act(Actions.back(), device, 0);
        assertFalse("刚看过的页面就是动作前的页面", fresh.has("beforeSnapshot"));

        device.show(TASK);
        JSONObject stale = session.act(Actions.back(), device, 0);
        assertEquals(TASK.getSeq(), stale.getJSONObject("beforeSnapshot").getLong("seq"));
    }

    @Test
    public void actReportsSoftFailureWithoutChange() throws Exception {
        device.show(HOME);
        JSONObject r = session.act(Actions.click(By.id("nope")), device, 0);
        assertEquals("SOFT_FAIL", r.getString("status"));
        assertTrue(r.getString("message").contains("target not found"));
        assertFalse(r.getBoolean("changed"));
        assertTrue(device.log.isEmpty());
    }

    @Test
    public void serviceDownIsReported() {
        device.serviceDown(true);
        try {
            session.snapshot(device, 0);
            fail("expected failure");
        } catch (DevtoolsException e) {
            assertEquals(DevtoolsException.SERVICE_UNAVAILABLE, e.getCode());
        }
    }
}
