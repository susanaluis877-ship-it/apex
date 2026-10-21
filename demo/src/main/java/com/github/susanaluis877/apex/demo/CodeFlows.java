package com.github.susanaluis877.apex.demo;

import com.github.susanaluis877.apex.demo.flows.ClockFlows;
import com.github.susanaluis877.apex.flow.Flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 用 Java DSL 写在代码里的流程，流程运行器的列表里会显示它们。
 *
 * <p>新流程写成一个返回 {@link Flow} 的静态方法（例如放在 {@code flows/} 包里），再在 {@link #ALL} 里登记一行。
 * 调试时可以用 {@code apex.py run com.github.susanaluis877.apex.demo.flows.XxxFlows#method} 直接运行，不用登记。
 */
final class CodeFlows {

    /** 名称 → 流程。按登记顺序显示。 */
    static final Map<String, Supplier<Flow>> ALL = new LinkedHashMap<>();

    static {
        // ALL.put("示例", ExampleFlows::example);
        ALL.put("时钟：新建工作日 07:30 闹钟 Apex", ClockFlows::addWorkdayAlarm);
    }

    private CodeFlows() {
    }

    static List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(ALL.keySet()));
    }
}
