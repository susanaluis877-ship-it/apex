# Apex

[![](https://jitpack.io/v/susanaluis877-ship-it/apex.svg)](https://jitpack.io/#susanaluis877-ship-it/apex)

基于 Android 无障碍服务的自动化点击框架。用**状态图**描述流程：每个状态说明“这是哪个页面”“在这个页面做什么”“接下来应该出现哪个页面”，再用全局**拦截器**处理弹窗、广告这类意外页面。

- 纯 Java，无任何三方依赖，minSdk 24
- 流程既可以用 Java DSL 写，也可以用 JSON 写（可远程下发），两者产出同一个模型
- 支持节点点击、坐标点击/滑动（手势）、滚动、输入文本、返回/Home、启动 App、自定义动作
- 纯 Java 的引擎和匹配逻辑，可以在 JVM 上完整单测

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
    implementation("com.github.susanaluis877-ship-it:apex:0.2.0")
}
```

无障碍服务 `ApexAccessibilityService` 会随 AAR 自动合并进宿主的 manifest，宿主不需要再声明。

## 快速开始

```java
// 1. Application.onCreate()
Apex.init(this, new ApexConfig.Builder().setDebug(BuildConfig.DEBUG).build());

// 2. 引导用户在系统设置里开启无障碍服务
if (!Apex.isServiceEnabled(context)) {
    Apex.openAccessibilitySettings(context);
}

// 3. 定义流程
Flow flow = new Flow.Builder("claim")
        .start("home")
        .state("home", s -> s
                .match(allOf(pkg("com.foo"), has(By.id("tab_task"))))
                .action(click(By.id("tab_task")))
                .next("task", "sold_out"))
        .state("task", s -> s
                .match(has(By.text("领取奖励").enabled(true)))
                .action(click(By.text("领取奖励")))
                .next("done"))
        .state("done", s -> s.match(has(By.textContains("领取成功"))).endSuccess())
        .state("sold_out", s -> s.match(has(By.text("已抢光"))).action(back()).endFailure())
        .interceptor("close_ad", i -> i
                .match(has(By.id("iv_close_ad")))
                .action(click(By.id("iv_close_ad"))))
        .build();

// 4. 运行，回调都在主线程
RunHandle handle = Apex.run(flow, result -> Log.i("Apex", "finished: " + result));
// handle.cancel();
```

`Match.*`、`Actions.*` 建议静态导入。`FlowListener` 还有 `onStateEntered` 和 `onInterceptor` 两个可选回调。

## 核心概念

### 状态

| 字段 | 说明 |
|---|---|
| `match` | 页面匹配条件，判断“当前是不是这个页面” |
| `actions` | 进入状态后依次执行的动作 |
| `next` | 有序的候选下一状态。引擎按顺序检查候选状态的 `match`，第一个匹配的就是下一状态 |
| `end` | `success` 或 `failure`，表示终止状态：执行完动作后结束流程，不能有 `next` |
| `timeoutMs` | 进入状态后等待下一状态出现的最长时间；对起始状态是等它出现的最长时间 |
| `retryIntervalMs` / `maxRetries` | 页面毫无变化时，多久后重跑一次动作、最多几次 |

同一个页面需要走不同分支时，就拆成多个 `match` 互斥的状态，例如上面的 `task` 和 `sold_out`。

### 拦截器

拦截器是全局的：任何时候屏幕匹配它的条件，就先执行它的动作，然后回到被打断的地方继续。它不会让当前状态的动作重跑一遍。

- 每一轮先检查拦截器，再检查候选状态，所以弹窗挡住页面时不会先点到下面的按钮。
- 如果某个弹窗本身就是流程的一步，在拦截器条件里用 `not(...)` 把它排除掉。
- `maxTimes`（默认 5）：一次运行中最多触发几次。用完后这个拦截器就不再检查。
- `cooldownMs`（默认 1500）：两次触发之间的最短间隔。冷却期间这个弹窗如果还在，引擎这一轮什么都不做，以免在关闭动画期间点到下面。

### 引擎每一轮做什么

1. 等界面安静：收到无障碍事件后等 200 ms 没有新事件；没有事件时每 500 ms 主动检查一次。
2. 抓取所有应用窗口的节点快照，包括 Activity、Dialog 和 PopupWindow。
3. 有匹配的拦截器就执行它，然后进入下一轮。
4. 候选状态有匹配的，就进入该状态并执行它的动作。
5. 都没有进展时：
   - 当前状态有动作软失败（例如目标还没出现），从失败的那个动作接着执行。
   - 页面在 `retryIntervalMs` 内毫无变化，重跑当前状态的动作，最多 `maxRetries` 次。页面有任何变化（例如正在加载）都不会重试。
   - 当前状态和候选状态都不匹配（过渡页、动画），只等待。
6. 超过状态超时时间，以 `TIMEOUT` 结束。结果的 `message` 里带当时的包名、Activity 和页面上的文本，方便排查。

动作不能重复执行时（例如下单），把这个状态的 `maxRetries` 设为 0。

### 运行结果

`RunResult.getStatus()` 的取值：

| 状态 | 含义 |
|---|---|
| `SUCCESS` / `FAILURE` | 到达 `end: success` / `end: failure` 的终止状态 |
| `TIMEOUT` | 起始状态没出现、下一状态没出现，或整个流程超时（`timeoutMs`，默认 5 分钟） |
| `MAX_STEPS` | 进入状态的次数超过 `maxSteps`（默认 100），通常是状态之间来回跳转 |
| `ACTION_FAILED` | 动作无法执行，例如没有启动 intent、目标不是输入框 |
| `CANCELLED` | 调用了 `RunHandle.cancel()` |
| `SERVICE_UNAVAILABLE` | 服务未连接，或运行中断开超过 3 秒 |
| `BUSY` / `NOT_INITIALIZED` / `INVALID_ARGUMENT` | 没能开始运行 |
| `ERROR` | 自定义动作或条件抛出了异常 |

除此之外还有 `getPath()`（依次进入的状态）、`getInterceptorCounts()`、`getDurationMs()`、`getMessage()`。

## JSON 格式

下面的 JSON 与 demo 里 `DemoFlows.dsl()` 定义的流程完全等价：

```json
{
  "schema": 1,
  "id": "demo.claim",
  "start": "home",
  "timeoutMs": 60000,
  "maxSteps": 20,
  "defaults": { "timeoutMs": 10000, "retryIntervalMs": 3000, "maxRetries": 2 },
  "states": [
    { "id": "home",
      "match": { "package": "com.github.susanaluis877.apex.demo", "has": { "id": "demo_start" } },
      "actions": [ { "type": "click", "target": { "id": "demo_start" } } ],
      "next": ["task", "sold_out"] },
    { "id": "task",
      "match": { "has": { "id": "demo_claim", "enabled": true } },
      "actions": [ { "type": "click", "target": { "id": "demo_claim" } } ],
      "next": "done", "timeoutMs": 15000 },
    { "id": "done", "match": { "has": { "textContains": "Reward claimed" } }, "end": "success" },
    { "id": "sold_out", "match": { "has": { "text": "Sold out" } },
      "actions": [ { "type": "back" } ], "end": "failure" }
  ],
  "interceptors": [
    { "id": "close_ad",
      "match": { "has": [ { "text": "AD" }, { "id": "demo_ad_close", "clickable": true } ] },
      "actions": [ { "type": "click", "target": { "id": "demo_ad_close" } } ],
      "maxTimes": 3 }
  ]
}
```

```java
try {
    Flow flow = FlowParser.parse(json);
} catch (FlowParseException e) {
    // e.getPath() 例如 "states[1].next[0]"，e.getReason() 例如 "unknown state 'x'"
}
```

解析规则：

- 任何层级出现未知的键都会报错，用来发现拼写错误。
- 值为 `null` 的键视为没写。
- 校验与 `Flow.Builder.build()` 完全相同，报错时指出 JSON 路径。

### 顶层与默认值

| 键 | 默认值 | 说明 |
|---|---|---|
| `id`、`start`、`states` | 必填 | |
| `timeoutMs` | 300000 | 整个流程的超时 |
| `maxSteps` | 100 | 最多进入状态的次数 |
| `maxInterceptions` | 30 | 所有拦截器合计最多触发几次 |
| `interceptors` | 无 | |
| `defaults.timeoutMs` | 10000 | 状态超时 |
| `defaults.retryIntervalMs` | 3000 | 页面无变化多久后重试 |
| `defaults.maxRetries` | 2 | 最多重试几次 |
| `defaults.actionGapMs` | 300 | 同一状态内相邻动作的间隔 |
| `defaults.interceptorMaxTimes` | 5 | 拦截器默认最多触发几次 |
| `defaults.interceptorCooldownMs` | 1500 | 拦截器默认冷却时间 |

状态可以单独写 `timeoutMs`、`retryIntervalMs`、`maxRetries` 覆盖默认值；拦截器可以单独写 `maxTimes`、`cooldownMs`。

### 页面条件 `match`

同一个对象里的多个键是 AND 关系。

| 键 | DSL | 说明 |
|---|---|---|
| `package` | `pkg("com.foo")` | 最上层应用窗口的包名 |
| `activity` | `activity(".ui.HomeActivity")` | Activity 类名，可写全名、`.` 开头的后缀或简单类名。它来自无障碍事件，页面刚切换时可能比节点内容晚一点更新；单 Activity、Compose、Flutter 应用里它也不区分页面。所以只当辅助条件，判断页面优先用 `has` |
| `has` | `has(selector...)` | 存在匹配的节点；写数组时要求每个都存在 |
| `not` | `not(matcher)` | |
| `allOf` / `anyOf` | `allOf(...)` / `anyOf(...)` | 子条件数组 |
| `always` | `always()` | 只能写 `true`，任何屏幕都匹配 |
| `custom` | `Match.custom(name, args)` | `"名字"` 或 `{"name": ..., "args": {...}}` |

### 节点选择器 selector

同一个对象里的多个键也是 AND 关系，DSL 用 `By.xxx(...).yyy(...)`。

| 键 | 说明 |
|---|---|
| `text` / `textContains` / `textMatches` | 文本完全相等 / 包含 / 整段匹配正则 |
| `desc` / `descContains` / `descMatches` | contentDescription，规则同上 |
| `id` | 写短名 `submit` 匹配任意包下的 `:id/submit`，写全名 `com.foo:id/submit` 时精确匹配 |
| `className` | 简单类名 `Button` 或全名 `android.widget.Button` |
| `clickable` / `enabled` / `checked` / `selected` / `scrollable` | 布尔过滤 |
| `visible` | 默认 `true`，只匹配用户可见的节点；写 `false` 时可见和不可见都匹配 |

有多个节点匹配时取第一个，顺序是：窗口从上到下，窗口内先序遍历。

### 动作 `actions`

| `type` | 参数 | DSL |
|---|---|---|
| `click` / `longClick` | `target`，`mode`：`auto`（默认）、`node`、`gesture` | `click(By...)` / `longClick(By...)` |
| `tap` | `x`、`y` 或 `xRatio`、`yRatio`（0~1），可选 `durationMs` | `tap(x, y)` / `tapRatio(0.5, 0.9)` |
| `swipe` | `from`、`to`（`[x, y]`）或 `fromRatio`、`toRatio`，可选 `durationMs` | `swipe(...)` / `swipeRatio(...)` |
| `scroll` | `direction`：`forward`/`backward`/`up`/`down`/`left`/`right`，可选 `target` | `scroll(direction)` / `scroll(By..., direction)` |
| `setText` | `target`、`text` | `setText(By..., "text")` |
| `back` / `home` / `recents` / `notifications` | 无 | `back()` 等 |
| `launchApp` | `package` | `launchApp("com.foo")` |
| `sleep` | `ms` | `sleep(1000)` |
| `custom` | `name`、可选 `args` | `custom(name, args)` |

所有动作都可以加两个参数：

- `optional: true`（DSL `.optional()`）：失败时跳过，继续下一个动作。
- `delayMs`（DSL `.delayMs(ms)`）：执行前先等待。

`click` 的 `auto` 模式会先对目标节点或它最近的可点击祖先执行 `ACTION_CLICK`，不成功再按节点中心坐标手势点击。自绘 View、WebView、Flutter 页面上建议直接用 `mode: gesture`。

## 自定义动作和条件

先注册，再构建或解析引用它们的流程：

```java
Apex.registerMatcher("hasBadge", (screen, args) ->
        By.textMatches("\\d+").findFirst(screen) != null);

Apex.registerAction("tapAllCoupons", (ctx, args) -> {
    for (UiNode node : By.text("领取").findAll(ctx.screen())) {
        if (ctx.isCancelled()) {
            return false;
        }
        ctx.click(node);
        ctx.sleep(300);
    }
    return true;  // false 表示软失败，引擎会按重试规则稍后再试
});
```

自定义动作在引擎线程上同步执行，抛出异常时流程以 `ERROR` 结束。`ActionContext` 还提供 `refresh()`（重新抓快照）和 `perform(Action)`（执行内置动作）。

## 编写选择器

在目标页面上调用 `Apex.dumpScreen(...)` 可以拿到节点树。debug 模式下它也会输出到 logcat，tag 为 `Apex`：

```
screen seq=12 pkg=com.foo activity=com.foo.ui.HomeActivity size=1080x2400
[window 0] layer=21 active
  FrameLayout [0,0][1080,2400]
    Button id=com.foo:id/tab_task text="任务" [100,2200][300,2400] clickable
```

## 宿主配置与注意事项

- **自定义服务名称。** 宿主可以用同名资源覆盖 `apex_accessibility_label` 和 `apex_accessibility_description`，demo 里有例子。
- **不需要无障碍服务时移除它：**
  ```xml
  <service
      android:name="com.github.susanaluis877.apex.service.ApexAccessibilityService"
      tools:node="remove" />
  ```
- **启动其他 App。** Android 11 及以上，宿主需要在 manifest 里声明要启动的包，否则 `launchApp` 会以 `ACTION_FAILED` 结束：
  ```xml
  <queries>
      <package android:name="com.foo" />
  </queries>
  ```
- **服务类名不能改。** 类名 `com.github.susanaluis877.apex.service.ApexAccessibilityService` 会保存在系统设置里，改名会让已开启的服务失效。
- **上架 Google Play。** Google Play 对无障碍 API 的使用有专门政策：需要在 Play Console 里声明用途，并在应用内做显著披露。自动操作其他 App 是常见的拒审原因。
- **系统限制：**
  - Android 13 及以上，非应用商店安装的 App 要先在应用信息里“允许受限设置”，才能开启无障碍服务。`adb install` 不受影响。
  - 很多机型上强制停止 App 后，服务会从已开启列表里移除。
  - Android 14 及以上，标记为 `accessibilityDataSensitive` 的控件对非辅助工具类服务不可见。
- **节点信息的局限：**
  - Compose（未开启 `testTagsAsResourceId` 时）和 Flutter 页面通常没有 view id，可以改用 `text`、`desc` 或坐标手势。
  - WebView 的节点树可能不完整。
  - Toast 不是窗口，匹配不到。
- **快照上限。** 单次快照最多 3000 个节点、深度 64、耗时 3 秒，超出时这一轮跳过。

## Demo

`demo` 模块里有一组确定性的目标页面（首页 → 任务页 → 完成页，可选广告弹窗、售罄分支、第一次点击无效），以及控制台页面：

```bash
./gradlew :demo:installDebug

# 用 adb 开启服务（会覆盖已开启的其他服务；需要保留时先 get，再用 ':' 拼接）
adb shell settings put secure enabled_accessibility_services \
    com.github.susanaluis877.apex.demo/com.github.susanaluis877.apex.service.ApexAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 自动运行 JSON 流程并弹出广告
adb logcat -c && adb logcat -v time -s Apex ApexDemo &
adb shell am start -n com.github.susanaluis877.apex.demo/.MainActivity --es autorun json --ez ad true
```

`autorun` 可以是：

- `json` 或 `dsl`：运行对应写法的流程。
- `dump`：导出当前屏幕的节点树。

场景开关：

- `--ez soldOut true`：走售罄的失败分支。
- `--ez loseFirstClick true`：第一次点击无效，用来看重试。
- `--el cancelAfterMs 4500`：开始后 4.5 秒取消。

部分 ROM（例如 ColorOS）不允许 adb 写 secure 设置，需要在手机的无障碍设置里手动开启服务。这些 ROM 上强制停止 App 也会把服务从已开启列表里移除。

## 本地构建

需要 JDK 17+ 和 Android SDK（platform 36）。

```bash
./gradlew :apex:test                     # 单元测试（引擎、匹配、解析都在 JVM 上跑）
./gradlew :apex:assembleRelease          # 生成 AAR
./gradlew :demo:installDebug             # 安装 demo 宿主
```

## 发布新版本

1. 修改 `gradle.properties` 中的 `VERSION_NAME`。
2. 提交并推送一个与版本号同名的 tag，例如 `0.2.0`。不要加 `v` 前缀，因为 tag 名会直接作为版本号和 `Apex.getVersion()` 的返回值。
3. 打开 https://jitpack.io/#susanaluis877-ship-it/apex ，对该 tag 点击 "Get it" 触发构建，并查看构建日志。

## 许可证

[MIT](LICENSE)
