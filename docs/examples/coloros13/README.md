# ColorOS 13 上 AI 写出的流程

验收「让 AI 编写流程」时，全新的 AI 会话只拿到一句需求和 apex-flow 插件，自己在真机（OPPO PHW110，ColorOS 13.1，Android 13）上探索、编写、运行并自测，写出了这四个流程。文件原样保存，没有人工修改。

| 文件 | 需求 | 状态数 | 结果 |
|---|---|---|---|
| `dark_mode_on.json` | 在系统设置里打开深色模式；已经是深色就直接成功 | 7 | 5 次运行全部成功 |
| `brightness_max.json` | 打开系统设置，把屏幕亮度调到最高 | 5 | 4 次运行全部成功 |
| `alarm_workday_0730.json` | 在时钟里新建工作日 7:30、名称 Apex 的闹钟 | 9 | 3 次运行全部成功 |
| `alarm_delete_apex.json` | 删掉名称为 Apex 的闹钟；没有就直接成功 | 9 | 4 次运行全部成功 |

之后又在流程运行器（demo）的列表里逐个点「运行」各跑了一遍，全部成功，并用只读命令核对了实际效果（亮度变成最大、深色模式打开、闹钟新建后又被删除，用户原有的闹钟没动）。

注意：

- 这些流程依赖 ColorOS 13 的界面（id、文字、页面结构），换机型或系统版本要重新探索。
- `brightness_max.json` 写于选择器支持 `progressAtLeast` 之前，所以「已经最高」写死了这台机器的最大值 8187；现在应该写成 `{"id": "slider", "progressAtLeast": 1}`。
- `alarm_workday_0730.json` 的「工作日」用的是 ColorOS 时钟的「法定工作日」（跳过法定节假日），判断「已经存在」时用了两个独立的 `has`；现在可以用 `hasDescendant` 要求时间和名称在同一行。

后来改为 DSL 优先后，又让全新的会话在 demo 模块里用 Java DSL 写了新建闹钟的流程：`demo/src/main/java/com/github/susanaluis877/apex/demo/flows/ClockFlows.java`（在 runner 列表里叫「时钟：新建工作日 07:30 闹钟 Apex」），9 次运行全部成功。和上面的 JSON 版相比，它用常量复用选择器和页面条件，用 `hasDescendant` / `inside` 确保时间和名称在同一张卡片里，还处理了「存在但关着」「残留多选模式」等起点。
