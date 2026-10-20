package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.node.UiNode;

/**
 * 设备操作原语。节点参数必须来自 {@link ScreenSource} 最新一次返回的快照。
 *
 * <p>目标查找、可点击祖先、手势兜底这些逻辑在 {@link ActionRunner} 里，这里只做单个操作。
 */
public interface ActionExecutor {

    ActionResult clickNode(UiNode node, boolean longClick);

    ActionResult scrollNode(UiNode node, Action.Direction direction);

    ActionResult setText(UiNode node, String text);

    /** 从 (x1, y1) 移动到 (x2, y2) 的单指手势；两点相同时就是点击或长按。 */
    ActionResult gesture(int x1, int y1, int x2, int y2, long durationMs);

    ActionResult global(Action.GlobalAction kind);

    ActionResult launchApp(String packageName);
}
