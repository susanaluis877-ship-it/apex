package com.github.susanaluis877.apex.match;

import com.github.susanaluis877.apex.node.ScreenSnapshot;

import java.util.Map;

/**
 * 宿主自定义的页面条件，通过 {@code Apex.registerMatcher(name, matcher)} 注册后，
 * 在 DSL 里用 {@link Match#custom(String)} 或在 JSON 里用 {@code "custom"} 按名字引用。
 *
 * <p>在引擎线程上调用，不要做耗时操作。
 */
public interface CustomMatcher {

    /**
     * @param args 流程定义里写的参数，不可修改；没有参数时为空 Map
     */
    boolean matches(ScreenSnapshot screen, Map<String, String> args);
}
