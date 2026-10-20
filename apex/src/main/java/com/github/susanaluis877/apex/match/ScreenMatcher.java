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
     * 说明当前屏幕为什么不匹配，用于日志和运行轨迹；匹配时返回 null。
     *
     * <p>会再次执行条件（包括自定义条件），只在需要解释时调用。
     */
    public abstract String explainMismatch(ScreenSnapshot screen);

    /**
     * 收集条件树里引用的自定义条件名，用于构建流程时校验是否已注册。
     */
    public abstract void collectCustomNames(Set<String> out);
}
