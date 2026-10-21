package com.github.susanaluis877.apex.internal.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;

import org.junit.Test;

import java.util.Collections;

public class RunMonitorTest {

    private final RunMonitor monitor = new RunMonitor();

    @Test
    public void tracksTheRunAndKeepsTheLastResult() {
        int[] changes = {0};
        monitor.addListener(() -> changes[0]++);
        assertNull(monitor.view().getFlowId());

        monitor.onStart("demo#1", "demo");
        monitor.onStateEntered("home", 1);
        for (int i = 0; i < RunMonitor.RECENT + 5; i++) {
            monitor.onTrace(new TraceEvent(i, TraceEvent.Type.WAIT, 3, "home", "waiting " + i));
        }
        RunMonitor.View running = monitor.view();
        assertTrue(running.isRunning());
        assertEquals("home", running.getStateId());
        assertEquals(1, running.getStep());
        assertEquals("只保留最近的轨迹", RunMonitor.RECENT, running.getRecent().size());
        assertEquals("waiting 5", running.getRecent().get(0).getMessage());
        assertEquals(RunMonitor.RECENT + 4, running.getElapsedMs());

        RunResult result = new RunResult(RunResult.Status.SUCCESS, "demo", "demo#1", "done",
                Collections.singletonList("home"), Collections.emptyMap(), 1234, null, Collections.emptyList(), 0);
        monitor.onFinish(result);
        RunMonitor.View done = monitor.view();
        assertFalse(done.isRunning());
        assertEquals(result, done.getResult());
        assertEquals(1234, done.getElapsedMs());
        assertEquals("拷贝不受之后的变化影响", RunMonitor.RECENT, running.getRecent().size());
        assertEquals(RunMonitor.RECENT + 8, changes[0]);

        monitor.onStart("demo#2", "demo");
        assertNull("新的运行清掉上次的结果", monitor.view().getResult());
        assertTrue(monitor.view().getRecent().isEmpty());
    }
}
