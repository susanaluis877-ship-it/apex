package com.github.susanaluis877.apex.action;

import java.util.Map;

/**
 * 宿主自定义的动作，通过 {@code Apex.registerAction(name, action)} 注册后，
 * 在 DSL 里用 {@link Actions#custom(String)} 或在 JSON 里用 {@code {"type": "custom", "name": ...}} 引用。
 *
 * <p>在引擎线程上同步执行，可以阻塞，但应定期检查 {@link ActionContext#isCancelled()}。
 */
public interface CustomAction {

    /**
     * @param args 流程定义里写的参数，不可修改；没有参数时为空 Map
     * @return true 表示成功；false 表示软失败（例如目标还没出现），引擎会按重试规则稍后再试
     * @throws Exception 抛出异常时整个流程以 ERROR 结束
     */
    boolean perform(ActionContext context, Map<String, String> args) throws Exception;
}
