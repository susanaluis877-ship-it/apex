package com.github.susanaluis877.apex.internal;

import com.github.susanaluis877.apex.action.CustomAction;
import com.github.susanaluis877.apex.match.CustomMatcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义动作和自定义条件的名字注册表。流程定义里只保存名字，构建时校验、运行时按名字查找。
 */
public final class Registry {

    private static final Registry INSTANCE = new Registry();

    private final Map<String, CustomAction> actions = new ConcurrentHashMap<>();
    private final Map<String, CustomMatcher> matchers = new ConcurrentHashMap<>();

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

    /** 仅供测试使用。 */
    public void clear() {
        actions.clear();
        matchers.clear();
    }
}
