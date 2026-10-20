package com.github.susanaluis877.apex.internal.engine;

/**
 * 单调时钟，单位毫秒。测试里换成可手动推进的假时钟。
 */
public interface Clock {

    Clock SYSTEM = () -> System.nanoTime() / 1_000_000;

    long now();
}
