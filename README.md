# Apex

[![](https://jitpack.io/v/susanaluis877-ship-it/apex.svg)](https://jitpack.io/#susanaluis877-ship-it/apex)

一个纯 Java 的 Android SDK，无任何三方依赖，minSdk 21。

## 接入

在宿主工程的 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

在模块的 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.susanaluis877-ship-it:apex:0.1.0")
}
```

## 使用

```java
// Application.onCreate()
Apex.init(this, new ApexConfig.Builder().setDebug(BuildConfig.DEBUG).build());

// 回调在主线程执行
Apex.ping(new ApexCallback() {
    @Override
    public void onSuccess(String result) { }

    @Override
    public void onError(int code, String message) { }
});
```

## 本地构建

需要 JDK 17+ 和 Android SDK（platform 36）。

```bash
./gradlew :apex:test                     # 单元测试
./gradlew :apex:assembleRelease          # 生成 AAR
./gradlew :demo:installDebug             # 安装 demo 宿主
```

## 发布新版本

1. 修改 `gradle.properties` 中的 `VERSION_NAME`。
2. 提交并推送一个与版本号同名的 tag，例如 `0.1.0`（不要加 `v` 前缀，tag 名会直接作为版本号和 `Apex.getVersion()` 的返回值）。
3. 打开 https://jitpack.io/#susanaluis877-ship-it/apex ，对该 tag 点击 "Get it" 触发构建，并查看构建日志。

## 许可证

[MIT](LICENSE)
