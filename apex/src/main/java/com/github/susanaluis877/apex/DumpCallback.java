package com.github.susanaluis877.apex;

import com.github.susanaluis877.apex.node.ScreenSnapshot;

/**
 * {@link Apex#dumpScreen} 的结果回调，在主线程执行。
 */
public interface DumpCallback {

    /**
     * @param snapshot 当前屏幕；服务未连接或有流程正在运行时为 null
     * @param text     便于阅读的节点树文本，用来编写选择器；不可用时是原因说明
     */
    void onDump(ScreenSnapshot snapshot, String text);
}
