package com.github.susanaluis877.apex;

/**
 * SDK 配置，通过 {@link Builder} 创建，创建后不可修改。
 */
public final class ApexConfig {

    private final boolean debug;
    private final ApexLogger logger;
    private final Integer logLevel;

    private ApexConfig(Builder builder) {
        this.debug = builder.debug;
        this.logger = builder.logger;
        this.logLevel = builder.logLevel;
    }

    /**
     * 是否为调试模式。调试模式下默认日志级别为 DEBUG，可以看到每一步的状态匹配、动作和等待原因。
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * 日志接收器，默认 {@link ApexLogger#LOGCAT}。
     */
    public ApexLogger getLogger() {
        return logger != null ? logger : ApexLogger.LOGCAT;
    }

    /**
     * 最低输出级别。没有显式设置时，调试模式为 {@link ApexLogger#DEBUG}，否则为 {@link ApexLogger#WARN}。
     */
    public int getLogLevel() {
        if (logLevel != null) {
            return logLevel;
        }
        return debug ? ApexLogger.DEBUG : ApexLogger.WARN;
    }

    public static final class Builder {

        private boolean debug;
        private ApexLogger logger;
        private Integer logLevel;

        public Builder setDebug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /**
         * 接管 SDK 的所有日志，例如写文件或上报。传 null 恢复为 logcat。
         */
        public Builder setLogger(ApexLogger logger) {
            this.logger = logger;
            return this;
        }

        /**
         * 最低输出级别，取 {@link ApexLogger#VERBOSE} ~ {@link ApexLogger#ERROR}。
         * VERBOSE 会额外输出每一轮的快照信息，量较大。
         */
        public Builder setLogLevel(int priority) {
            this.logLevel = priority;
            return this;
        }

        public ApexConfig build() {
            return new ApexConfig(this);
        }
    }
}
