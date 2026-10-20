package com.example.apex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.Executor;

public class ApexConfigTest {

    @Test
    public void defaults() {
        ApexConfig config = new ApexConfig.Builder().build();
        assertFalse(config.isDebug());
        assertNull(config.getExecutor());
    }

    @Test
    public void builderSetsValues() {
        Executor executor = Runnable::run;
        ApexConfig config = new ApexConfig.Builder()
                .setDebug(true)
                .setExecutor(executor)
                .build();
        assertTrue(config.isDebug());
        assertSame(executor, config.getExecutor());
    }
}
