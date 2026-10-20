package com.github.susanaluis877.apex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApexConfigTest {

    @Test
    public void defaults() {
        ApexConfig config = new ApexConfig.Builder().build();
        assertFalse(config.isDebug());
    }

    @Test
    public void builderSetsValues() {
        ApexConfig config = new ApexConfig.Builder()
                .setDebug(true)
                .build();
        assertTrue(config.isDebug());
    }
}
