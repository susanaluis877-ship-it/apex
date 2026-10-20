package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.node.ScreenSnapshot;

/**
 * 抓取屏幕快照。
 */
public interface ScreenSource {

    /**
     * @return 当前屏幕；无障碍服务不可用时返回 null
     */
    ScreenSnapshot snapshot();
}
