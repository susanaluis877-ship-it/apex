# CLAUDE.md

Apex 是基于 AccessibilityService 的 Android 自动化点击 SDK，用状态图驱动，通过 JitPack 发布（`com.github.susanaluis877-ship-it:apex`）。面向用户的用法写在 README.md；这里记录开发约定、内部设计和踩过的坑。

## 常用命令

```bash
./gradlew :apex:test                   # JVM 单测：引擎、匹配、解析、轨迹
./gradlew :apex:lintRelease            # 会查出 minSdk 24 上不可用的 API（NewApi）
./gradlew :apex:assembleRelease        # AAR
./gradlew :demo:assembleRelease        # demo 开了 R8，用来验证 consumer-rules.pro（目前不需要任何规则）
./gradlew :apex:publishToMavenLocal    # JitPack 执行的就是这个任务；检查 POM 里没有 <dependencies>
python3 -m unittest discover -s plugins/apex-flow/skills/apex-flow/scripts/tests   # apex.py 的单测
claude plugin validate ./              # 检查插件市场和插件清单
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
| `internal/engine/` | `FlowEngine`、`ActionRunner`、`Settler`、`Tracer`、`Fingerprint`、`EventSignal`/`RunController`，以及 `ScreenSource`、`ActionExecutor`、`Clock`、`Waiter` 这些接口 | ✅ |
| `internal/devtools/` | 调试桥的协议和逻辑：`Dispatcher`、`DevtoolsSession`、`FlowCheck`、`SnapshotJson`、`Backend` 接口 | ✅ |
| `internal/android/` | `AccessibilityScreenSource`、`AndroidActionExecutor`、`GestureDispatcher`、`ActivityTracker`、`DisplayInfo` | Android |
| `internal/` | `ApexCore`（运行槽位、引擎线程、服务引用）、`DevBridge`（调试桥的 socket 和 Android 实现）、`ApexLog`、`LogcatLogger`、`Registry`、`ScreenDumper` | — |
| `service/` | `ApexAccessibilityService`：只把自己交给 `ApexCore`，把事件转成唤醒信号 | Android |

`PurityTest` 保证标 ✅ 的包不 import `android.*`。这样引擎能在 JVM 上用假设备完整测试。

仓库里另有 Claude Code 插件（不进 AAR）：

| 路径 | 内容 |
|---|---|
| `.claude-plugin/marketplace.json` | 仓库本身是插件市场，名字 `apex` |
| `plugins/apex-flow/` | 插件：`.mcp.json`（MCP 服务）、`hooks/`（拦截 uiautomator 和 force-stop 的钩子）、`skills/apex-flow/` |
| `skills/apex-flow/SKILL.md`、`references/` | 给 AI 的工作方法和流程格式速查 |
| `skills/apex-flow/scripts/apex.py` + `apexlib/` | 电脑端工具，CLI 和 MCP 共用 `apexlib/tools.py`；只用 Python 标准库 |

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
- `scroll`：方向动作（up/down/left/right）失败时先换成 forward/backward，再不行在节点范围内滑动。ColorOS 的 RecyclerView 只支持 forward/backward。
- `scrollTo`：每次滚动后等 350 ms 再抓快照查找；用 `Fingerprint.ofAll` 判断滚到底，因为滚轮选择器的列是不可见的虚拟节点，`Fingerprint.of` 看不到它们的变化。
- `setProgress`：AOSP 的 SeekBar 在值不变时让 `ACTION_SET_PROGRESS` 返回 false，所以已经到目标值就直接算成功；不支持直接设值时按比例位置点击。
- 滚动前先看节点的 `canScrollForward/Backward`（来自 `getActionList()`）：节点报告了能滚的方向、但不包括要滚的方向时，直接报「已到头」，不再滑动。`scrollTo` 另外用 `Fingerprint.content`（不含位置）判断到头，因为 ColorOS 列表到头时会回弹，节点位置还在变。
- 坐标兜底（点击退到坐标、滑块按比例点、滚动退到滑动）在目标中心不在屏幕内时返回软失败：引擎在页面滑入动画中途就可能进入新状态，这时节点还在屏幕外，按坐标会点到屏幕边缘。
- 选择器的 `stateDesc` / `progressAtLeast` / `progressAtMost` 是状态过滤（不算“身份”条件），比例由 float 计算，留了 1e-4 的余量。
- 关系条件 `hasDescendant`（可多个，各自可以由不同子孙满足）和 `inside`（任一祖先匹配）排在条件列表最后：它们要遍历子树或祖先，先让便宜的条件把大部分节点排除。用来表达“同一个列表项里同时有 A 和 B”，验收 T2 时发现两个独立的 `has` 会误判。

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

## 调试桥和 AI 工具链

目标是让 AI 只凭用户的一句描述，在真机上自己探索目标 App、写出流程并验证。设计和取舍见 `docs/plans/ai-devtools.md`。

**设备端（SDK 里）：**

- `DevBridge` 在抽象 socket `apex_<包名>` 上监听，电脑上用 `adb forward tcp:N localabstract:apex_<包名>` 连接。只在 debuggable 包的主进程、且 `ApexConfig.isDevtools()`（默认 true）时启动。用 `getPeerCredentials` 只接受 uid 0、2000 和本应用：Android 7~8 上其他应用也能连到抽象 socket。不需要 INTERNET 权限，也不改 manifest，所以 JitPack 仍然只发布一个 AAR。
- 协议：每个连接一行 JSON 请求，一行或多行 JSON 响应（`Dispatcher` 的注释）。命令：info、snapshot、find、act、validate、check、run、cancel。`run` 流式推送轨迹，写失败（客户端断开）就取消运行。
- 屏幕操作都提交到 `apex-engine` 线程；有流程在跑时返回 BUSY。`DevtoolsSession` 整个调试桥共用一个 `AccessibilityScreenSource`，所以快照序号单调递增。
- 抓快照时等到相隔 200 ms 的两次快照一致才返回：页面切换动画期间没有无障碍事件，节点位置还在变（实测 ColorOS 设置页滑入时 x 坐标在 1080 以外）。
- `act` 走真实的 `ActionRunner.runOnce`，不包装成单状态流程：终止状态里动作软失败也会报成功，还会触发全局拦截器。
- 选择器建议放在电脑端（规则经常调整，不想跟着宿主发版），设备端只用真实的 `Selector` 验证。
- `flowRef`（`类名#静态方法`）：`FlowRefs` 用宿主的 ClassLoader 反射调用，构建时的 `FlowValidationException` 原样抛出，由 `validate` 转成带路径的问题列表。这样 AI 可以 DSL 优先：改代码 → `deploy` → 按名字 run/check，不用先转 JSON。
- `export`：`FlowWriter.toJson` 写 JSON 并用 `FlowParser.parse(json).equals(flow)` 确认无损。matcher 自己写 JSON（`ScreenMatcher.toJson`，包内方法），`AllOf` 在子条件键顺序和解析器一致时合并成一个对象，否则写成 `allOf` 数组；内联代码抛 `IllegalArgumentException`。

**电脑端（`apex.py`）：**

- 两种模式：调试桥能用且服务已连接时走 Apex；否则（宿主没装、服务没开）`observe` / `act` 自动改用 adb 模式（`adbmode.py`：`uiautomator dump` 转成同样的快照 JSON，`input` 操作）。服务“已开启但没连接”时先等 5 秒再决定，刚安装或刚开启时用 uiautomator 反而会让服务断开。
- `setup`：编译安装（安装期间用 adb 模式盯着安装器界面点「继续安装」，遇到密码框就报告给人）→ `settings put`（追加，不覆盖已开启的服务）→ 被拒绝时在设置界面里开：先试 `ACCESSIBILITY_DETAILS_SETTINGS`（ColorOS 上 shell 没权限），再从无障碍设置列表里按服务名找。服务名从已安装 APK 的 `apex_accessibility_label` 资源读（aapt2）。界面操作写成“每轮看屏幕上有什么就处理什么”的循环；只碰写着服务名的那一行或标题是服务名的页面上的开关，只处理提到服务名的确认弹窗——曾经因为条件太宽，在无障碍列表页上误开了「按电源键结束通话」「锁屏快捷启用」。
- `deploy`：`gradlew :module:assembleDebug` → `adb install -r -t` → 等新进程、调试桥和服务就绪。demo 上一轮约 2~4 秒。

- 页面结构：只列可操作元素（带 `@handle`、类型、标签、状态、中心坐标）和不属于任何可操作元素的文本；没有文本的可点击容器用子孙文本拼标签。标为不可见但可操作、有文本的节点（滚轮选择器的列）也列出来。
- 页面身份（P1、P2…）：包名、弹窗状态、选中的标签页（顶部或底部 25% 区域里 selected 的短文字；选中的标签常常不可点击）都要相同；两边都有标题时只看标题和 Activity，否则按 Activity、id 集合和短文本集合的相似度，阈值 0.6。同一页面内容变化较大时另存样本 `P3.2`…（每页最多 5 份）。
- 页面标题：窗口标题出现在顶部区域时用它（ColorOS 时钟的窗口标题都是「时钟」，不在页面上，所以不用），否则取顶部区域第一段不可点击的短文字。
- 选择器建议同时数上不可见的同样节点：同一个 Activity 的其他标签页里常有同 id 的按钮，换个标签时会点错。
- 截图：`adb exec-out screencap` 取原始 RGBA，抽样缩到约 540 宽，每个通道保留高 4 位，再用 zlib 编码 PNG；超过 80 KB 继续缩小（MCP 工具结果有大小限制）。
- 探索记录在 `.apex/sessions/<时间>/`：`journal.jsonl`、每个新页面的样本 `pages/P*.json`、截图。

**验收方法（改了 skill 或工具后照这个重跑）：**

- 用全新的通用子代理模拟「只装了插件的新会话」：只给一句需求和 SKILL.md 的路径，说明 `${CLAUDE_SKILL_DIR}` 的实际路径、命令行用法、`ANDROID_SERIAL` 和单独的 `APEX_HOME`；禁止读 `docs/examples`、其他会话目录和 SDK 源码（宿主模块可以读写）。
- 要求它汇报：交付物、每次运行结果、迭代轮数、工具调用量、**工具和 skill 的具体问题**、手机最后的状态。每轮反馈修完再跑下一个任务。
- skill 和参考文档里的例子不要用验收任务本身的场景（会泄题）。
- 测试会改手机状态：开始前记录原值（亮度、深色模式、闹钟列表），结束后恢复；自动操作系统设置时前后各做一次 `settings list secure/system/global` 对比。
- 最后在 runner 里点「运行」跑一遍交付的流程，确认不依赖 AI 和电脑也能跑。

## 测试

- 单测开了 `unitTests.isReturnDefaultValues = true`，`android.util.Log` 之类的调用会直接返回默认值。正因为如此，需要 `PurityTest` 防止纯 Java 的包误用 Android API。
- `FakeDevice` 同时实现屏幕、执行器、时钟和等待器。时间只在等待或休眠时推进，所以测试瞬间跑完、结果确定。用 `at`/`after` 安排页面变化，用 `onClick` 定义点击效果，用 `log` 断言执行过的操作。
- `testutil/Trees` 用来构造节点树和快照。
- Maven 版 org.json 和 Android 版行为不同：显式 null、key 顺序都不一样。所以解析器只用 `opt()` 加 `instanceof` 判断，不依赖 key 顺序。
- `FlowEngineTest` 里有自己的 `run(Flow)` 方法，会盖住静态导入的 `Actions.run`，测试里要写成 `Actions.run(...)`。
- `FakeDevice` 是 public 的，`internal/devtools` 的测试也用它；`DispatcherTest` 里的假 `Backend` 在调用线程上同步执行一切。
- `apex.py` 的单测不需要设备：用手写的快照 JSON 测页面整理和选择器候选，用本地 socket 测协议，用 StringIO 测 MCP。

## Demo 与真机调试

- **固定 id。** demo 页面用 `res/values/ids.xml` 里的固定 id；`generateViewId()` 生成的 id 拿不到资源名，选择器匹配不到。
- **自动运行参数。** `autorun`（`flow`/`json`/`dsl`/`open`/`dump`）、`flow`、`ad`、`soldOut`、`loseFirstClick`、`cancelAfterMs`，用法见 README 的 Demo 一节。
- **流程运行器。** 主页列出 `CodeFlows` 里登记的 Java DSL 流程、`assets/flows/` 和 `files/flows/`（`apex.py push`，用 `run-as` 写入）里的 JSON 流程；manifest 声明了 MAIN/LAUNCHER 的 `<queries>`，`launchApp` 才能拉起其他应用。
- **ColorOS（已在 OPPO 真机上确认）：**
  - adb 不能执行 `settings put secure`，服务要在手机上手动开启。
  - **强制停止会把服务从已开启列表里移除**（这是 AOSP 的 `onPackagesForceStoppedLocked`，所有机型都一样），脚本里不要用 `am force-stop`。重置页面用 `am start -f 0x10008000`（NEW_TASK|CLEAR_TASK）。
  - adb 也不能 `pm grant` 运行时权限，要在界面上授予。
  - 首次 `adb install` 要在手机上确认，确认前命令会一直等着；之后的 `install -r` 一般直接成功。
  - `adb install -r` 覆盖安装不会关掉服务；卸载后系统设置里还留着服务名，重新安装后才清掉。
  - 首次 `adb install` 会弹「继续安装」确认（不要密码），`setup` 会在 adb 模式下点掉。
  - 无障碍服务的详情页 shell 没权限直接打开（`ACCESSIBILITY_DETAILS_SETTINGS` 要 `OPEN_ACCESSIBILITY_DETAILS_SETTINGS`），只能从无障碍设置列表里按服务名进去；开启时弹「要允许…完全控制你的设备吗？」，点「允许」。
  - `launchApp` 的落点：设置回到首页但保留列表滚动位置；时钟停在上次的标签页，新建面板会被关掉，多选模式会保留。
  - 时钟新建闹钟需要「音乐和音频」权限，被拒绝两次后只能去应用权限页开。时间滚轮是标成不可见的虚拟节点，文字形如「07小时选择器项目…」（补零）。
  - 屏幕 30 秒没触摸会变暗（DIM），这时亮度类读数变成 0；节点点击不算触摸。
- **多台设备。** `gradlew installDebug` 会装到所有连着的设备上。指定设备用 `adb -s <serial> install -r ...`，或者设置 `ANDROID_SERIAL`。

## 发布

1. 修改 `gradle.properties` 的 `VERSION_NAME`（注释里的坐标也一起改）和 README 里的依赖版本。调试桥协议或插件有变化时，同步 `plugin.json` 的 `version` 和 `apexlib/mcp.py` 的 `SERVER_VERSION`；协议不兼容时两边的 `PROTOCOL` 一起加 1。
2. 跑 test、lint 和 `publishToMavenLocal`。
3. 在 `feat/...` 分支上提交，推送分支。
4. 打 annotated tag，名字和版本号完全一致，推送 tag。
5. 请求一次 `https://jitpack.io/com/github/susanaluis877-ship-it/apex/<版本>/apex-<版本>.pom` 触发构建，过几分钟再查 `https://jitpack.io/api/builds/com.github.susanaluis877-ship-it/apex/<版本>`。不要频繁轮询。构建完成前状态一直是 `none`，排队可能要好几分钟。
6. 构建 `ok` 并确认产物都能下载后，把 `main` 快进到 tag，然后删掉功能分支。不要用 squash 合并，否则 tag 指向的提交不在 `main` 上。
