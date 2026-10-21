# 让 AI 像人一样探索、操作并编写 Apex 流程

> 状态：2026-10-20 方案确认；2026-10-21 完成并随 **0.4.0** 发布（main `89c6e0b`，JitPack ok）。实施结果、DSL 优先的调整和遗留问题见文末。

## 背景

现状：开发者先用 uiauto.dev 看页面、找特征，再一步一步口述给 AI。这样效率很低。

目标：开发者只用自然语言描述流程（例如「打开设置把亮度调到最高」「新建一个工作日 7:30 的闹钟」），然后由 AI 完成全部工作：

- **像人一样探索**：看得到截图和节点树；知道上一步做了什么、导致了什么；判断自己是否离目标更近；走错了能自己退回来。
- **写出流程并验证**：编写流程 JSON，校验后真机运行，读运行轨迹，迭代到通过。

需要人做的事只有三件：开启一次无障碍服务；处理登录、验证码、支付；审阅最终结果。

验收方式是真实场景：在 runner App 里选择流程并运行，由它去操作系统设置、时钟等真实 App。demo 内置页面只用于回归测试。

## 调研结论

**1. 用 UiAutomation 的工具都会让 Apex 掉线。**

- 受影响的工具：uiauto.dev（底层是 uiautomator2）、`uiautomator dump`、Appium、Maestro、mobile-mcp。
- 原因：它们都以 flags=0 连接 UiAutomation。连接期间，系统会 unbind 所有无障碍服务，断开后才恢复。
- 第二个问题：它们看到的节点树和 Apex 不一样（窗口过滤、`flagIncludeNotImportantViews`、id 规则都有差别）。在那边验证通过的选择器，放到 Apex 里不保证能用。
- 结论：AI 必须通过 Apex 自己的服务读取页面，并且只用 Apex 自己的动作做探索。这样能探索通的路径，就一定能写成流程。

**2. 参考方案。**

- Maestro 和 agent-device：探索时用临时引用定位元素；存下来的流程只用选择器；失败时返回结构化的原因。
- Appium MCP：先在真机上走一遍，再生成代码。

**3. 截图和节点树一次返回。**

- MCP 的工具结果可以同时带图片和文本，一次调用就能拿到带编号框的截图和节点树。
- CLI 做不到：Bash 返回文本后，还得再 Read 一次图片。
- 所以探索阶段用 MCP；运行、批量检查和人工排查用 CLI。两者用同一个 Python 文件实现。

**4. 节省上下文的做法。**

- 只列出可操作和有信息量的节点，每个带编号。
- 操作之后只给页面变化部分。
- 截图降采样到大约 540×1200（Claude 按像素计费，约 900 token）。

**5. 测试设备情况。** OPPO PHW110，ColorOS 13.1（Android 13）。

- 设置：`com.android.settings`
- 时钟：`com.coloros.alarmclock`
- 无线设置：`com.oplus.wirelesssettings`

**6. 顺带要更正和补充的。**

- 强制停止会把服务移出「已开启」列表，这是 AOSP 本身的行为，CLAUDE.md 要改。
- 终止状态的动作软失败时，流程照样结束（`FlowEngine.java:235`），README 要写明这一点。

## 方案总览

```
开发者："打开设置把亮度调到最高"
  ↓
Claude Code + apex-flow 插件
   ├─ skill：思考协议、编码规范、JSON 速查、排错
   └─ MCP（apex.py mcp）：observe / act / find_text / plan / history / features / check / run …
        ↓ adb forward tcp:N localabstract:apex_<pkg>
宿主 App（runner）里的 Apex devtools 桥（只在 debuggable 包启动）
  → 复用 FlowParser / Selector / ActionRunner / FlowEngine / Fingerprint
```

## 一、SDK

### 1.1 devtools 桥（`internal/devtools` + `internal/DevBridge`）

**存放位置。** 放在主 AAR 里，默认休眠。

- 不加 manifest 条目，不加权限，JitPack 坐标不变。
- 只有同时满足三个条件才启动：`ApexConfig.setDevtools(true)`（默认就是 true）、应用是 debuggable 包、当前是主进程。

**传输与安全。**

- 用抽象命名空间的 `LocalServerSocket("apex_<pkg>")`。
- 用 `getPeerCredentials` 校验对方，只接受 uid 0、2000 和本应用。

**协议。**

- 每个连接只发一行 JSON 请求，返回一行或多行 JSON；带 `protocol:1`。
- `run` 会流式返回轨迹，2 秒一次心跳；客户端断开就取消运行。

**线程与异常。**

- 屏幕操作都在 `apex-engine` 线程上执行；有流程在跑时返回 BUSY。
- 所有异常都转成错误响应，`Apex.*` 保持不向外抛异常。

**命令。**

| 命令 | 作用 |
|---|---|
| `info` | 返回版本、服务状态、是否在运行、屏幕尺寸、自定义名字、全局拦截器 |
| `snapshot` | 等页面稳定后抓快照，返回 JSON，带 seq 和 handle |
| `find` | 在保留的快照上，用真实的 `Selector` 批量验证候选选择器，返回命中数、首个 handle 和 explainMismatch |
| `act` | 执行一个动作 JSON（走 `ActionRunner`），等页面稳定后抓新快照，返回结果 |
| `validate` | 校验 JSON，返回出错的路径和所有问题 |
| `check` | 计算「流程状态和拦截器 × 若干快照」的匹配矩阵，并给出告警；对实时页面用时就是 `where` |
| `run` / `cancel` | 走正常的运行槽位执行流程，或取消 |

`check` 会告警的情况：

- 以某个状态命名的快照没被这个状态匹配上；
- 同一个 `next` 里有多个候选匹配同一张快照（指出实际谁会胜出）；
- 拦截器匹配到了正常页面；
- 某个状态匹配所有快照。

### 1.2 快照里加入 AI 需要的信息

这些字段对人和 AI 理解页面都有用，selector 暂不使用它们。

| 新增到 `UiNode` 的字段 | 来源 | 可用 API 级别 |
|---|---|---|
| `range`（min / max / current） | RangeInfo | 24+ |
| `canScrollForward` / `canScrollBackward` / `canSetProgress` | `getActionList()` | 24+ |
| `stateDescription`（例如开关显示的「已开启」） | 节点属性 | 30+，更低版本为 null |
| `hint` | 节点属性 | 26+ |

改动范围：`AccessibilityScreenSource` 负责拷贝这些字段，`ScreenDumper` 和 `SnapshotJson` 负责输出它们。

### 1.3 真实场景需要的动作（先做 spike 再定）

候选动作：

- `setProgress {target, value | ratio}`：调滑块，例如亮度。
- `scrollTo {container?, target, direction, maxSwipes}`：在列表里滚动，直到目标出现。设置首页、闹钟列表、滚轮选择器都要用。

做法：先用桥在真机上把设置和时钟的页面摸一遍，按实际看到的情况再定接口。如果滚轮选择器暴露的是一组虚拟节点，用 `scrollTo` 就够了；如果暴露的是 EditText，用 `setText` 就够了。只实现基准任务真正用到的动作。

## 二、探索工具：让 AI 像人一样看、想、做

宿主端只有一个文件 `apex.py`，用 Python 3.8 以上的标准库写成。

- `apex.py mcp`：手写的 MCP stdio 服务（JSON-RPC），不依赖任何第三方库。
- `apex.py <cmd>`：同样的功能做成 CLI。

### 2.1 `observe`：一次拿到截图、页面结构和上下文

返回的图片是降采样后的截图，上面给可操作元素画编号框（set-of-mark），编号和文本里的 `@n` 一一对应。截图流程如下：

1. 用 `adb exec-out screencap` 取原始 RGBA（文件头长度用 `总长 - w*h*4` 推算）。
2. 抽样缩小。
3. 画框，并用内置的点阵数字字体画编号。
4. 用 zlib 编码成 PNG。

整个过程只用标准库。参数 `screenshot` 可以取 `annotated`、`plain` 或 `none`。

返回的文本示例：

```
目标: 把屏幕亮度调到最高      计划 2/3 ▸ 进入「显示与亮度」（证据：出现「亮度」）
上一步 #4: click {"text":"显示与亮度"}  意图: 进入显示设置
  结果: 进入新页面 P5「显示与亮度」(首次到达)  预期「亮度」✓
  新增: 亮度 / 自动亮度 / 深色模式 …   消失: WLAN / 蓝牙 …
路径: P1 桌面 → P2 设置 → P5 显示与亮度
页面 P5 com.android.settings/.SubSettings  窗口1  标题「显示与亮度」
可操作:
  @3  返回       ImageButton desc="向上导航"
  @12 开关       "深色模式"  状态: 关闭
  @15 滑块       SeekBar "亮度"  45/100  可 setProgress
  @20 列表       RecyclerView  ↓还有更多
  @22 输入框     "搜索设置"
其他文本: …
⚠ （没有警告时不显示这一行）
```

- **页面结构。**
  - 每个可操作元素按人的理解归类：按钮、开关（带状态）、滑块（带当前值）、输入框、可滚动列表（标出还能往哪个方向滚）、返回、标签页。
  - 没有文本的可点击容器，用子节点的文本拼出标签，和读屏软件的做法一样。
  - 页面标题取顶部区域的显著文本。
  - 弹窗和对话框单独标出。
- **页面身份（P 编号）。**
  - 由包名、activity 和页面上稳定的 id/文本集合求相似度：相似就认为是同一页，否则分配新的 P 编号。
  - 这样 AI 能知道「这页来过」，能看到整条路径，也能发现自己在兜圈子。

### 2.2 `act`：每次操作都带意图和预期，执行完直接返回新的 observe

- **参数。**
  - 动作本身：可以是 `@n`，也可以是 Apex 动作 JSON，覆盖 Apex 支持的全部动作。
  - `intent`：为什么要做这一步。
  - `expect`：期望看到的结果，可选。例如 `{"text":"亮度"}`、`{"leaves":"P2"}`、`{"dialogClosed":true}`。
- **执行。**
  - `@n` 会先换成已经在设备上验证过的、最稳定的选择器，再执行。实际用的选择器会写进记录，之后可以直接搬进流程。
  - 执行完立刻 observe，返回内容包括前后变化、预期是否满足（✓/✗）、新页面。
- **警告。** 以下情况会自动提示：
  - 点了以后页面没变化（可能点错了，或者需要等待）；
  - 离开了目标 App（例如进了应用商店或广告落地页），会附带建议用 `back`；
  - 同一页面来回超过 3 次；
  - 连续 N 步计划没有推进；
  - 出现了没预料到的弹窗（它很可能应该做成拦截器）。

### 2.3 其他工具

| 工具 | 作用 |
|---|---|
| `plan set/done/revise` | AI 把目标拆成若干里程碑，每个里程碑写上「到了这一页会看到什么」。每次 observe 的开头都会带上目标和当前进度，保证目标始终在上下文里。这些里程碑之后基本就对应流程里的状态 |
| `find_text T [--scroll]` | 在当前页面找文本；加 `--scroll` 时会用 Apex 的滚动动作翻遍可滚动区域，并报告文本在哪里。相当于人「往下翻找一找」 |
| `back` / `reset` / `launch` | 后退、清掉任务后重启 App（`am start -f 0x10008000`）、打开 App。不提供对宿主包的 force-stop |
| `history` | 列出完整操作记录：每一步的页面、动作、意图、验证过的选择器、结果 |
| `features P5` | 找出 P5 有、而本次会话里其他页面都没有的选择器，并在设备上验证。用来给每个状态写 matcher |
| `validate` / `check` / `where` / `run` | 流程阶段用。`check` 默认用本次会话保存的全部页面快照作为样本 |

会话数据保存在 `.apex/sessions/<时间>/` 下：`journal.jsonl`、`pages/P*.json`、`shots/*.png`。

## 三、skill：思考协议和编码规范

### 探索阶段：每一步都按这个协议做

1. **定计划。** 拿到需求后，先 `plan set`：把目标拆成里程碑，并写清每个里程碑的判断依据。
2. **看。** 调用 `observe`。
3. **想。** 回答四个问题：
   - 我在哪？
   - 上一步的效果符合意图吗？
   - 离下一个里程碑更近了吗？
   - 如果偏了，是退回去、换个入口（搜索框、菜单、往下翻），还是重新规划？
4. **做。** 调用 `act`，必须带 `intent`，最好带 `expect`。

**纠错规则：**

- 连续 2 步没有推进，就停下来重新看整个页面和路径。
- 进了无关页面，先 `back`；退不回来就 `reset`。

**必须先问人的情况：**

- 不可逆的操作：付款、发消息、删除别人的数据、下单；
- 登录和验证码。

**禁止事项：**

- 不能用 uiautomator 系工具；
- 不能 force-stop 宿主包。

### 编码阶段

1. **准备素材。**
   - 用 `history` 取出成功路径。
   - 对每个里程碑页面，用 `features` 找出能区分它的特征。
2. **写流程 JSON。**
   - 状态 = 里程碑页面，`next` 按路径填写。
   - 中途意外弹出的窗口写成拦截器；如果在多个流程里都会出现，建议做成全局拦截器。
3. **跑 `validate` 和 `check`。**
4. **`reset` 后 `run`，读运行轨迹并修改。**
5. **做鲁棒性检查，像人做自测一样**：
   - 从不同的起点再跑一次：App 已经停在深层页面、App 在后台；
   - 目标已经达成时再跑一次：例如亮度已经是最高、深色模式已经开着。
   - 根据结果补上分支，让流程可以重复执行而结果不变。
6. **交付。**
   - 交付流程 JSON，并说明有哪些假设、哪些步骤需要人来做。
   - 需要的话，再转成 Java DSL。

### 参考文档

`references/` 目录下准备三份：

- `flow-json.md`
- `selectors.md`：讲选择器怎么选、各候选状态之间要互斥。
- `trace-troubleshooting.md`

### 分发

- apex 仓库本身就是一个 Claude Code 插件市场：
  - 根目录放 `.claude-plugin/marketplace.json`；
  - 插件放在 `plugins/apex-flow/` 下，里面有 skill、`.mcp.json`（`python3 ${CLAUDE_PLUGIN_ROOT}/scripts/apex.py mcp`）和可选的 PreToolUse hook（拦截 uiautomator，以及对宿主包的 force-stop）。
- `doctor` 会检查 SDK 协议版本和插件版本是否匹配。

## 四、runner App（demo 改造）

**主界面。** 一个流程列表：

- 来源：内置的 `assets/flows/*.json`，加上 AI 推送过来的 `files/flows/*.json`。
- 每个流程可以点「运行」或「取消」。
- 显示实时进度和失败时的运行轨迹。
- 显示服务状态，并提供打开无障碍设置的入口。

**推送流程。** `apex.py push flow.json` 的实现是 `adb shell run-as <pkg> sh -c 'cat > files/flows/x.json'`。debug 包本来就允许 `run-as`，所以这条路不需要 SDK 参与。

**启动其他 App。** manifest 里声明 `<queries>`（MAIN/LAUNCHER intent），`launchApp` 才能拉起设置、时钟等 App。

**自动化入口。** 保留 `autorun` extra，并新增 `flow=<name>`，方便验收脚本直接从命令行跑指定流程。

**demo 内置页面。** 原来的目标页面和 `demo_flow.json` 留作回归用例。新增 `autorun=open`：这些页面是 `exported=false`，adb 无法直接打开。

## 五、真实场景基准（验收标准）

测试设备是 OPPO PHW110（ColorOS 13.1），runner 用 debug 包。

| 编号 | 需求（只给 AI 这句话） | 主要考验什么 |
|---|---|---|
| T1 | 打开设置，把屏幕亮度调到最高 | 多级导航、找入口（滚动或搜索）、滑块 |
| T2 | 在时钟里新建一个工作日 07:30、标签为 Apex 的闹钟 | 时间选择器、重复日期的多选、保存后确认 |
| T3 | 删除标签为 Apex 的闹钟（同时清理 T2 留下的闹钟） | 在列表里定位、长按或编辑菜单、确认弹窗 |
| T4 | 打开深色模式；如果已经开着，直接算成功 | 读取开关状态、分支、可重复执行 |

**流程。**

1. 新开一个 Claude Code 会话，只装 apex-flow 插件，把需求原话给 AI。
2. AI 自己完成探索、编码，并推送到 runner。
3. 在 runner 里选择这个流程运行（或者用 `flow=` 跑）。每个任务从三个起点各跑一次：桌面、目标 App 停在无关页面、目标已经达成。

**判定。** 运行结果是 SUCCESS，并且实际效果和需求一致。核对实际效果用 Apex 的快照，或者只读命令，例如 `settings get system screen_brightness`、闹钟列表。

**记录的指标。** 人工介入次数、工具调用次数、迭代轮数、耗时，以及失败原因的分类：选择器问题、页面理解问题、SDK 能力缺口。

**测试副作用。** 测试前记录亮度和深色模式的原值，测试后恢复。T3 本身就负责清理 T2 建的闹钟。

## 里程碑

每一步都要求 `:apex:test` 和 `:apex:lintRelease` 通过。

| 编号 | 内容 |
|---|---|
| M0 | 小重构，不改变行为：`FlowParser` 公开 `parseSelector`、`parseMatcher`、`parseAction`；抽出 `Settler`；增加 `ActionRunner.runOnce`；Tracer 支持 sink；增加 `Registry.actionNames()` 和 `matcherNames()` |
| M1 | 纯 Java 部分：`SnapshotJson`、`FlowCheck`、`DevtoolsSession`、`Dispatcher`；`UiNode` 新增字段（见 1.2）。`PurityTest` 覆盖 `internal/devtools` |
| M2 | Android 部分：`DevBridge`、`ApexCore.submit` 及其他改动、`ApexConfig.setDevtools`；`apex.py` 的最小 CLI（doctor、screen、act、run） |
| M3 | spike：用 M2 的工具在真机上把 T1–T4 的页面摸一遍，确定 1.3 的动作接口，并收集页面快照作为测试素材 |
| M4 | SDK 新动作（只做 spike 确认需要的）：JSON 和 DSL 两种写法、等价性测试、`AndroidActionExecutor` 实现 |
| M5 | 探索工具：带编号框的截图、页面结构和归类、页面身份、diff、`plan`、`history`、`features`、`find_text`、警告；MCP stdio 服务 |
| M6 | runner App（demo 改造）、`push` |
| M7 | skill、插件清单、hook；跑真实场景基准，按失败原因迭代工具、skill 和 SDK |
| M8 | 更新 README（新增「让 AI 编写流程」一节，以及与 UiAutomation 的冲突说明）和 CLAUDE.md（devtools、更正 force-stop），按原有流程发布 0.4.0 |

## 验证

**JVM 单测：**

- `SnapshotJsonTest`：序列化往返后，dump 结果完全一致。
- `FlowCheckTest`：覆盖歧义、拦截器误伤、自定义 matcher 抛异常只影响一格。
- `DevtoolsSessionTest`：用 FakeDevice 测点击、软失败、硬失败、seq 过期、页面稳定的计时。
- `DispatcherTest`：覆盖错误请求、BUSY、`validate` 的路径、`run` 的流式输出、断开后取消。
- 新动作的解析、DSL 与 JSON 的等价性、引擎测试。
- 用 M3 收集的真实快照（设置、时钟）做 fixture，测 `features` 和 `check`。

**Python `unittest`：**

- 页面结构和元素归类、页面身份是否稳定、diff。
- 截图标注：对一张已知的 RGBA 图，检查输出 PNG 的尺寸和像素。
- MCP 握手、`tools/list`、`tools/call`（图片加文本）。
- 不会生成对宿主包的 force-stop。

**真机：**

- 回归：demo 的四个场景。
- release 包里桥不存在。
- 跑过 `uiautomator dump` 后，`doctor` 能给出提示。
- 运行中返回 BUSY；取消正常。

**验收：**

- 第五节的 T1–T4 全部通过。
- 记录各项指标和失败原因。
- 测试结束后恢复设备状态。

## 本期不做

- 示教录制：人点一遍，桥记录 `TYPE_VIEW_CLICKED` 和前后快照。适合登录、验证码这类 AI 走不通的环节。
- 相对选择器和序号选择器。如果基准任务卡在这里，会提到 M4 来做。
- 截图用 `AccessibilityService.takeScreenshot`（API 30+）。目前用 adb 截图就够了。


## 实施结果（2026-10-21）

**验收：** 每个任务由一个全新的 AI 会话完成，只给一句需求和插件（skill + 命令行），不给任何页面信息。

| 任务 | 状态数 | 真机运行 | 迭代 | 人工介入 |
|---|---|---|---|---|
| T4 打开深色模式（已开则直接成功） | 7 | 5 次全部成功（含换起点、目标已达成） | 1 轮（只是调了滚动方向，省时间） | 0 |
| T1 亮度调到最高 | 5 | 4 次全部成功 | 0 | 0 |
| T2 新建工作日 07:30「Apex」闹钟 | 9 | 3 次全部成功（滚轮、工作日类型弹窗） | 0 | 0 |
| T3 删除「Apex」闹钟（没有则成功） | 9 | 4 次全部成功（含两个 Apex、残留多选） | 0 | 0 |

之后在 runner App 的列表里逐个点「运行」，四个流程全部成功，并用只读命令核对了实际效果。流程原样保存在 `docs/examples/coloros13/`。

**验收中发现并补上的 SDK 能力**（都是真实 App 卡住或差点出错的地方）：

- `scroll` 的方向回退（ColorOS 列表不支持 up/down）、`scrollTo`、`setProgress`。
- 选择器的 `progressAtLeast` / `progressAtMost` / `stateDesc`（「已经拉满」不用写死机型的最大值）。
- 关系条件 `hasDescendant` / `inside`（「同一行里同时有 07:30 和 Apex」，两个独立的 `has` 会误判）。
- 滚到头的判断：节点的 canScroll 标志 + 不含位置的内容指纹（ColorOS 列表到头会回弹）。
- 坐标兜底遇到屏幕外的目标时不点（页面滑入动画中）。

**每轮验收反馈修掉的工具问题**（节选）：`expect` 支持选择器且拒绝未知键；`check` 豁免 `always`、样本对应与参数顺序无关；同一页面的附加样本；按选中的标签页区分页面；选择器建议同时检查不可见的同样节点、避开含数字的文字；合并标签分段显示；屏幕变暗提示；history 记录 launch / run；截图不覆盖；skill 示例换成与验收任务无关的场景，避免泄题。

**没做（留给以后）：** MCP 包装之外的其他客户端测试（验收用的是命令行，MCP 只做了协议级和真机单次调用的验证）、示教录制、`takeScreenshot`。

## 改为 DSL 优先（2026-10-21 下午）

用户的决定：交付固化下来的 Java DSL 代码，JSON 只在项目需要时由 SDK 导出；调试桥没就绪时用 adb 兜底完成安装和开启服务。

- SDK：`FlowWriter`（DSL → JSON，往返相等校验，内联代码报告位置）；调试桥按 `类名#静态方法` 加载宿主代码里的流程（`FlowRefs`），`validate` / `check` / `run` / `export` 都支持。
- 电脑端：`deploy`（编译 → 覆盖安装 → 等服务重连，demo 约 2~3 秒一轮）、`setup` 与 adb 模式（ColorOS 上从卸载到 Apex 就绪约 25 秒全自动：点「继续安装」、在无障碍设置里开服务并点「允许」；settings 前后对比无副作用）。
- 验收：全新会话在 demo 模块里写出 `flows/ClockFlows.java`（15 个状态，常量化的选择器、关系条件、安全注释），3 版代码无编译错误，9 次运行全部成功，每轮 deploy 约 3 秒；判断 runner 直接加载 DSL、不需要导出 JSON。之后在 runner 里点「运行」新建闹钟成功，再用 JSON 的删除流程清理。
- 事故：`setup` 第一版在无障碍设置列表页误开了两个无关开关（条件太宽），已恢复并收紧规则，加了单测；以后自动操作系统设置都做 settings 前后对比。

## 遗留问题和后续可做

- **MCP 只做了协议级测试和单次真机调用**：所有验收都是子代理通过命令行完成的，还没有在装了插件的真实 Claude Code 会话里用 MCP（截图直接进上下文）跑过一次完整任务。
- **setup 只在 ColorOS 13 上验证过**：其他 ROM 的安装确认、无障碍设置结构不同；首次安装要求输入账号密码的情况只做了检测（报告给人），没实测过。
- **深色模式被打开的原因没查清**：最后检查时发现手机变成了深色模式，DSL 验收的会话没进过「设置」，最可疑的是调试 `setup` 时在「设置」里的 adb 模式操作。已恢复。以后自动操作系统设置时测试前后都做 `settings list` 对比。
- **启发式规则**：页面身份、列表项判断、遮挡检测、选择器和页面特征建议都是启发式的，换 App 可能需要调整；验收里发现的问题都已修，但样本只有设置、时钟两个 App。
- **流程本身的假设**：`ClockFlows` 按 24 小时制、ColorOS「法定工作日」写的；12 小时制、其他机型没测。
- 方案里「本期不做」的仍然没做：示教录制、`takeScreenshot`。
