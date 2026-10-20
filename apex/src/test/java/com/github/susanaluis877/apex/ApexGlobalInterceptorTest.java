package com.github.susanaluis877.apex;

import static com.github.susanaluis877.apex.action.Actions.back;
import static com.github.susanaluis877.apex.match.Match.always;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.susanaluis877.apex.flow.FlowValidationException;
import com.github.susanaluis877.apex.flow.Interceptor;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ApexGlobalInterceptorTest {

    @After
    public void tearDown() {
        Apex.clearGlobalInterceptors();
    }

    @Test
    public void addReplaceRemoveKeepRegistrationOrder() {
        Apex.addGlobalInterceptor("a", i -> i.match(always()).action(back()));
        Apex.addGlobalInterceptor("b", i -> i.match(always()).action(back()));
        Apex.addGlobalInterceptor("a", i -> i.match(always()).action(back()).maxTimes(9));
        assertEquals(Arrays.asList("a", "b"), ids());
        assertEquals(9, Apex.getGlobalInterceptors().get(0).getMaxTimes());

        assertTrue(Apex.removeGlobalInterceptor("a"));
        assertFalse(Apex.removeGlobalInterceptor("a"));
        assertEquals(Arrays.asList("b"), ids());
    }

    @Test(expected = FlowValidationException.class)
    public void invalidDefinitionFailsAtRegistration() {
        Apex.addGlobalInterceptor("empty", i -> i.match(always()));
    }

    private static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (Interceptor interceptor : Apex.getGlobalInterceptors()) {
            ids.add(interceptor.getId());
        }
        return ids;
    }
}
