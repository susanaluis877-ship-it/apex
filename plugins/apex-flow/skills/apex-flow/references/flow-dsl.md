# Apex 流程 Java DSL 速查

DSL 和 JSON 描述的是同一个模型（[flow-json.md](flow-json.md) 里的语义、默认值、常用写法都适用），DSL 多了一样东西：**可以直接写代码**——内联的条件 `Match.custom(screen -> ...)` 和动作 `Actions.run(ctx -> ...)`，复杂场景用它们。

```java
import static com.github.susanaluis877.apex.action.Actions.*;
import static com.github.susanaluis877.apex.match.Match.*;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.match.By;

public final class ShopFlows {

    private static final String PKG = "com.example.shop";

    /** 领取今日签到奖励；今天已经签过就直接成功。 */
    public static Flow dailyCheckin() {
        return new Flow.Builder("shop.daily_checkin")
                .start("launch")
                .timeoutMs(120_000)
                .state("launch", s -> s.match(always()).action(launchApp(PKG)).next("home"))
                .state("home", s -> s
                        .match(allOf(pkg(PKG), has(By.id("home_tab_bar"))))
                        .action(scrollTo(By.text("每日签到")).optional(),
                                scrollTo(By.text("每日签到"), null, Action.Direction.BACKWARD, 5),
                                click(By.text("每日签到")))
                        .next("already", "checkin"))
                .state("already", s -> s.match(has(By.text("今日已签到"))).endSuccess())
                .state("checkin", s -> s
                        .match(has(By.text("立即签到").enabled(true)))
                        .action(click(By.text("立即签到")))
                        .next("done")
                        .maxRetries(0))
                .state("done", s -> s.match(has(By.textContains("签到成功"))).endSuccess())
                .interceptor("close_popup", i -> i
                        .match(has(By.id("popup_close")))
                        .action(click(By.id("popup_close"))))
                .build();
    }
}
```

调试时直接按名字引用这个方法：`run com.example.shop.flows.ShopFlows#dailyCheckin`（改了代码要先 `deploy`）。方法要是 **static、无参、返回 Flow**；也可以写成类里的静态 `flow()` 方法，或者实现 `Supplier<Flow>` 的类。

## 对照表

| JSON | DSL |
|---|---|
| `{"id", "start", "timeoutMs", "maxSteps", "maxInterceptions"}` | `new Flow.Builder(id).start(...).timeoutMs(...).maxSteps(...).maxInterceptions(...)` |
| `"defaults": {...}` | `.defaults(d -> d.timeoutMs(...).retryIntervalMs(...).maxRetries(...).actionGapMs(...).interceptorMaxTimes(...).interceptorCooldownMs(...))` |
| 状态 | `.state(id, s -> s.match(...).action(a1, a2).next("x", "y").timeoutMs(...).retryIntervalMs(...).maxRetries(...))` |
| `"end": "success"` / `"failure"` | `.endSuccess()` / `.endFailure()` |
| 拦截器 | `.interceptor(id, i -> i.match(...).action(...).maxTimes(...).cooldownMs(...))` |
| `useGlobalInterceptors` / `excludeGlobalInterceptors` | `.useGlobalInterceptors(false)` / `.excludeGlobalInterceptors("id", ...)` |
| `{"package": p}` `{"activity": a}` | `pkg(p)` `activity(a)` |
| `{"has": sel}` / `{"has": [a, b]}` | `has(sel)` / `has(a, b)` |
| `not` `allOf` `anyOf` `always` | `not(m)` `allOf(m1, m2)` `anyOf(m1, m2)` `always()` |
| `{"custom": "name"}` | `Match.custom("name")`（静态导入 Actions 时会和 `Actions.custom` 冲突，写全类名） |
| — | `Match.custom(screen -> ...)`：内联条件，拿到 `ScreenSnapshot`，返回 true 表示匹配 |
| 选择器 `{"text": ..., "enabled": true}` | `By.text(...).enabled(true)`，其他键同名：`textContains`、`textMatches`、`desc*`、`id`、`className`、`clickable`、`checked`、`selected`、`scrollable`、`stateDesc`、`progressAtLeast`、`progressAtMost` |
| `"hasDescendant": [a, b]` / `"inside": x` | `.hasDescendant(a, b)` / `.inside(x)`；也可以 `By.hasDescendant(...)` 开头 |
| `"visible": false` | `.visible(false)` |
| `click` / `longClick`，`mode` | `click(sel)` / `longClick(sel)` / `click(sel, Action.ClickMode.GESTURE)` |
| `tap` | `tap(x, y)` / `tapRatio(0.5, 0.9)` |
| `swipe` | `swipe(x1, y1, x2, y2)` / `swipeRatio(0.5, 0.8, 0.5, 0.2)` |
| `scroll` | `scroll(Action.Direction.FORWARD)` / `scroll(sel, dir)` |
| `scrollTo` | `scrollTo(target)` / `scrollTo(target, container, dir, maxSwipes)`（container 可以是 null） |
| `setText` | `setText(sel, "text")` |
| `setProgress` | `setProgress(sel, value)` / `setProgressRatio(sel, 1)` |
| `back` `home` `recents` `notifications` `launchApp` `sleep` | 同名方法 |
| `{"type": "custom", "name": ...}` | `Actions.custom("name")` |
| — | `run(ctx -> ...)`：内联动作，返回 true 成功、false 软失败（会续跑），抛异常结束流程 |
| `"optional": true` `"delayMs": 500` | `.optional()` `.delayMs(500)` |

## 内联代码

```java
// 条件：余额 >= 100 才去提现
.match(Match.custom(screen -> {
    UiNode balance = By.id("tv_balance").findFirst(screen);
    String digits = balance == null || balance.getText() == null ? "" : balance.getText().replaceAll("[^0-9]", "");
    return !digits.isEmpty() && Long.parseLong(digits) >= 100;
}))
// 动作：把列表里所有「领取」都点一遍
.action(run(ctx -> {
    for (UiNode node : By.text("领取").findAll(ctx.screen())) {
        if (ctx.isCancelled() || !ctx.click(node)) {
            return false;
        }
        ctx.sleep(300);
    }
    return true;
}))
```

`ActionContext`：`screen()` 当前快照，`refresh()` 重新抓，`click(node)`，`perform(action)` 执行内置动作，`sleep(ms)`，`isCancelled()`。条件函数每轮可能调用多次，不要做耗时操作；动作函数在引擎线程上同步执行，循环里检查 `isCancelled()`。动作里不能再 `perform` 另一个 `run(...)` / `custom(...)`。

## 什么时候用内联代码

内置的条件和动作能表达的就别写代码（能转 JSON、更好读）。需要这些时才写：

- 比较数值、日期、计算（余额够不够、倒计时还剩多少）。
- 在列表里按规则挑一项（价格最低的、最新的一条）。
- 需要循环、或者根据页面上的内容决定做什么。

流程里有内联代码就不能导出成 JSON；需要 JSON 时把这段代码用 `Apex.registerMatcher` / `registerAction` 注册成名字，在流程里用 `custom("名字")` 引用。

## 导出 JSON（只在需要时）

`export com.foo.Flows#method -o app/src/main/assets/flows/x.json`：在设备上把 DSL 写成 JSON，并确认解析回来和原流程完全相等。项目要远程下发、要放进 assets、要推到 runner 时才导出；DSL 仍然是唯一的源头，JSON 不要手改，DSL 改了就重新导出。
