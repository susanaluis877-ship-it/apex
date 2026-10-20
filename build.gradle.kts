// 根工程只声明插件版本，具体模块各自 apply
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}
