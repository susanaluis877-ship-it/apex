package com.github.susanaluis877.apex;

/**
 * SDK 配置，通过 {@link Builder} 创建，创建后不可修改。
 */
public final class ApexConfig {

    private final boolean debug;

    private ApexConfig(Builder builder) {
        this.debug = builder.debug;
    }

    /**
     * 是否输出调试日志（每一轮的状态匹配、动作执行结果等，tag 为 {@code Apex}）。
     */
    public boolean isDebug() {
        return debug;
    }

    public static final class Builder {

        private boolean debug;

        public Builder setDebug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public ApexConfig build() {
            return new ApexConfig(this);
        }
    }
}
