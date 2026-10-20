package com.github.susanaluis877.apex.match;

import com.github.susanaluis877.apex.node.ScreenSnapshot;

import java.util.Set;

/**
 * 页面级匹配条件，判断当前屏幕是否处于某个状态。通过 {@link Match} 创建，不可变。
 */
public abstract class ScreenMatcher {

    ScreenMatcher() {
    }

    public abstract boolean matches(ScreenSnapshot screen);

    /**
     * 收集条件树里引用的自定义条件名，用于构建流程时校验是否已注册。
     */
    public abstract void collectCustomNames(Set<String> out);
}
