# Apex 流程 JSON 速查

流程是一张状态图：每个状态 = 页面条件 `match` + 进入后执行的 `actions` + 有序的候选下一状态 `next`；拦截器处理随时可能出现的弹窗。
任何位置出现未知的键都会报错（用来抓拼写错误），值为 `null` 的键视为没写。

## 顶层

```json
{
  "id": "shop.daily_checkin",
  "start": "launch",
  "timeoutMs": 120000,
  "maxSteps": 30,
  "defaults": {"timeoutMs": 10000, "retryIntervalMs": 3000, "maxRetries": 2, "actionGapMs": 300},
  "states": [...],
  "interceptors": [...],
  "useGlobalInterceptors": true,
  "excludeGlobalInterceptors": ["close_ad"]
}
```

| 键 | 默认 | 说明 |
|---|---|---|
| `timeoutMs` | 300000 | 整个流程的超时 |
| `maxSteps` | 100 | 最多进入状态的次数，防止来回跳 |
| `maxInterceptions` | 30 | 所有拦截器合计最多触发几次 |
| `defaults.timeoutMs` | 10000 | 每个状态等下一状态出现的最长时间 |
| `defaults.retryIntervalMs` / `maxRetries` | 3000 / 2 | 页面毫无变化多久后重跑动作、最多几次 |
| `defaults.actionGapMs` | 300 | 同一状态里相邻动作的间隔 |
| `defaults.interceptorMaxTimes` / `interceptorCooldownMs` | 5 / 1500 | 拦截器默认值 |

## 状态

```json
{"id": "checkin_page", "match": {...}, "actions": [...], "next": ["checked_in", "already_checked"],
 "timeoutMs": 15000, "retryIntervalMs": 3000, "maxRetries": 2}
{"id": "done", "match": {...}, "end": "success"}
```

- `next` 是有序列表，按顺序检查，**第一个匹配的胜出**。同一页面的不同分支要拆成 `match` 互斥的状态。
- `end`：`success` 或 `failure`，执行完动作就结束，不能有 `next`。终止状态的动作失败也会结束（尽力而为）。
- 不能有自环（A 的 next 里不能有 A），所有状态要能从 `start` 到达，至少一个终止状态。
- 动作什么时候执行：进入状态时一次；某个动作软失败（目标还没出现）时，页面一变或过了 `retryIntervalMs` 就从它续跑；页面在 `retryIntervalMs` 内毫无变化时重跑全部动作，最多 `maxRetries` 次。**不能重复执行的动作（下单、提交）所在状态设 `"maxRetries": 0`。**

## 拦截器

```json
{"id": "perm_allow", "match": {"package": "com.android.permissioncontroller", "has": {"id": "permission_allow_button"}},
 "actions": [{"type": "click", "target": {"id": "permission_allow_button"}}], "maxTimes": 3, "cooldownMs": 1500}
```

每一轮先检查拦截器再检查状态：弹窗盖住页面时先处理弹窗，处理完回到原来的状态，不重跑它的动作。条件更具体的放前面。弹窗本身是流程的一步时，在拦截器条件里用 `not` 排除它。

## 页面条件 match（同一对象里多个键是 AND）

| 键 | 示例 | 说明 |
|---|---|---|
| `package` | `"com.android.settings"` | 最上层应用窗口的包名，建议每个状态都写 |
| `activity` | `".SubSettings"` | 会滞后、单 Activity 应用不区分页面，只当辅助 |
| `has` | `{"text": "每日签到"}` 或 `[{...}, {...}]` | 存在匹配的节点；数组表示每个都要存在 |
| `not` | `{"has": {"text": "加载中"}}` | |
| `allOf` / `anyOf` | `[{...}, {...}]` | |
| `always` | `true` | 任何屏幕都匹配，常用于起始状态 |
| `custom` | `"isLoggedIn"` 或 `{"name": ..., "args": {...}}` | 宿主注册的自定义条件（`doctor` 会列出） |

## 选择器（同一对象里多个键是 AND）

| 键 | 说明 |
|---|---|
| `text` / `textContains` / `textMatches` | 文本相等 / 包含 / 整段匹配正则（Java 正则，整段匹配） |
| `desc` / `descContains` / `descMatches` | contentDescription，同上 |
| `id` | 短名 `slider` 匹配任意包的 `:id/slider`；全名 `com.foo:id/slider` 精确匹配 |
| `className` | 简单类名 `SeekBar` 或全名 |
| `clickable` / `enabled` / `checked` / `selected` / `scrollable` | 状态过滤 |
| `stateDesc` / `stateDescContains` | 状态描述（API 30+），例如开关的「已开启」、滑块的「100%」 |
| `progressAtLeast` / `progressAtMost` | 滑块、进度条的当前值在范围内的比例（0~1），例如 `{"id": "volume", "progressAtLeast": 1}` 表示已经拉满；没有范围的节点不匹配 |
| `hasDescendant` | 选择器或选择器数组：节点下面要有匹配每一个的子孙。表达「同一个列表项里同时有 A 和 B」 |
| `inside` | 选择器：节点要在匹配它的某个祖先里面。表达「那一行里的开关」 |
| `visible` | 默认只匹配可见节点；`false` 表示可见不可见都行（滚轮选择器等虚拟节点要写） |

多个节点匹配时取第一个：窗口从上到下、窗口内先序遍历。

## 动作

所有动作都可以加 `"optional": true`（软失败、硬失败都跳过，继续下一个动作；ERROR 和取消仍会结束流程）和 `"delayMs": 500`（执行前先等）。

坐标类的兜底（点击退到坐标、滑块按比例点、滚动退到滑动）在目标中心不在屏幕内时不会执行，而是软失败等下一轮——页面切换动画期间节点可能还在屏幕外。

| type | 参数 | 说明 |
|---|---|---|
| `click` / `longClick` | `target`，`mode`: `auto`/`node`/`gesture` | auto：先对目标或最近的可点击祖先做节点点击，不行再按中心坐标点 |
| `setText` | `target`, `text` | 输入框 |
| `setProgress` | `target` + `value`（绝对值）或 `ratio`（0~1） | 滑块。目标是包着滑块的行也行；已经是目标值时直接成功；不支持直接设值时按比例位置点 |
| `scrollTo` | `target`，可选 `container`、`direction`（默认 `forward`）、`maxSwipes`（默认 10） | 一直滚动直到 target 出现；滚到头（列表报告不能再滚，或内容不再变化）或次数用完是**软失败** |
| `scroll` | `direction`: `forward`/`backward`/`up`/`down`/`left`/`right`，可选 `target` | 滚一次。方向动作不支持时自动退到 forward/backward，再不行就滑动；已经到头时软失败 |
| `tap` | `x`,`y` 或 `xRatio`,`yRatio` | 坐标点击，最后的手段 |
| `swipe` | `from`,`to` 或 `fromRatio`,`toRatio`，可选 `durationMs` | |
| `back` / `home` / `recents` / `notifications` | | 全局动作 |
| `launchApp` | `package` | 用宿主 App 启动（宿主要声明 `<queries>`） |
| `sleep` | `ms` | |
| `custom` | `name`, `args` | 宿主注册的自定义动作 |

## 常用写法

**从任何起点开始**：起始状态无条件匹配，启动目标 App，再识别首页。

```json
{"id": "launch", "match": {"always": true}, "actions": [{"type": "launchApp", "package": "com.android.settings"}],
 "next": ["home"]},
{"id": "home", "match": {"package": "com.example.shop", "has": {"id": "home_tab_bar"}}, "actions": [
   {"type": "scrollTo", "target": {"text": "每日签到"}, "maxSwipes": 5, "optional": true},
   {"type": "scrollTo", "target": {"text": "每日签到"}, "direction": "backward", "maxSwipes": 5},
   {"type": "click", "target": {"text": "每日签到"}}], "next": ["checkin_page"]}
```

列表可能停在上次的滚动位置，所以入口不在屏幕上时先往下找（`optional`，没找到就跳过），再往上找。

`launchApp` 之后落在哪一页因 App 和 ROM 而异：有的回到首页（例如 ColorOS 的设置），有的停在上次的深层页面。写流程前先实测：把目标 App 停在深层页面，再执行 `launch` 看落在哪。会停在深层页面的，把那些页面也列进 `next`（用 `back` 回到首页的状态），并在自测里真的从深层页面跑一次。

**目标已经达成时直接成功**（流程可以重复跑）：

```json
{"id": "settings", "match": {...}, "next": ["already_on", "off"]},
{"id": "already_on", "match": {"has": {"id": "notify_switch", "checked": true}}, "end": "success"},
{"id": "off", "match": {"has": {"id": "notify_switch", "checked": false}},
 "actions": [{"type": "click", "target": {"id": "notify_switch"}}], "next": ["turned_on"]},
{"id": "turned_on", "match": {"has": {"id": "notify_switch", "checked": true}}, "end": "success"}
```

注意：`already_on` 和 `turned_on` 的条件一样，但不冲突——它们是不同状态的候选。

**滚轮选择器**（时间、日期）：先在探索时拨一下确认方向，再用 `scrollTo` 拨到目标值。

```json
{"type": "scrollTo", "target": {"textMatches": "2025年.*", "visible": false},
 "container": {"textMatches": "\\d+年.*", "visible": false}, "direction": "backward", "maxSwipes": 30}
```

**滑块拉到最大**：`{"type": "setProgress", "target": {"id": "volume"}, "ratio": 1}`；判断已经到最大用 `{"has": {"id": "volume", "progressAtLeast": 1}}`，不要把具体的最大值写死（不同机型不一样）。

**同一个列表项里的多个条件**：分开写两个 `has` 只说明页面上各有一个，可能分属两行。要求在同一行时用 `hasDescendant`；要操作那一行里的控件用 `inside`：

```json
{"has": {"id": "order_item", "hasDescendant": [{"text": "已发货"}, {"textContains": "运动鞋"}]}}
{"type": "click", "target": {"id": "item_switch", "inside": {"id": "order_item", "hasDescendant": {"textContains": "运动鞋"}}}}
```

目标那一行可能在屏幕外：判断「是否已存在」之前先 `scrollTo` 到它（加 `optional`），否则列表长了会误判为不存在。

**滚轮通常是循环的**：两个方向都能拨，但写流程时不知道当前值离目标哪边近，就固定往一个方向拨、`maxSwipes` 留够一整圈（例如分钟 59 格，每格约 0.5 秒），状态的 `timeoutMs` 按最坏耗时留够。目标值的写法要和滚轮显示的一致（常见补零：`07` 而不是 `7`）；`scrollTo` 失败时会列出滚动中见过的值，照着改。

**系统权限弹窗**：做成拦截器，按功能需要选择允许或拒绝。
