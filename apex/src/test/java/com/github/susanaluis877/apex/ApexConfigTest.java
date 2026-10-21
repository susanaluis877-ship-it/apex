package com.github.susanaluis877.apex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApexConfigTest {

    @Test
    public void defaults() {
        ApexConfig config = new ApexConfig.Builder().build();
        assertFalse(config.isDebug());
        assertSame(ApexLogger.LOGCAT, config.getLogger());
        assertEquals(ApexLogger.WARN, config.getLogLevel());
        assertTrue("调试桥默认允许，实际只在 debuggable 包里启动", config.isDevtools());
        assertFalse(new ApexConfig.Builder().setDevtools(false).build().isDevtools());
    }

    @Test
    public void debugLowersDefaultLevelUnlessExplicit() {
        assertEquals(ApexLogger.DEBUG, new ApexConfig.Builder().setDebug(true).build().getLogLevel());
        assertEquals(ApexLogger.VERBOSE,
                new ApexConfig.Builder().setDebug(true).setLogLevel(ApexLogger.VERBOSE).build().getLogLevel());
        ApexLogger logger = (priority, tag, message, throwable) -> { };
        assertSame(logger, new ApexConfig.Builder().setLogger(logger).build().getLogger());
        assertSame(ApexLogger.LOGCAT, new ApexConfig.Builder().setLogger(null).build().getLogger());
    }

    @Test
    public void builderSetsValues() {
        ApexConfig config = new ApexConfig.Builder()
                .setDebug(true)
                .build();
        assertTrue(config.isDebug());
    }
}
