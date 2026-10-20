package com.github.susanaluis877.apex.internal;

import com.github.susanaluis877.apex.action.CustomAction;
import com.github.susanaluis877.apex.flow.Interceptor;
import com.github.susanaluis877.apex.match.CustomMatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义动作和自定义条件的名字注册表（流程定义里只保存名字，构建时校验、运行时按名字查找），
 * 以及对所有流程生效的全局拦截器。
 */
public final class Registry {

    private static final Registry INSTANCE = new Registry();

    private final Map<String, CustomAction> actions = new ConcurrentHashMap<>();
    private final Map<String, CustomMatcher> matchers = new ConcurrentHashMap<>();
    /** 按注册顺序保存；同 id 再次注册时替换并保持原位置。 */
    private final Map<String, Interceptor> globalInterceptors = new LinkedHashMap<>();

    private Registry() {
    }

    public static Registry get() {
        return INSTANCE;
    }

    public void registerAction(String name, CustomAction action) {
        actions.put(name, action);
    }

    public void registerMatcher(String name, CustomMatcher matcher) {
        matchers.put(name, matcher);
    }

    public CustomAction action(String name) {
        return actions.get(name);
    }

    public CustomMatcher matcher(String name) {
        return matchers.get(name);
    }

    /** @return 被替换掉的同 id 拦截器，没有时为 null */
    public Interceptor putGlobalInterceptor(Interceptor interceptor) {
        synchronized (globalInterceptors) {
            return globalInterceptors.put(interceptor.getId(), interceptor);
        }
    }

    public boolean removeGlobalInterceptor(String id) {
        synchronized (globalInterceptors) {
            return globalInterceptors.remove(id) != null;
        }
    }

    public void clearGlobalInterceptors() {
        synchronized (globalInterceptors) {
            globalInterceptors.clear();
        }
    }

    /** 当前全局拦截器的快照，按注册顺序排列。每次运行开始时取一次，运行中途增删不影响这次运行。 */
    public List<Interceptor> globalInterceptors() {
        synchronized (globalInterceptors) {
            return new ArrayList<>(globalInterceptors.values());
        }
    }

    /** 仅供测试使用。 */
    public void clear() {
        actions.clear();
        matchers.clear();
        clearGlobalInterceptors();
    }
}
