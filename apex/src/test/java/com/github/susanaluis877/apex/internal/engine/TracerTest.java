package com.github.susanaluis877.apex.internal.engine;

import static org.junit.Assert.assertEquals;

import com.github.susanaluis877.apex.ApexLogger;
import com.github.susanaluis877.apex.TraceEvent;

import org.junit.Test;

import java.util.List;

public class TracerTest {

    @Test
    public void keepsHeadAndMostRecentEvents() {
        long[] now = {1_000};
        Tracer tracer = new Tracer("f#1", () -> now[0]);
        tracer.start(1_000);
        for (int i = 0; i < 1_500; i++) {
            now[0]++;
            tracer.event(TraceEvent.Type.ACTION, ApexLogger.DEBUG, null, "e" + i);
        }
        List<TraceEvent> events = tracer.events();
        assertEquals(Tracer.MAX_EVENTS, events.size());
        assertEquals(500, tracer.dropped());
        assertEquals("e0", events.get(0).getMessage());
        assertEquals("e99", events.get(Tracer.HEAD - 1).getMessage());
        assertEquals("开头之后紧接着的是最近的记录", "e600", events.get(Tracer.HEAD).getMessage());
        assertEquals("e1499", events.get(events.size() - 1).getMessage());
        assertEquals(1_500, events.get(events.size() - 1).getTimeMs());
    }

    @Test
    public void formatsEventLines() {
        TraceEvent e = new TraceEvent(61_234, TraceEvent.Type.WAIT, ApexLogger.DEBUG, "task", "next of 'task'");
        assertEquals("+61.234s WAIT        next of 'task'", e.toString());
        assertEquals("0.007s", TraceEvent.formatSeconds(7));
    }
}
