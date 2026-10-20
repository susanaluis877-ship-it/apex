package com.github.susanaluis877.apex.match;

import com.github.susanaluis877.apex.node.ScreenSnapshot;

/**
 * 用代码写的页面条件，通过 {@link Match#custom(ScreenCondition)} 直接放进 {@code match(...)}，不需要注册名字：
 *
 * <pre>{@code
 * .match(custom(screen -> {
 *     UiNode balance = By.id("tv_balance").findFirst(screen);
 *     return balance != null && balance.getText() != null && balance.getText().startsWith("¥");
 * }))
 * }</pre>
 *
 * <p>只能在 Java DSL 里使用；JSON 流程要用 {@code Apex.registerMatcher} 注册的 {@link CustomMatcher}。
 * 在引擎线程上每一轮都可能调用多次，不要做耗时操作或修改界面。抛出异常时流程以 ERROR 结束。
 */
@FunctionalInterface
public interface ScreenCondition {

    /**
     * @return 当前屏幕是否符合条件
     */
    boolean test(ScreenSnapshot screen);
}
