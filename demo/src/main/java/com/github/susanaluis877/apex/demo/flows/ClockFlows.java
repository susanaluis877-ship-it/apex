package com.github.susanaluis877.apex.demo.flows;

import static com.github.susanaluis877.apex.action.Actions.click;
import static com.github.susanaluis877.apex.action.Actions.launchApp;
import static com.github.susanaluis877.apex.action.Actions.scrollTo;
import static com.github.susanaluis877.apex.action.Actions.setText;
import static com.github.susanaluis877.apex.match.Match.allOf;
import static com.github.susanaluis877.apex.match.Match.always;
import static com.github.susanaluis877.apex.match.Match.has;
import static com.github.susanaluis877.apex.match.Match.not;
import static com.github.susanaluis877.apex.match.Match.pkg;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.ScreenMatcher;
import com.github.susanaluis877.apex.match.Selector;

/**
 * 系统时钟 App（ColorOS 的 com.coloros.alarmclock，在 OPPO PHW110 / Android 13 上实测）里的流程。
 */
public final class ClockFlows {

    private static final String PKG = "com.coloros.alarmclock";

    private static final String NAME = "Apex";
    /** 列表里闹钟下面那行文字：「工作日类型 | 名称」。 */
    private static final String LABEL = "法定工作日 | " + NAME;

    /** 底部导航的「闹钟」标签。 */
    private static final Selector ALARM_TAB = By.id("normalLable").text("闹钟");
    /** 闹钟列表右下角的添加按钮，只在「闹钟」标签页可见。 */
    private static final Selector ADD_BUTTON = By.desc("添加闹钟");
    /** 列表里 07:30、法定工作日、名称 Apex 的那张卡片。用 alarm_item_card_bg 限定在同一张卡片里。 */
    private static final Selector APEX_ROW = By.id("alarm_item_card_bg")
            .hasDescendant(By.id("timeDisplay").text("07:30"), By.id("daysOfWeek_label").text(LABEL));
    /** 那张卡片里的开关。 */
    private static final Selector APEX_SWITCH = By.id("alarm_switch").inside(APEX_ROW);
    private static final Selector ALARM_LIST = By.id("list");
    /** 多选模式左上角的「取消」。 */
    private static final Selector SELECTION_CANCEL = By.id("coui_toolbar_back_view").desc("取消");

    /** 时间滚轮是虚拟节点（不可见），文字是「07小时选择器项目 使用两指 上下滑动选择」，小时补零。 */
    private static final Selector HOUR_WHEEL = By.textMatches("\\d+小时选择器项目.*").visible(false);
    private static final Selector HOUR_07 = By.textMatches("07小时选择器项目.*").visible(false);
    private static final Selector MINUTE_WHEEL = By.textMatches("\\d+分钟选择器项目.*").visible(false);
    private static final Selector MINUTE_30 = By.textMatches("30分钟选择器项目.*").visible(false);

    /** 「工作日响铃」选项卡：选中时是 checked 的 Button，没选中时是未勾选的 CompoundButton。 */
    private static final Selector WORKDAY_TAB = By.text("工作日响铃");
    private static final Selector WORKDAY_TYPE = By.id("add_alarm_work_day_sub");

    /** 「闹钟」标签页：闹钟标签选中，看得到添加按钮。 */
    private static final ScreenMatcher LIST_PAGE = allOf(pkg(PKG),
            has(ALARM_TAB.selected(true)), has(ADD_BUTTON));
    /** 新建 / 编辑闹钟的底部面板（有取消、确认和名称输入框）。 */
    private static final ScreenMatcher EDITOR_PANEL = allOf(pkg(PKG),
            has(By.id("cancel"), By.id("save"), By.id("alarm_label")));
    private static final ScreenMatcher NEW_EDITOR = allOf(EDITOR_PANEL, has(By.text("新建闹钟")));

    private ClockFlows() {
    }

    /**
     * 新建一个闹钟：工作日（法定工作日）早上 07:30 响，名称 Apex。
     * 已经有这个闹钟且开着就直接成功；有但关着就打开它。
     *
     * <pre>
     * launch ─▶ alarm_list（或先经过 other_tab / stale_editor / selection_mode 回到 alarm_list）
     * alarm_list ─▶ exists_on (success)
     *            ─▶ exists_off ─打开开关─▶ turned_on (success)
     *            ─▶ absent ─添加─▶ editor ─▶ editor_ready ─确认─▶ verify ─▶ saved (success)
     *                              editor ─▶ workday_type_wrong ─▶ workday_dialog ─▶ editor_ready（或回 editor）
     *
     * other_tab：上次停在世界时钟 / 秒表 / 计时器；stale_editor：编辑面板还开着；selection_mode：上次长按留下的多选模式。
     * </pre>
     */
    public static Flow addWorkdayAlarm() {
        return new Flow.Builder("clock.add_workday_alarm")
                .start("launch")
                .timeoutMs(180_000)
                .maxSteps(30)
                .state("launch", s -> s
                        .match(always())
                        .action(launchApp(PKG))
                        .next("alarm_list", "other_tab", "stale_editor", "selection_mode"))
                // launchApp 会停在上次离开的标签页
                .state("other_tab", s -> s
                        .match(allOf(pkg(PKG), has(ALARM_TAB.selected(false))))
                        .action(click(ALARM_TAB))
                        .next("alarm_list"))
                // 防御：实测 launchApp 会把开着的编辑面板关掉，这个状态在 PHW110 上走不到
                .state("stale_editor", s -> s
                        .match(EDITOR_PANEL)
                        .action(click(By.id("cancel")))
                        .next("alarm_list"))
                // 上次长按留下的多选模式，launchApp 不会退出它：点左上角「取消」，绝不点「删除」
                .state("selection_mode", s -> s
                        .match(allOf(pkg(PKG), has(By.id("alarm_select"), SELECTION_CANCEL)))
                        .action(click(SELECTION_CANCEL))
                        .next("alarm_list"))
                // 列表长的时候目标那一行可能在屏幕外，先往下、再往上找一遍
                .state("alarm_list", s -> s
                        .match(LIST_PAGE)
                        .action(scrollTo(APEX_ROW, ALARM_LIST, Action.Direction.FORWARD, 10).optional(),
                                scrollTo(APEX_ROW, ALARM_LIST, Action.Direction.BACKWARD, 10).optional())
                        .next("exists_on", "exists_off", "absent"))
                .state("exists_on", s -> s
                        .match(allOf(LIST_PAGE, has(APEX_SWITCH.checked(true))))
                        .endSuccess())
                .state("exists_off", s -> s
                        .match(allOf(LIST_PAGE, has(APEX_SWITCH.checked(false))))
                        .action(click(APEX_SWITCH))
                        .next("turned_on"))
                .state("turned_on", s -> s
                        .match(allOf(LIST_PAGE, has(APEX_SWITCH.checked(true))))
                        .endSuccess())
                .state("absent", s -> s
                        .match(allOf(LIST_PAGE, not(has(APEX_ROW))))
                        .action(click(ADD_BUTTON))
                        .next("editor"))
                // 滚轮默认是当前时间，往回拨；滚轮是循环的，最坏小时拨 23 格、分钟拨 59 格
                .state("editor", s -> s
                        .match(NEW_EDITOR)
                        .action(scrollTo(HOUR_07, HOUR_WHEEL, Action.Direction.BACKWARD, 24),
                                scrollTo(MINUTE_30, MINUTE_WHEEL, Action.Direction.BACKWARD, 60),
                                click(WORKDAY_TAB.checked(false)).optional(),
                                setText(By.id("alarm_label"), NAME))
                        .next("editor_ready", "workday_type_wrong")
                        .timeoutMs(90_000))
                // 工作日类型默认是法定工作日；万一不是，打开选择框改回来
                .state("workday_type_wrong", s -> s
                        .match(allOf(NEW_EDITOR, has(WORKDAY_TAB.checked(true), WORKDAY_TYPE),
                                not(has(WORKDAY_TYPE.text("法定工作日")))))
                        .action(click(By.id("add_alarm_work_day_layout")))
                        .next("workday_dialog"))
                .state("workday_dialog", s -> s
                        .match(allOf(pkg(PKG), has(By.textContains("将智能跳过法定节假日"), By.text("法定工作日"))))
                        .action(click(By.text("法定工作日")))
                        .next("editor_ready", "editor"))
                // 保存前核对所有设置；确认按钮不能重复点（会建出两个闹钟）
                .state("editor_ready", s -> s
                        .match(allOf(NEW_EDITOR, has(HOUR_07, MINUTE_30, WORKDAY_TAB.checked(true),
                                WORKDAY_TYPE.text("法定工作日"), By.id("alarm_label").text(NAME))))
                        .action(click(By.id("save")))
                        .next("verify")
                        .maxRetries(0))
                .state("verify", s -> s
                        .match(LIST_PAGE)
                        .action(scrollTo(APEX_ROW, ALARM_LIST, Action.Direction.FORWARD, 10).optional(),
                                scrollTo(APEX_ROW, ALARM_LIST, Action.Direction.BACKWARD, 10).optional())
                        .next("saved"))
                .state("saved", s -> s
                        .match(allOf(LIST_PAGE, has(APEX_SWITCH.checked(true))))
                        .endSuccess())
                .build();
    }
}
