package com.github.susanaluis877.apex.action;

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

/**
 * 提供给 {@link CustomAction} 的执行环境。
 */
public interface ActionContext {

    /** 当前快照。 */
    ScreenSnapshot screen();

    /** 重新抓取一次快照并作为新的当前快照返回；服务已断开时返回 null。 */
    ScreenSnapshot refresh();

    /** 在当前快照上执行一个内置动作，例如 {@code perform(Actions.click(By.text("OK")))}。成功返回 true。 */
    boolean perform(Action action);

    /** 点击当前快照里的某个节点（按 {@link Action.ClickMode#AUTO} 方式）。成功返回 true。 */
    boolean click(UiNode node);

    /** 等待一段时间，流程被取消时提前返回。 */
    void sleep(long ms);

    boolean isCancelled();
}
