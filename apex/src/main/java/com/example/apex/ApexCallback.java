package com.example.apex;

/**
 * 异步结果回调，所有回调都在主线程执行。
 */
public interface ApexCallback {

    /** SDK 尚未初始化。 */
    int ERROR_NOT_INITIALIZED = 1;

    /** SDK 内部错误。 */
    int ERROR_INTERNAL = 2;

    void onSuccess(String result);

    void onError(int code, String message);
}
