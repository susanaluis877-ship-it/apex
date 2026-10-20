# CLAUDE.md

Apex 是基于 AccessibilityService 的 Android 自动化点击 SDK，用状态图驱动，通过 JitPack 发布（`com.github.susanaluis877-ship-it:apex`）。面向用户的用法写在 README.md；这里记录开发约定、内部设计和踩过的坑。

## 常用命令

```bash
./gradlew :apex:test                   # JVM 单测：引擎、匹配、解析、轨迹
./gradlew :apex:lintRelease            # 会查出 minSdk 24 上不可用的 API（NewApi）
./gradlew :apex:assembleRelease        # AAR
./gradlew :demo:assembleRelease        # demo 开了 R8，用来验证 consumer-rules.pro（目前不需要任何规则）
./gradlew :apex:publishToMavenLocal    # JitPack 执行的就是这个任务；检查 POM 里没有 <dependencies>
```

**JDK。** Gradle 守护进程的 JDK 由 `gradle/gradle-daemon-jvm.properties` 固定为 21，`JAVA_HOME` 和 `-Dorg.gradle.java.home` 都覆盖不了它。

- Android Studio 曾把它改成 25，结果 Gradle 选中了 VS Code 插件自带的精简 JRE（没有 jlink），AGP 构建失败。
- 这个文件里没有 `toolchainUrl`，因为项目没配工具链下载仓库，`updateDaemonJvm` 也跑不了。
- `jitpack.yml` 用 `openjdk21`，必须和这个文件保持一致。

## 硬约束

- **依赖。** 纯 Java，没有三方运行时依赖。测试依赖可以加（junit、org.json）。
- **构建配置。**
  - `enableKotlin = false`，否则 AGP 9 会往 POM 里加 kotlin-stdlib。
  - 资源名必须带 `apex_` 前缀。
  - minSdk 24，Java 11 源码。用到更新的 API 时 lint 会报错，例如 `BigInteger.longValueExact` 要求 API 31。
- **异常。**
  - 运行期的公开 API（`Apex.*`）不向宿主抛异常，全部 catch 后记日志或走回调。
  - 定义期例外：`Flow.Builder.build()` 抛 `FlowValidationException`，`FlowParser.parse()` 抛 `FlowParseException`，都带具体位置或 JSON 路径。
- **服务类名不能改。** `com.github.susanaluis877.apex.service.ApexAccessibilityService` 会被持久化到系统设置 `enabled_accessibility_services`，改名会让所有用户已开启的服务失效。
- **版本号。** tag 名就是版本号，不加 `v`。JitPack 把它注入为 `VERSION`，`Apex.getVersion()` 返回的就是它。
- **注释风格。** 注释用中文，风格和周围代码一致。

## 代码结构

包根是 `com.github.susanaluis877.apex`。

| 包 | 内容 | 纯 Java |
|---|---|---|
| 根包 | `Apex` 门面、`ApexConfig`、`ApexLogger`、`RunResult`、`TraceEvent`、`FlowListener`、`RunHandle`、`DumpCallback` | — |
| `flow/` | `Flow`、`State`、`Interceptor`、`FlowDefaults`，Builder 和校验，`FlowParser` | ✅ |
| `node/` | `UiNode`、`UiWindow`、`Bounds`、`ScreenSnapshot`：快照模型 | ✅ |
| `match/` | `By`/`Selector`（节点）、`Match`/`ScreenMatcher`（页面）、`ScreenCondition`、`CustomMatcher` | ✅ |
| `action/` | `Action`（不可变值对象）、`Actions`、`ActionContext`、`ActionFunction`、`CustomAction` | ✅ |
| `internal/engine/` | `FlowEngine`、`ActionRunner`、`Tracer`、`Fingerprint`、`EventSignal`/`RunController`，以及 `ScreenSource`、`ActionExecutor`、`Clock`、`Waiter` 这些接口 | ✅ |
| `internal/android/` | `AccessibilityScreenSource`、`AndroidActionExecutor`、`GestureDispatcher`、`ActivityTracker`、`DisplayInfo` | Android |
| `internal/` | `ApexCore`（运行槽位、引擎线程、服务引用）、`ApexLog`、`LogcatLogger`、`Registry`、`ScreenDumper` | — |
| `service/` | `ApexAccessibilityService`：只把自己交给 `ApexCore`，把事件转成唤醒信号 | Android |

`PurityTest` 保证标 ✅ 的包不 import `android.*`。这样引擎能在 JVM 上用假设备完整测试。

## 引擎设计（`FlowEngine`）

**每一轮的步骤：**

1. 等界面安静（`settle`）：没有事件时最多等 500 ms；收到事件后等到 200 ms 没有新事件，最长等 1500 ms。
2. 抓快照。抓不到时给服务 3 秒宽限，之后以 `SERVICE_UNAVAILABLE` 结束。快照被标记为不完整时跳过这一轮。
3. 检查拦截器，按声明顺序取第一个匹配的。
   - 次数用完的拦截器视为不存在。
   - 匹配但还在冷却中时，这一轮什么都不做，免得在弹窗关闭动画期间点到下面的内容。
   - 拦截器花掉的时间会补加到状态超时上。
4. 检查候选状态，按 `next` 的顺序取第一个匹配的。进入状态时 steps 加 1，超过 maxSteps 就以 `MAX_STEPS` 结束。
5. 都没有进展：
   - 当前状态还匹配、且有软失败的动作：页面指纹变了，或者过了 `retryIntervalMs`，就从失败的动作续跑。页面没变时续跑只会得到同样的失败，也会刷屏轨迹。
   - 否则，页面指纹在 `retryIntervalMs` 内没变就重跑全部动作，最多 `maxRetries` 次。
   - 当前状态和候选状态都不匹配时，只等待。
6. 超过状态 deadline，以 `TIMEOUT` 结束。消息里带每个候选状态的 `explainMismatch` 和页面文本。

**其他规则：**

- `Fingerprint` 只看可见节点的类名、id、文本、描述、位置和 checked/enabled/selected。
- 动作列表里，相邻动作之间会重新抓快照，并先处理拦截器。
- `ActionResult` 有四种：
  - OK
  - SOFT_FAIL：之后续跑。
  - HARD_FAIL：以 `ACTION_FAILED` 结束；`optional` 的动作会跳过它。
  - ERROR / CANCELLED：总是结束流程。
- 等价性：`Match.allOf`/`anyOf` 会展开嵌套，`has(a, b)` 等于 `allOf(has(a), has(b))`，JSON 里 matcher 的键按固定顺序组合。这样 DSL 和 JSON 写出的流程 `equals`，由 `DslJsonEquivalenceTest` 保证。
- 内联的 `Match.custom(fn)`、`Actions.run(fn)` 按函数对象的身份判等。按名字引用的自定义条件和动作走 `Registry`，构建流程时就校验是否已注册。
- 全局拦截器：
  - 用 `Interceptor.create` 单独构建。它和流程里的拦截器共用 `Flow.Builder.checkInterceptor` 校验；没设置的 maxTimes/cooldown 用 SDK 默认值，不用流程的 defaults。
  - 存在 `Registry` 里，按注册顺序保存，同 id 替换。
  - `ApexCore` 每次运行开始时取一次快照传给 `FlowEngine`，再由 `Flow.effectiveInterceptors` 合并：流程自己的在前，全局的在后，同 id 用流程的，并去掉被排除的。
  - 轨迹里全局拦截器标为 `(global)`。
- `Selector` 的条件只有一份定义，`matches` 和 `explainMismatch` 共用。“差一个条件”的判断要求节点至少命中一个文本、id 或类名条件，否则几乎所有节点都只差那一个条件。

**轨迹（`Tracer`）：**

- 最多 1000 条，保留开头 100 条和最近 900 条。
- WAIT 只在页面指纹变化时记录，页面不变时每 5 秒记一次。
- VERBOSE 只进日志，不进轨迹。
- 以 TIMEOUT、MAX_STEPS、ACTION_FAILED、SERVICE_UNAVAILABLE、ERROR 结束时，附上 SCREEN_DUMP。
- 日志默认级别 WARN，debug 模式下为 DEBUG。

## Android 层

- **只用一条常驻的 `apex-engine` 线程。** 框架里的 `AccessibilityInteractionClient` 按线程 id 缓存连接对象，而且从不移除，每次运行新建线程就会泄漏。所有节点 IPC 都在这条线程上做，绝不在主线程查询节点。demo 的目标页面和 SDK 在同一个进程里，主线程被占住会卡死。
- **快照。**
  - 用 `getWindows()` 取 `TYPE_APPLICATION` 窗口，按 layer 从高到低排；拿不到时退回 `getRootInActiveWindow()`。
  - 节点树拷贝成 `UiNode`，同时用 `IdentityHashMap` 记住每个 `UiNode` 对应的原始节点，只保留最近一次快照。旧快照里的节点执行动作时返回软失败，不会点错位置。
  - 执行动作前先 `refresh()`。从不调用 `recycle()`。
  - 上限：3000 个节点、深度 64、耗时 3 秒。
- **事件。** `onAccessibilityEvent` 只记录类型、包名和类名，然后唤醒引擎；不持有 event 对象。服务配置里 `notificationTimeout=50`。
- **手势。** `dispatchGesture` 的 handler 传 null，回调落在主线程，引擎线程用 latch 等结果，超时为手势时长加 2 秒。`onCancelled` 算软失败。
- **Activity 识别。** `ActivityTracker` 按类名启发式过滤 Dialog、PopupWindow 等窗口类。事件有节流，所以 activity 可能比快照里的内容晚一拍。
- **模态对话框。** 弹出模态对话框时，系统只上报对话框这一个窗口，下面的 Activity 窗口不在 `getWindows()` 里。
- **启动其他 App。** `launchApp` 用服务的 context 加 `FLAG_ACTIVITY_NEW_TASK`；无障碍服务豁免后台启动 Activity 的限制。Android 11 及以上，宿主要声明 `<queries>`。
- **manifest。** 服务声明为 `exported="false"` 加 `BIND_ACCESSIBILITY_SERVICE` 权限，系统 uid 能正常绑定，已在真机验证。
- **API 废弃。** `AccessibilityNodeInfo.isChecked()` 在 API 36 废弃，但替代方法只有 36 才有，所以继续用它并加了 `@SuppressWarnings`。

## 测试

- 单测开了 `unitTests.isReturnDefaultValues = true`，`android.util.Log` 之类的调用会直接返回默认值。正因为如此，需要 `PurityTest` 防止纯 Java 的包误用 Android API。
- `FakeDevice` 同时实现屏幕、执行器、时钟和等待器。时间只在等待或休眠时推进，所以测试瞬间跑完、结果确定。用 `at`/`after` 安排页面变化，用 `onClick` 定义点击效果，用 `log` 断言执行过的操作。
- `testutil/Trees` 用来构造节点树和快照。
- Maven 版 org.json 和 Android 版行为不同：显式 null、key 顺序都不一样。所以解析器只用 `opt()` 加 `instanceof` 判断，不依赖 key 顺序。
- `FlowEngineTest` 里有自己的 `run(Flow)` 方法，会盖住静态导入的 `Actions.run`，测试里要写成 `Actions.run(...)`。

## Demo 与真机调试

- **固定 id。** demo 页面用 `res/values/ids.xml` 里的固定 id；`generateViewId()` 生成的 id 拿不到资源名，选择器匹配不到。
- **自动运行参数。** `autorun`（`json`/`dsl`/`dump`）、`ad`、`soldOut`、`loseFirstClick`、`cancelAfterMs`，用法见 README 的 Demo 一节。
- **ColorOS（已在 OPPO 真机上确认）：**
  - adb 不能执行 `settings put secure`，服务要在手机上手动开启。
  - **强制停止会把服务从已开启列表里移除**，脚本里不要用 `am force-stop`。重置页面用 `am start -f 0x10008000`（NEW_TASK|CLEAR_TASK）。
  - `adb install -r` 覆盖安装不会关掉服务。
- **多台设备。** `gradlew installDebug` 会装到所有连着的设备上。指定设备用 `adb -s <serial> install -r ...`，或者设置 `ANDROID_SERIAL`。

## 发布

1. 修改 `gradle.properties` 的 `VERSION_NAME`（注释里的坐标也一起改）和 README 里的依赖版本。
2. 跑 test、lint 和 `publishToMavenLocal`。
3. 在 `feat/...` 分支上提交，推送分支。
4. 打 annotated tag，名字和版本号完全一致，推送 tag。
5. 请求一次 `https://jitpack.io/com/github/susanaluis877-ship-it/apex/<版本>/apex-<版本>.pom` 触发构建，过几分钟再查 `https://jitpack.io/api/builds/com.github.susanaluis877-ship-it/apex/<版本>`。不要频繁轮询。构建完成前状态一直是 `none`，排队可能要好几分钟。
6. 构建 `ok` 并确认产物都能下载后，把 `main` 快进到 tag，然后删掉功能分支。不要用 squash 合并，否则 tag 指向的提交不在 `main` 上。
