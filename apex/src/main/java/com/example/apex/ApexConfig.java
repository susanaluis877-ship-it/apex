package com.example.apex;

import java.util.concurrent.Executor;

/**
 * SDK 配置，通过 {@link Builder} 创建，创建后不可修改。
 */
public final class ApexConfig {

    private final boolean debug;
    private final Executor executor;

    private ApexConfig(Builder builder) {
        this.debug = builder.debug;
        this.executor = builder.executor;
    }

    /**
     * 是否输出调试日志。
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * 宿主注入的后台线程池；为 null 时 SDK 使用自己的单线程。
     */
    public Executor getExecutor() {
        return executor;
    }

    public static final class Builder {

        private boolean debug;
        private Executor executor;

        public Builder setDebug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /**
         * 让 SDK 复用宿主的线程池，避免 SDK 额外创建线程。
         */
        public Builder setExecutor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public ApexConfig build() {
            return new ApexConfig(this);
        }
    }
}
