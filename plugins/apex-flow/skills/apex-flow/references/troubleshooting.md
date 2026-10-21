# 读运行结果和轨迹

`run` 输出实时轨迹，最后一行是结果。每行格式：`+秒数 类型 内容`。

| 类型 | 含义 |
|---|---|
| `RUN_START` | 流程概况：起始状态、所有状态、拦截器（`(global)` 是全局的） |
| `STATE_ENTER` | 进入状态，带当时的页面概况（包名、Activity、窗口数） |
| `ACTION` | 每个动作做了什么、结果、耗时。成功时写明点了哪个节点、用的哪种方式 |
| `INTERCEPTOR` | 拦截器触发 |
| `WAIT` | 在等什么，以及**每个候选状态为什么还不匹配**（页面有变化时记一次，不变时每 5 秒记一次） |
| `RETRY` / `RESUME` | 页面无变化重跑动作 / 从软失败的动作续跑 |
| `LOOP` | 几个状态在没有变化的页面上一圈圈轮流进入：它们的条件同时匹配同一页，或者动作没改变页面（轮询类流程可以忽略） |
| `SERVICE` | 无障碍服务断开或恢复 |
| `SCREEN_DUMP` | 失败时最后一屏的节点树 |
| `RUN_END` | 结束状态、路径、拦截器次数 |

## 按结果处理

| 结果 | 常见原因 | 怎么查 |
|---|---|---|
| `SUCCESS` / `FAILURE` | 走到了 success / failure 终止状态 | FAILURE 是你设计的失败分支，确认它确实该失败 |
| `TIMEOUT` | 下一状态一直没出现 | 看最后几条 `WAIT`：哪个候选差了什么条件（`match all but ...`、`no node matches (...)`）。对照 `SCREEN_DUMP` 看页面上实际是什么。常见：文字不完全一样（空格、全角、换行）、节点不可见要 `visible:false`、页面停在了没列进 `next` 的地方、弹窗挡住没拦截器 |
| `ACTION_FAILED` | 动作硬失败：目标不可输入、不是滑块、启动不了 App | 看 `ACTION` 行的原因；launchApp 失败多半是宿主没声明 `<queries>` |
| `MAX_STEPS` | 状态之间来回跳 | 看路径和 `LOOP` 提示：两个状态条件都匹配同一个页面，或者动作没有让页面前进 |
| `ERROR` | 自定义条件/动作抛异常，或者内部错误 | 看消息 |
| `SERVICE_UNAVAILABLE` | 无障碍服务断开 | `doctor`；常见原因是 uiautomator / uiauto.dev / Appium 在运行，或宿主被强制停止过 |
| `BUSY` | 已经有流程在跑 | 等它结束，或者 `raw {"cmd": "cancel"}` |

## 动作的常见失败

- `target not found: ...` —— 软失败，引擎会在页面变化后续跑。持续出现说明选择器不对，或者目标在屏幕外（先 `scrollTo`）。
- `target is outside the screen (page still animating?)` —— 页面切换动画期间节点还在屏幕外，引擎会等下一轮再试，一般不用管。
- `already at the end of ...` / `reached the end of ...` —— 列表已经到头。`scrollTo` 换个方向，或者目标不在这个列表里。
- `... is not scrollable now (its content fits on the screen)` —— 列表一屏放得下，现在不能滚；目标不在屏幕上就是真的没有。SDK 不会改去滚外层容器（外层常是左右翻页的 ViewPager，会翻到别的标签页）。
- `no scrollable list on screen; skipped ViewPager ...` —— 没写容器时不会滚左右翻页的容器；要翻页就把它写成 `container`。
- `skipped ACTION_CLICK on ancestor ...: it is a container` —— 目标本身不可点击，可点击的是整个列表 / 月视图，所以按目标中心坐标点了。这是正常的处理，不用管。
- `performAction returned false` —— 节点不接受这个操作。`click` 的 auto 模式会自动退到坐标点击；`scroll` 会自动换方向或滑动；`setProgress` 在值没变化时也会返回 false（已经处理）。
- `stale node (not from the latest snapshot)` / `node no longer exists` —— 页面在动作之间变了，引擎会重试，一般不用管。
- 动作成功但页面没变化：点到了不响应的地方（选择器命中的是别的同名节点？）、页面还在加载（加 `delayMs` 或拆成两个状态）。

## 探索时的设备问题

- **服务已开启但没连接**：UiAutomation 类工具正在运行，关掉后等几秒。
- **服务不见了**：宿主 App 被强制停止过（设置里的「强行停止」、`am force-stop`、清理加速类 App）。要人重新开启。
- **截图全黑**：页面设置了 FLAG_SECURE（支付、密码页），只能看节点树。
- **屏幕变暗 / 熄屏**：长时间没操作屏幕会变暗（`observe` 会提示 DIM），这时亮度类读数会变（例如亮度滑块显示 0），保存的样本也不准。任何一次操作都会恢复；熄屏时用 `adb shell input keyevent KEYCODE_WAKEUP` 唤醒，有锁屏密码要人解锁。
- **ColorOS / 部分 ROM**：adb 不能 `settings put secure`、`pm grant`，权限要在界面上授予；安装 APK 需要在手机上确认。
