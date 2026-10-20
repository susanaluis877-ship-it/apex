# Apex

[![](https://jitpack.io/v/susanaluis877-ship-it/apex.svg)](https://jitpack.io/#susanaluis877-ship-it/apex)

基于 Android 无障碍服务的自动化点击框架。用**状态图**描述流程：每个状态说明“这是哪个页面”“在这个页面做什么”“接下来应该出现哪个页面”，全局**拦截器**负责弹窗、广告这类意外页面。

- 纯 Java，无三方依赖，minSdk 24
- 流程可以用 Java DSL 写，也可以用 JSON 写（方便远程下发）
- 支持节点点击、坐标点击和滑动、滚动、输入文本、返回/Home、启动 App，也可以用代码写条件和动作
- 每次运行都有完整的运行轨迹，失败时能看到每一步做了什么、卡在哪里

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
    implementation("com.github.susanaluis877-ship-it:apex:0.3.2")
}
```

无障碍服务会随 AAR 自动合并进宿主的 manifest，不需要再声明。

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

`Match.*`、`Actions.*` 建议静态导入。`FlowListener` 除了 `onFinished`，还有可选的 `onStateEntered` 和 `onInterceptor`。同一时间只能运行一个流程。

## 定义流程

### 状态与拦截器

| 状态字段 | 说明 |
|---|---|
| `match` | 页面匹配条件，判断“当前是不是这个页面” |
| `actions` | 进入状态后依次执行的动作 |
| `next` | 有序的候选下一状态。按顺序检查它们的 `match`，第一个匹配的就是下一状态 |
| `end` | `success` 或 `failure`：执行完动作后结束流程，不能再有 `next` |
| `timeoutMs` | 等待下一状态出现的最长时间；对起始状态是等它出现的最长时间 |
| `retryIntervalMs` / `maxRetries` | 页面毫无变化时，多久后重跑动作、最多几次 |

同一个页面要走不同分支时，拆成多个 `match` 互斥的状态，例如上面的 `task` 和 `sold_out`。

**拦截器是全局的。** 任何时候屏幕匹配它的条件，就先执行它的动作，再回到被打断的地方继续，不会让当前状态的动作重跑。

- 每一轮先检查拦截器、再检查状态，所以弹窗挡住页面时不会先点到下面的按钮。
- 如果某个弹窗本身就是流程的一步，在拦截器的条件里用 `not(...)` 把它排除掉。
- `maxTimes`（默认 5）是一次运行里最多触发几次。
- `cooldownMs`（默认 1500）是两次触发的最短间隔；冷却期间弹窗还在的话，引擎会先等着。
- 可以定义多个拦截器，按声明顺序检查，每一轮最多触发一个。条件更具体的放前面。

**全局拦截器。** 每个流程都要处理的弹窗（权限、升级提示、广告），注册一次就对所有流程生效：

```java
Apex.addGlobalInterceptor("close_ad", i -> i
        .match(has(By.id("iv_close_ad")))
        .action(click(By.id("iv_close_ad"))));
```

- 排在流程自己的拦截器之后，按注册顺序检查。
- 流程里有同 id 的拦截器时，这个流程用自己的版本。
- 某个弹窗正好是流程的一步时，用 `.excludeGlobalInterceptors("close_ad")` 排除；`.useGlobalInterceptors(false)` 则完全不用全局拦截器。
- 每次运行开始时取一次当前的全局拦截器，运行中途增删不影响这次运行。
- 没设置的 `maxTimes`、`cooldownMs` 用默认值，不受流程 `defaults` 影响。
- 同 id 再注册会替换原来的；用 `removeGlobalInterceptor(id)` / `clearGlobalInterceptors()` 移除。
- 定义写错时，注册时就会抛出异常；引用的自定义条件要先注册。

**动作什么时候执行：**

- **进入状态时执行一次。**
- **某个动作软失败时续跑。** 软失败是指目标还没出现，或者用代码写的动作返回了 false。之后页面一有变化，或者页面不变但过了 `retryIntervalMs`，就从这个动作接着执行。
- **页面毫无变化时重跑。** 页面在 `retryIntervalMs` 内一点没变，就重跑全部动作，最多 `maxRetries` 次。页面一直在变（例如正在加载）时不会重跑。不能重复执行的动作（例如下单）要设 `maxRetries(0)`。
- **其他情况只等待。** 当前状态和候选状态都不匹配时（过渡页、动画），一直等到超时。

### 流程级设置

| 设置 | 默认值 | DSL | 说明 |
|---|---|---|---|
| `timeoutMs` | 5 分钟 | `Flow.Builder.timeoutMs(...)` | 整个流程的超时 |
| `maxSteps` | 100 | `.maxSteps(...)` | 最多进入状态的次数，防止来回跳转 |
| `maxInterceptions` | 30 | `.maxInterceptions(...)` | 所有拦截器合计最多触发几次 |
| `defaults.timeoutMs` | 10 秒 | `.defaults(d -> d.timeoutMs(...))` | 状态超时 |
| `defaults.retryIntervalMs` | 3 秒 | `.defaults(d -> d.retryIntervalMs(...))` | 页面无变化多久后重跑 |
| `defaults.maxRetries` | 2 | `.defaults(d -> d.maxRetries(...))` | 最多重跑几次 |
| `defaults.actionGapMs` | 300 ms | `.defaults(d -> d.actionGapMs(...))` | 同一状态内相邻动作的间隔 |
| `defaults.interceptorMaxTimes` | 5 | `.defaults(d -> d.interceptorMaxTimes(...))` | 拦截器默认 `maxTimes` |
| `defaults.interceptorCooldownMs` | 1.5 秒 | `.defaults(d -> d.interceptorCooldownMs(...))` | 拦截器默认 `cooldownMs` |
| `useGlobalInterceptors` | true | `.useGlobalInterceptors(...)` | 是否使用全局拦截器 |
| `excludeGlobalInterceptors` | 无 | `.excludeGlobalInterceptors("id", ...)` | 不使用的全局拦截器 |

状态可以单独设置 `timeoutMs`、`retryIntervalMs`、`maxRetries`，拦截器可以单独设置 `maxTimes`、`cooldownMs`。

### 页面条件 `match`

同一个对象里的多个键是 AND 关系。

| JSON 键 | DSL | 说明 |
|---|---|---|
| `package` | `pkg("com.foo")` | 最上层应用窗口的包名 |
| `activity` | `activity(".ui.HomeActivity")` | Activity 类名，可写全名、`.` 开头的后缀或简单类名。页面刚切换时可能更新得稍晚，单 Activity、Compose、Flutter 应用里也不区分页面，所以只当辅助条件 |
| `has` | `has(selector...)` | 存在匹配的节点；写多个时要求每个都存在 |
| `not` | `not(matcher)` | |
| `allOf` / `anyOf` | `allOf(...)` / `anyOf(...)` | |
| `always` | `always()` | 任何屏幕都匹配，JSON 里写 `true` |
| `custom` | `custom(screen -> ...)` / `Match.custom("名字")` | 用代码写的条件，见下文 |

### 节点选择器

同一个对象里的多个键也是 AND 关系，DSL 写成 `By.xxx(...).yyy(...)`。

| 键 | 说明 |
|---|---|
| `text` / `textContains` / `textMatches` | 文本完全相等 / 包含 / 整段匹配正则 |
| `desc` / `descContains` / `descMatches` | contentDescription，规则同上 |
| `id` | 短名 `submit` 匹配任意包下的 `:id/submit`，全名 `com.foo:id/submit` 精确匹配 |
| `className` | 简单类名 `Button` 或全名 `android.widget.Button` |
| `clickable` / `enabled` / `checked` / `selected` / `scrollable` | 状态过滤 |
| `visible` | 默认只匹配用户可见的节点；设为 `false` 时可见和不可见的都匹配 |

有多个节点匹配时取第一个，顺序是窗口从上到下、窗口内先序遍历。

### 动作

| JSON `type` | 参数 | DSL |
|---|---|---|
| `click` / `longClick` | `target`，`mode`：`auto`（默认）、`node`、`gesture` | `click(By...)` / `longClick(By...)` |
| `tap` | `x`、`y`，或 `xRatio`、`yRatio`（0~1），可选 `durationMs` | `tap(x, y)` / `tapRatio(0.5, 0.9)` |
| `swipe` | `from`、`to`（`[x, y]`），或 `fromRatio`、`toRatio`，可选 `durationMs` | `swipe(...)` / `swipeRatio(...)` |
| `scroll` | `direction`：`forward`、`backward`、`up`、`down`、`left`、`right`，可选 `target` | `scroll(direction)` / `scroll(By..., direction)` |
| `setText` | `target`、`text` | `setText(By..., "text")` |
| `back` / `home` / `recents` / `notifications` | 无 | `back()` 等 |
| `launchApp` | `package` | `launchApp("com.foo")` |
| `sleep` | `ms` | `sleep(1000)` |
| `custom` | `name`、可选 `args` | `run(ctx -> ...)` / `Actions.custom("名字")`，见下文 |

所有动作都支持两个参数：

- `optional: true`，DSL 写 `.optional()`：失败时跳过，继续下一个动作。
- `delayMs`，DSL 写 `.delayMs(ms)`：执行前先等一会儿。

`click` 的 `auto` 模式先对目标或它最近的可点击祖先执行节点点击，不成功再按节点中心坐标点击。自绘 View、WebView、Flutter 页面建议直接用 `gesture` 模式。

### 用 JSON 定义

上面快速开始的流程写成 JSON 是这样的，两种写法得到的流程完全相同：

```json
{
  "id": "claim",
  "start": "home",
  "states": [
    { "id": "home", "match": { "package": "com.foo", "has": { "id": "tab_task" } },
      "actions": [ { "type": "click", "target": { "id": "tab_task" } } ], "next": ["task", "sold_out"] },
    { "id": "task", "match": { "has": { "text": "领取奖励", "enabled": true } },
      "actions": [ { "type": "click", "target": { "text": "领取奖励" } } ], "next": "done" },
    { "id": "done", "match": { "has": { "textContains": "领取成功" } }, "end": "success" },
    { "id": "sold_out", "match": { "has": { "text": "已抢光" } }, "actions": [ { "type": "back" } ], "end": "failure" }
  ],
  "interceptors": [
    { "id": "close_ad", "match": { "has": { "id": "iv_close_ad" } },
      "actions": [ { "type": "click", "target": { "id": "iv_close_ad" } } ] }
  ]
}
```

顶层还可以写上面“流程级设置”里的各项（`defaults` 是一个对象）；状态和拦截器里可以写各自的超时、重试次数。更完整的例子见 `demo/src/main/assets/demo_flow.json`。

```java
try {
    Flow flow = FlowParser.parse(json);
} catch (FlowParseException e) {
    // e.getPath() 例如 "states[1].next[0]"，e.getReason() 例如 "unknown state 'x'"
}
```

解析规则：

- 任何位置出现未知的键都会报错，用来发现拼写错误。
- 值为 `null` 的键视为没写。
- 流程写错时，`Flow.Builder.build()` 和 `FlowParser.parse()` 都会指出具体位置。

## 用代码写条件和动作

内置的条件和动作不够用时，可以直接传函数：

```java
.state("withdraw", s -> s
        // 条件：拿到整个屏幕快照，返回 true 表示匹配
        .match(custom(screen -> {
            UiNode balance = By.id("tv_balance").findFirst(screen);
            String digits = balance == null || balance.getText() == null
                    ? "" : balance.getText().replaceAll("[^0-9]", "");
            return !digits.isEmpty() && Long.parseLong(digits) >= 100;
        }))
        // 动作：拿到 ActionContext，返回 true 表示成功
        .action(run(ctx -> {
            for (UiNode node : By.text("领取").findAll(ctx.screen())) {
                if (ctx.isCancelled() || !ctx.click(node)) {
                    return false;
                }
                ctx.sleep(300);
            }
            return true;
        }))
        .next("done"))
```

**条件函数能用到的信息：**

- 快照 `ScreenSnapshot` 提供包名、Activity、窗口（从上到下）、全部节点 `nodes()` 和屏幕尺寸。
- 节点 `UiNode` 提供文本、id、位置、状态，以及 `getParent()` / `getChildren()`。
- `By...findFirst(screen)` / `findAll(screen)` 可以在快照里找节点。

条件可以和其他条件组合，例如 `allOf(pkg("com.foo"), custom(...))`，也可以用在拦截器上。

**`ActionContext` 的方法：**

| 方法 | 作用 |
|---|---|
| `screen()` | 当前快照 |
| `refresh()` | 重新抓一次快照 |
| `click(node)` | 点击节点 |
| `perform(action)` | 执行内置动作，例如 `ctx.perform(swipeRatio(0.5, 0.8, 0.5, 0.2))` |
| `sleep(ms)` | 等待，流程取消时提前返回 |
| `isCancelled()` | 流程是否已取消 |

**规则：**

- **执行线程。** 函数在引擎线程上同步执行。条件函数每一轮可能调用多次，不要做耗时操作；动作函数可以阻塞，但循环里要检查 `isCancelled()`。
- **返回值和异常。** 动作返回 false 算软失败，引擎会按上面的规则续跑；函数抛异常时，流程以 `ERROR` 结束。
- **嵌套。** 动作函数里不能再用 `perform` 执行另一个 `run(...)` 或 `custom(...)`。
- **方法名冲突。** 构建流程的类如果自己有 `run` 方法（例如实现了 `Runnable`），要写成 `Actions.run(...)`。

**JSON 流程或要复用时，按名字注册。** 必须先注册，再构建或解析引用它们的流程。注册的版本还能拿到流程里写的 `args`：

```java
Apex.registerMatcher("hasBadge", (screen, args) -> By.textMatches("\\d+").findFirst(screen) != null);
Apex.registerAction("tapAll", (ctx, args) -> { /* 同上 */ return true; });
```

JSON 里写 `{"custom": "hasBadge"}` 和 `{"type": "custom", "name": "tapAll", "args": {...}}`。在 DSL 里，因为 `Match` 和 `Actions` 都有 `custom("名字")`，两个类都静态导入时要写全：`Match.custom("hasBadge")` / `Actions.custom("tapAll")`。

## 运行结果与排查

| `RunResult.getStatus()` | 含义 |
|---|---|
| `SUCCESS` / `FAILURE` | 到达 `end: success` / `end: failure` 的状态 |
| `TIMEOUT` | 起始状态或下一状态没出现，或整个流程超时 |
| `MAX_STEPS` | 进入状态的次数超过 `maxSteps` |
| `ACTION_FAILED` | 动作无法执行，例如找不到要启动的 App、目标不是输入框 |
| `CANCELLED` | 调用了 `RunHandle.cancel()` |
| `SERVICE_UNAVAILABLE` | 服务没连接，或运行中断开超过 3 秒 |
| `BUSY` / `NOT_INITIALIZED` / `INVALID_ARGUMENT` | 没能开始运行 |
| `ERROR` | 自定义的条件或动作抛出了异常 |

结果里还有 `getPath()`（依次进入的状态）、`getInterceptorCounts()`、`getDurationMs()`、`getMessage()`（失败原因）。

**运行轨迹。** 每次运行的每一步都会记录在 `getTrace()` 里，不受日志级别影响：

- 进入了哪个状态。
- 每个动作点了哪个节点、用什么方式、结果和耗时。
- 拦截器触发、重试和续跑。
- 等待时，每个候选状态为什么还没匹配。
- 失败时，最后一屏的节点树。

`formatTrace()` 可以把这些格式化成文本，方便上报：

```
run demo.claim#3 TIMEOUT in 10.200s, path=[home]
+0.000s STATE_ENTER home (step 1) on screen seq=2 pkg=com.foo activity=HomeActivity windows=1 nodes=12
+0.000s ACTION      home 1/1 click{id="demo_start"} -> OK in 12ms: ACTION_CLICK on Button id=com.foo:id/demo_start ...
+0.200s WAIT        next of 'home' [task, sold_out] (0.200s/10.000s, retries 0/2); left 'home' (...);
                    task: has{id="demo_claim", enabled=true}: 1 node(s) match all but enabled=true, e.g. Button ... (enabled=false);
                    sold_out: has{text="Sold out"}: no node matches (text="Sold out": 0 node(s))
+10.200s SCREEN_DUMP last screen: ...
+10.200s RUN_END     TIMEOUT at 'home' in 10.200s, ...
```

**日志。** 同样的内容也会输出到日志，每行带 `[流程id#运行编号 +耗时]` 前缀，tag 为 `Apex`。日志级别和去向在初始化时设置：

```java
Apex.init(this, new ApexConfig.Builder()
        .setDebug(BuildConfig.DEBUG)       // debug 时默认 DEBUG 级，否则只输出 WARN 及以上
        .setLogLevel(ApexLogger.VERBOSE)   // 可选：VERBOSE 会额外输出每一轮的快照概况
        .setLogger((priority, tag, message, throwable) -> {
            // 接管所有日志：写文件、接 Timber、上报。要保留 logcat 就再转给 ApexLogger.LOGCAT
            ApexLogger.LOGCAT.log(priority, tag, message, throwable);
        })
        .build());
```

各级别的内容：

- ERROR：动作无法执行、代码抛异常。
- WARN：超时、服务断开。
- INFO：开始、结束、进入状态、拦截器触发、重试。
- DEBUG：每个动作的细节、等待原因。

日志接收器可能在任意线程调用，要线程安全并尽快返回。

**编写选择器。**

- `Apex.dumpScreen((snapshot, text) -> ...)` 可以拿到当前页面的节点树（id、文本、位置、状态），debug 模式下也会输出到 logcat。
- 选择器或条件没匹配上时，可以直接问原因：

```java
By.id("demo_claim").enabled(true).explainMismatch(screen);
// "1 node(s) match all but enabled=true, e.g. Button id=...:id/demo_claim ... (enabled=false)"，匹配时返回 null
```

## 注意事项

- **自定义服务名称。** 用同名资源覆盖 `apex_accessibility_label` 和 `apex_accessibility_description`，demo 里有例子。
- **不需要无障碍服务时，移除它：**
  ```xml
  <service
      android:name="com.github.susanaluis877.apex.service.ApexAccessibilityService"
      tools:node="remove" />
  ```
- **启动其他 App。** Android 11 及以上，要在 manifest 里声明目标包，否则 `launchApp` 会失败：
  ```xml
  <queries>
      <package android:name="com.foo" />
  </queries>
  ```
- **上架 Google Play。** 使用无障碍 API 需要在 Play Console 里声明用途，并在应用内做显著披露。自动操作其他 App 是常见的拒审原因。
- **开启服务的系统限制：**
  - Android 13 及以上，非应用商店安装的 App 要先在应用信息里“允许受限设置”，才能开启服务。
  - 很多机型上，强制停止 App 后服务会被关掉，需要重新开启。
- **节点信息的局限：**
  - Compose（未开启 `testTagsAsResourceId` 时）和 Flutter 页面通常没有 view id，可以改用文本、描述或坐标手势。
  - WebView 的节点可能不完整。
  - Toast 匹配不到。
  - Android 14 及以上，标记为 `accessibilityDataSensitive` 的控件不可见。
  - 节点特别多（超过 3000 个）的页面会被跳过。

## Demo

`demo` 模块有一组用来演示的页面：首页 → 任务页 → 完成页，可以选择弹出广告、走售罄分支、或者第一次点击无效；另外还有一个控制台页面。

```bash
./gradlew :demo:installDebug

# 开启服务（会覆盖已开启的其他服务；部分 ROM 不允许 adb 改这个设置，需要在手机上手动开启）
adb shell settings put secure enabled_accessibility_services \
    com.github.susanaluis877.apex.demo/com.github.susanaluis877.apex.service.ApexAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 自动运行 JSON 流程并弹出广告
adb logcat -s Apex ApexDemo &
adb shell am start -n com.github.susanaluis877.apex.demo/.MainActivity --es autorun json --ez ad true
```

`autorun` 参数：

- `json` / `dsl`：运行对应写法的流程。
- `dump`：导出当前屏幕。

场景开关：

- `--ez soldOut true`：走售罄分支。
- `--ez loseFirstClick true`：第一次点击无效，演示重试。
- `--el cancelAfterMs 4500`：4.5 秒后取消。

## 许可证

[MIT](LICENSE)
