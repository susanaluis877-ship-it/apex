package com.github.susanaluis877.apex.action;

/**
 * 用代码写的动作，通过 {@link Actions#run(ActionFunction)} 直接放进 {@code action(...)}，不需要注册名字：
 *
 * <pre>{@code
 * .action(run(ctx -> {
 *     for (UiNode node : By.text("领取").findAll(ctx.screen())) {
 *         if (ctx.isCancelled() || !ctx.click(node)) {
 *             return false;
 *         }
 *         ctx.sleep(300);
 *     }
 *     return true;
 * }))
 * }</pre>
 *
 * <p>只能在 Java DSL 里使用；JSON 流程要用 {@code Apex.registerAction} 注册的 {@link CustomAction}。
 * 在引擎线程上同步执行，可以阻塞，但应定期检查 {@link ActionContext#isCancelled()}。
 */
@FunctionalInterface
public interface ActionFunction {

    /**
     * @return true 表示成功；false 表示软失败（例如目标还没出现），引擎会按续跑和重试规则稍后再试
     * @throws Exception 抛出异常时整个流程以 ERROR 结束
     */
    boolean perform(ActionContext context) throws Exception;
}
