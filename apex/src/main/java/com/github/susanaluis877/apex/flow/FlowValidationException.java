package com.github.susanaluis877.apex.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 流程定义不合法，包含所有发现的问题。构建期异常，说明流程写错了，运行时不会抛出。
 */
public class FlowValidationException extends IllegalArgumentException {

    private final List<Problem> problems;

    FlowValidationException(List<Problem> problems) {
        super(format(problems));
        this.problems = Collections.unmodifiableList(new ArrayList<>(problems));
    }

    public List<Problem> getProblems() {
        return problems;
    }

    private static String format(List<Problem> problems) {
        StringBuilder sb = new StringBuilder("invalid flow:");
        for (Problem p : problems) {
            sb.append("\n  ").append(p);
        }
        return sb.toString();
    }

    /**
     * 一条校验问题。{@code ordinal} 是状态或拦截器在声明顺序里的下标，{@code index} 是字段内数组的下标。
     */
    public static final class Problem {

        public static final String FLOW = "flow";
        public static final String STATE = "state";
        public static final String INTERCEPTOR = "interceptor";

        private final String scope;
        private final int ordinal;
        private final String id;
        private final String field;
        private final int index;
        private final String message;

        Problem(String scope, int ordinal, String id, String field, int index, String message) {
            this.scope = scope;
            this.ordinal = ordinal;
            this.id = id;
            this.field = field;
            this.index = index;
            this.message = message;
        }

        /** {@link #FLOW}、{@link #STATE} 或 {@link #INTERCEPTOR}。 */
        public String getScope() {
            return scope;
        }

        /** 状态或拦截器的声明下标；scope 为 flow 时为 -1。 */
        public int getOrdinal() {
            return ordinal;
        }

        public String getId() {
            return id;
        }

        /** 出问题的字段名（与 JSON 键名一致），可能为 null。 */
        public String getField() {
            return field;
        }

        /** 字段内数组下标，不适用时为 -1。 */
        public int getIndex() {
            return index;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(scope);
            if (id != null) {
                sb.append(" '").append(id).append('\'');
            }
            if (field != null) {
                sb.append('.').append(field);
                if (index >= 0) {
                    sb.append('[').append(index).append(']');
                }
            }
            return sb.append(": ").append(message).toString();
        }
    }
}
