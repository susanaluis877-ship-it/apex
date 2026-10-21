"""所有命令的实现。每个命令返回 Output（文本 + 可选的截图），CLI 打印它，MCP 把它作为工具结果返回。"""

import json
import os
import re
import time

from . import PROTOCOL, adbmode, provision, shot
from .device import Adb, Bridge, check_ok
from .errors import ApexError
from .screen import (Screen, describe_node, diff_screens, is_interactive, render_full, render_grep,
                     render_outline, short_activity)
from .selectors import best_selector, suggest
from .session import Session

PERMISSION_PACKAGES = ("com.android.permissioncontroller", "com.google.android.permissioncontroller",
                       "com.lbe.security.miui", "com.oplus.securitypermission")


class Output:
    def __init__(self, text, image=None, ok=True, image_path=None):
        self.text = text
        self.image = image
        self.ok = ok
        self.image_path = image_path


class Toolkit:

    def __init__(self, serial=None, package=None):
        self.adb = Adb(serial)
        self.bridge = Bridge(self.adb, package)

    # ---------------------------------------------------------------- 环境

    def doctor(self):
        adb, bridge = self.adb, self.bridge
        report = []
        ok = True
        devices = adb.devices()
        report.append("adb: %s，设备: %s" % (adb.path, ", ".join("%s(%s)" % d for d in devices) or "无"))
        report.append("使用设备: %s" % adb.resolve())
        sockets = bridge.sockets()
        report.append("调试桥: %s" % (", ".join("@apex_" + s for s in sockets) or "没有找到"))
        try:
            info = bridge.request({"cmd": "info"}, timeout=15)
        except ApexError as e:
            report.append("✗ 连接失败: %s" % e)
            if e.code == "NO_BRIDGE":
                report.append("  宿主 App 要是 debug 构建、调用了 Apex.init，并且进程在运行（无障碍服务开着时系统会拉起进程）")
            return Output("\n".join(report), ok=False)
        dev = info["device"]
        report.append("宿主: %s  SDK %s  协议 %s  API %s  %s" % (dev.get("package"), dev.get("sdkVersion"),
                                                              info.get("protocol"), dev.get("api"), dev.get("model")))
        if info.get("protocol") != PROTOCOL:
            ok = False
            report.append("✗ 协议版本不一致：apex.py 是 %d，设备是 %s；请更新插件或 SDK" % (PROTOCOL, info.get("protocol")))
        if not dev.get("serviceEnabled"):
            ok = False
            report.append("✗ 无障碍服务没有开启：请人在手机的 设置 → 无障碍 里开启宿主 App 的服务")
        elif not dev.get("serviceConnected"):
            ok = False
            report.append("✗ 服务已开启但没有连接。常见原因：uiautomator / ATX(uiauto.dev) / Appium 正连着 UiAutomation，"
                          "它们会让所有无障碍服务暂时断开；关掉它们后稍等几秒")
            hint = self._automation_processes()
            if hint:
                report.append("  可疑进程: " + hint)
        else:
            report.append("✓ 无障碍服务已连接")
        if dev.get("running"):
            report.append("! 正在运行流程 %s" % dev["running"])
        wake, policy = self.screen_state()
        if wake:
            report.append("屏幕: %s / %s%s" % (wake, policy or "?", "" if wake == "Awake" and policy in (None, "BRIGHT")
                                                else "（不是正常亮屏，见 observe 的提示）"))
        report.append("宿主注册的自定义动作: %s；自定义条件: %s" % (", ".join(info.get("customActions") or []) or "无",
                                                          ", ".join(info.get("customMatchers") or []) or "无"))
        globals_ = info.get("globalInterceptors") or []
        report.append("全局拦截器: %s" % (", ".join(globals_) + "（作用于所有流程，这些弹窗写流程时不用再处理）"
                                     if globals_ else "无"))
        return Output("\n".join(report), ok=ok)

    def screen_state(self):
        """屏幕电源状态：(wakefulness, policy)，例如 ("Awake", "BRIGHT")、("Awake", "DIM")、("Asleep", "OFF")。读不到时为 None。"""
        try:
            out = self.adb.shell("dumpsys power | grep -m1 mWakefulness=; dumpsys display | grep -m1 -o 'policy=[A-Z]*'",
                                 timeout=10)
        except ApexError:
            return None, None
        wake = re.search(r"mWakefulness=(\w+)", out)
        policy = re.search(r"policy=([A-Z]+)", out)
        return (wake.group(1) if wake else None), (policy.group(1) if policy else None)

    def _screen_warning(self):
        wake, policy = self.screen_state()
        if wake and wake != "Awake":
            return ("屏幕处于 %s 状态（熄屏或休眠），看到的可能是锁屏或旧内容。可以 `adb shell input keyevent KEYCODE_WAKEUP` "
                    "唤醒；有锁屏密码时请人解锁" % wake)
        if policy in ("DIM", "DOZE"):
            session = Session.current()
            last = session.meta.get("dim_warned_step")
            if last is not None and session.meta["steps"] - last < 8:
                return None
            session.meta["dim_warned_step"] = session.meta["steps"]
            session.save()
            return ("屏幕处于 %s 状态（一段时间没有触摸，系统把屏幕调暗了）。影响亮度类读数（例如亮度滑块显示 0）和截图亮度，"
                    "页面结构不受影响。节点点击不算触摸，坐标点击、滑动才会让它恢复；保存亮度相关的样本前先确认屏幕是亮的"
                    % policy)
        return None

    def _automation_processes(self):
        try:
            out = self.adb.shell("ps -A -o NAME,ARGS 2>/dev/null || ps -A")
        except ApexError:
            return ""
        hits = [l.strip() for l in out.splitlines() if re.search(r"uiautomator|atx-agent|appium|u2\.jar|maestro", l)]
        return "; ".join(hits[:3])

    # ---------------------------------------------------------------- 模式

    def apex_ready(self, timeout=5):
        """调试桥能用且无障碍服务已连接时返回 (True, None)，否则 (False, 原因)。原因决定后面用不用 adb 模式。"""
        try:
            info = self.bridge.request({"cmd": "info"}, timeout=timeout)
        except ApexError as e:
            return False, "调试桥不可用（宿主没安装、不是 debug 包，或进程没在运行）：%s" % e.code
        dev = info["device"]
        if not dev.get("serviceEnabled"):
            return False, "无障碍服务没有开启"
        if not dev.get("serviceConnected"):
            return False, "无障碍服务已开启但没有连接"
        return True, None

    def _adb_mode_reason(self):
        ready, why = self.apex_ready()
        if ready:
            return None
        if why == "无障碍服务已开启但没有连接":
            # 刚安装或刚开启时服务马上就会连上；这时用 uiautomator 反而会让它断开，所以先等一会儿
            for _ in range(10):
                time.sleep(0.5)
                ready, why = self.apex_ready()
                if ready:
                    return None
        return why

    def _adb_observe(self, why, screenshot):
        data = adbmode.dump(self.adb)
        self._save_adb_last(data)
        screen = Screen(data)
        lines = ["⚠ adb 模式（%s）：用 uiautomator 看页面、用 input 操作，只用于安装确认、开启无障碍服务这类准备工作。"
                 "这里的 @引用和选择器不经过 Apex 验证，不要写进流程；服务连上后会自动换回 Apex。" % why]
        out = Output("\n".join(lines) + "\n" + render_outline(screen))
        if screenshot not in (None, "none"):
            try:
                png = shot.screenshot(self.adb, self._boxes(screen) if screenshot == "annotated" else None)
                path = os.path.join(self._cache_dir(), "adb-shot.png")
                with open(path, "wb") as f:
                    f.write(png)
                out.image, out.image_path = png, path
                out.text += "\n截图: %s" % path
            except ApexError as e:
                out.text += "\n（截图失败: %s）" % e
        return out

    def _adb_act(self, action, why, screenshot):
        """adb 模式下的动作：点击 @引用 / 简单选择器（text、desc、id）/ 坐标，滑动，按键，输入 ASCII 文字，打开应用。"""
        if isinstance(action, dict):
            parts = None
            act = action
        else:
            text = str(action).strip()
            parts = _split(text) if not text.startswith("{") else None
            act = _json(text) if parts is None else None
        adb = self.adb
        if parts:
            verb, rest = parts[0], parts[1:]
            if verb in ("back", "home", "recents"):
                adbmode.key(adb, verb)
            elif verb == "tap":
                adbmode.tap(adb, int(rest[0]), int(rest[1]))
            elif verb == "swipe":
                adbmode.swipe(adb, *[int(v) for v in rest[:4]])
            elif verb == "launch":
                provision.launch_host(adb, rest[0])
            elif verb == "setText":
                n = self._adb_target(rest[0])
                adbmode.tap(adb, *n.center)
                adbmode.text(adb, " ".join(rest[1:]))
            elif verb in ("click", "longClick"):
                n = self._adb_target(rest[0])
                if verb == "click":
                    adbmode.tap(adb, *n.center)
                else:
                    adbmode.swipe(adb, n.center[0], n.center[1], n.center[0], n.center[1], 800)
            else:
                raise ApexError("BAD_REQUEST", "adb 模式只支持 click、longClick、setText、tap、swipe、back、home、"
                                               "recents、launch（%s）" % why)
        else:
            kind = act.get("type")
            if kind in ("back", "home", "recents"):
                adbmode.key(adb, kind)
            elif kind in ("click", "longClick") and isinstance(act.get("target"), dict):
                n = self._adb_target(act["target"])
                adbmode.tap(adb, *n.center)
            elif kind == "launchApp":
                provision.launch_host(adb, act["package"])
            else:
                raise ApexError("BAD_REQUEST", "adb 模式不支持 %s 动作（%s）" % (kind, why))
        time.sleep(1)
        return self._adb_observe(why, screenshot)

    def _adb_target(self, ref):
        data = self._load_adb_last()
        screen = Screen(data)
        if isinstance(ref, str) and ref.startswith("@"):
            return screen.node(ref)
        sel = ref if isinstance(ref, dict) else _json(ref)
        unsupported = set(sel) - {"text", "desc", "id"}
        if unsupported:
            raise ApexError("BAD_REQUEST", "adb 模式的选择器只支持 text、desc、id，不支持 %s" % sorted(unsupported))
        for n in screen.nodes:
            if (n.text == sel.get("text", n.text) and n.desc == sel.get("desc", n.desc)
                    and ("id" not in sel or n.id and (n.id == sel["id"] or n.id.endswith(":id/" + sel["id"])))):
                return n
        raise ApexError("BAD_REQUEST", "adb 模式下找不到 %s（只支持 text、desc、id）" % json.dumps(sel, ensure_ascii=False))

    def _cache_dir(self):
        base = os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache")
        path = os.path.join(base, "apex")
        os.makedirs(path, exist_ok=True)
        return path

    def _save_adb_last(self, data):
        with open(os.path.join(self._cache_dir(), "adb-last-%s.json" % self.adb.resolve()), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)

    def _load_adb_last(self):
        try:
            with open(os.path.join(self._cache_dir(), "adb-last-%s.json" % self.adb.resolve()), encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            raise ApexError("NO_SNAPSHOT", "no adb snapshot yet; observe first")

    # ---------------------------------------------------------------- 编译、安装、开启服务

    def deploy(self, module=None, project=None, variant="debug", timeout=60):
        """编译宿主、覆盖安装、等进程重启和无障碍服务重连。改了 Java DSL 流程后用它，再 run / check。"""
        project = provision.find_project(project)
        if not module:
            modules = provision.app_modules(project)
            if len(modules) != 1:
                raise ApexError("BUILD", "found app modules %s in %s; pass --module" % (modules, project))
            module = modules[0]
        try:
            apk, build_s = provision.build(project, module, variant)
        except ApexError as e:
            return Output("✗ 编译失败\n" + e.message, ok=False)
        package = provision.apk_package(apk)
        old_pid = provision.pid_of(self.adb, package)
        install_s, clicked = provision.install(self, apk)
        ready, detail = provision.wait_ready(self, package, old_pid, timeout)
        lines = ["编译 %s %.1fs → 安装 %.1fs%s → %s" % (module, build_s, install_s,
                                                    "（点了 %s）" % "、".join(clicked) if clicked else "",
                                                    "服务已连接 " + detail if ready else "✗ " + detail)]
        if not ready:
            lines.append("调试桥或服务没有就绪。首次安装或服务没开启时运行 setup。")
        return Output("\n".join(lines), ok=ready)

    def setup(self, module=None, apk=None, project=None, package=None, timeout=90):
        """准备环境：需要时编译并安装宿主（处理安装确认），然后开启 Apex 无障碍服务。调试桥不能用时全程用 adb 模式。"""
        lines = []
        if module or apk:
            if not apk:
                project = provision.find_project(project)
                apk, build_s = provision.build(project, module)
                lines.append("编译 %s %.1fs" % (module, build_s))
            package = package or provision.apk_package(apk)
            took, clicked = provision.install(self, apk)
            lines.append("安装 %.1fs%s" % (took, "，在确认界面点了 " + "、".join(clicked) if clicked else ""))
        package = package or self.bridge.package or os.environ.get("APEX_PACKAGE")
        if not package:
            raise ApexError("BAD_REQUEST", "pass --pkg (host package) or --module/--apk")
        self.bridge.package = package
        label = provision.app_label(apk) if apk else None
        for step in provision.enable_service(self, package, label=label):
            lines.append(step)
        ready, detail = provision.wait_ready(self, package, None, timeout)
        lines.append("✓ Apex 已就绪（%s）" % detail if ready else "✗ 还没就绪：%s" % detail)
        return Output("\n".join(lines), ok=ready)

    # ---------------------------------------------------------------- 快照缓存

    def _cache_file(self):
        base = os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache")
        path = os.path.join(base, "apex")
        os.makedirs(path, exist_ok=True)
        name = "%s-%s.json" % (self.adb.resolve(), self.bridge.resolve_package())
        return os.path.join(path, re.sub(r"[^\w.-]", "_", name))

    def _save_last(self, snapshot):
        with open(self._cache_file(), "w", encoding="utf-8") as f:
            json.dump(snapshot, f, ensure_ascii=False)

    def last_screen(self):
        try:
            with open(self._cache_file(), encoding="utf-8") as f:
                return Screen(json.load(f))
        except (OSError, ValueError):
            raise ApexError("NO_SNAPSHOT", "no snapshot yet; observe the screen first")

    def _wait_idle(self, timeout=60):
        """有流程在运行（例如刚在 runner 里点了运行）时等它结束。返回说明，没在运行时返回 None。"""
        try:
            info = self.bridge.request({"cmd": "info"}, timeout=5)
        except ApexError:
            return None
        running = info["device"].get("running")
        if not running:
            return None
        start = time.time()
        while time.time() - start < timeout:
            time.sleep(0.5)
            info = self.bridge.request({"cmd": "info"}, timeout=5)
            if not info["device"].get("running"):
                return "（流程 %s 正在运行，等它结束用了 %.1fs）" % (running, time.time() - start)
        raise ApexError("BUSY", "flow %s is still running after %ds; wait more or send raw {\"cmd\": \"cancel\"}"
                        % (running, timeout))

    def _take(self, wait_ms=0):
        self._busy_note = self._wait_idle()
        resp = self.bridge.request({"cmd": "snapshot", "waitMs": wait_ms})
        self._save_last(resp["snapshot"])
        return Screen(resp["snapshot"])

    # ---------------------------------------------------------------- 看

    def observe(self, screenshot="none", full=False, grep=None, wait_ms=0, save=None):
        """抓取当前页面：页面身份、路径、计划进度、警告、页面结构，可选带编号框的截图。"""
        why = self._adb_mode_reason()
        if why:
            return self._adb_observe(why, screenshot)
        screen = self._take(wait_ms)
        session = Session.current()
        page, new = session.identify(screen)
        session.log({"kind": "observe", "step": session.meta["steps"], "page": page["id"]})
        session.meta["last_page"] = page["id"]
        session.save()
        lines = session.plan_header()
        if getattr(self, "_busy_note", None):
            lines.append(self._busy_note)
        lines.append("页面 %s%s   路径: %s" % (Session.page_name(page), "（首次到达）" if new else "（第 %d 次）"
                                             % page["visits"], session.path_of_pages()))
        dim = self._screen_warning()
        if dim:
            lines.append("⚠ " + dim)
        if save:
            lines.append(self._save_sample(screen, save))
        if grep:
            body = render_grep(screen, grep)
        elif full:
            body = render_full(screen)
        else:
            body = render_outline(screen)
        out = Output("\n".join(lines) + "\n" + body)
        self._attach_shot(out, screen, screenshot, session, "observe-%03d" % session.meta["steps"])
        return out

    def _save_sample(self, screen, path):
        path = path if path.endswith(".json") else path + ".json"
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(screen.data, f, ensure_ascii=False, indent=1)
        return "已保存样本 %s" % path

    def _attach_shot(self, out, screen, mode, session, name):
        if mode in (None, "none"):
            return
        try:
            png = shot.screenshot(self.adb, self._boxes(screen) if mode == "annotated" else None)
        except ApexError as e:
            out.text += "\n（截图失败: %s）" % e
            return
        path = session.shot_path(name)
        with open(path, "wb") as f:
            f.write(png)
        out.image = png
        out.image_path = path
        if mode == "annotated":
            out.text += "\n截图: %s（框上的数字就是 @引用；文本以节点树为准，不要从截图里抄）" % path
        else:
            out.text += "\n截图: %s" % path

    @staticmethod
    def _boxes(screen):
        boxes = []
        for n in screen.nodes:
            if (n.visible or n.virtual) and is_interactive(n) and ("scrollable" not in n.f or n.virtual):
                boxes.append((n.h, n.b))
        return boxes[:80]

    # ---------------------------------------------------------------- 做

    def act(self, action, intent=None, expect=None, wait_ms=0, screenshot="none"):
        """执行一个动作，返回结果、和上一屏相比的变化、预期是否达成、警告，以及新页面。"""
        why = self._adb_mode_reason()
        if why:
            return self._adb_act(action, why, screenshot)
        action, note = self.resolve_action(action)
        try:
            before = self.last_screen()
        except ApexError:
            before = None
        session = Session.current()
        expect = _normalize_expect(expect)
        busy_note = self._wait_idle()
        resp = self.bridge.request({"cmd": "act", "action": action, "waitMs": wait_ms})
        after = Screen(resp["snapshot"])
        stale_note = None
        if resp.get("beforeSnapshot"):
            # 上次查看之后页面已经变了（例如中间跑过流程），按动作前现抓的页面对比
            before = Screen(resp["beforeSnapshot"])
            stale_note = "（动作前页面和上次看到的不一样，变化按动作前现抓的页面计算）"
        self._save_last(resp["snapshot"])
        step = session.next_step()
        from_page = session.meta.get("last_page")
        page, new = session.identify(after)
        session.meta["last_page"] = page["id"]
        if action.get("type") == "launchApp":
            session.meta["launched"] = sorted(set(session.meta["launched"]) | {action["package"]})
        session.save()

        changes = diff_screens(before, after) if before is not None else []
        checks = self._check_expect(expect, after, page, resp["changed"]) if expect else []
        checks += self._check_expect_selectors(expect, after) if expect else []
        warnings = self._warnings(session, before, after, page, new, resp, action, checks)
        dim = self._screen_warning()
        if dim:
            warnings.append(dim)
        session.log({"kind": "act", "step": step, "intent": intent, "expect": expect, "action": action,
                     "note": note, "status": resp["status"], "message": resp.get("message"),
                     "changed": resp["changed"], "from": from_page, "page": page["id"], "new_page": new,
                     "expect_met": all(ok for _, ok in checks) if checks else None, "warnings": warnings})

        lines = [l for l in session.plan_header() if not l.startswith("目标: ")]
        lines.append("步骤 #%d: %s%s" % (step, json.dumps(action, ensure_ascii=False),
                                       "  意图: " + intent if intent else ""))
        if note:
            lines.append("  " + note)
        if busy_note:
            lines.append(busy_note)
        lines.append("结果: %s%s" % (resp["status"], " — " + resp["message"] if resp.get("message") else ""))
        for nested in resp.get("nested") or []:
            lines.append("  " + nested)
        if checks:
            lines.append("预期: " + "；".join("%s %s" % ("✓" if ok else "✗", desc) for desc, ok in checks))
        lines.append("页面变化: %s%s" % ("是" if resp["changed"] else "否", stale_note or ""))
        for c in changes:
            lines.append("  " + c)
        arrival = "首次到达" if new else "第 %d 次" % page["visits"]
        lines.append("现在在 %s（%s）   路径: %s" % (Session.page_name(page), arrival, session.path_of_pages()))
        for w in warnings:
            lines.append("⚠ " + w)
        lines.append("")
        lines.append(render_outline(after))
        out = Output("\n".join(lines), ok=resp["status"] == "OK")
        self._attach_shot(out, after, screenshot, session, "%03d" % step)
        return out

    @staticmethod
    def _check_expect(expect, screen, page, changed):
        texts = screen.texts()
        checks = []

        def present(t):
            return any(t in x for x in texts)

        for t in _as_list(expect.get("text")):
            checks.append(("页面上有「%s」" % t, present(t)))
        for t in _as_list(expect.get("gone")):
            checks.append(("页面上没有「%s」" % t, not present(t)))
        if expect.get("page"):
            want = expect["page"]
            checks.append(("到达 %s" % want, want in (page["id"], page.get("title"))))
        if expect.get("package"):
            checks.append(("在 %s" % expect["package"], screen.pkg == expect["package"]))
        if "changed" in expect:
            checks.append(("页面%s变化" % ("有" if expect["changed"] else "没有"), bool(changed) == bool(expect["changed"])))
        return checks

    def _check_expect_selectors(self, expect, screen):
        """has / hasNot：用设备上真实的 Selector 在动作后的快照上验证（开关、单选的状态最需要这种核对）。"""
        wanted = [(sel, True) for sel in _as_list(expect.get("has"))] + \
                 [(sel, False) for sel in _as_list(expect.get("hasNot"))]
        if not wanted:
            return []
        resp = self.bridge.request({"cmd": "find", "seq": screen.seq, "selectors": [w[0] for w in wanted]})
        checks = []
        for (sel, should), r in zip(wanted, resp["results"]):
            desc = ("有 " if should else "没有 ") + json.dumps(sel, ensure_ascii=False)
            checks.append((desc, (r["count"] > 0) == should))
        return checks

    @staticmethod
    def _warnings(session, before, after, page, new, resp, action, checks):
        warnings = []
        if resp["status"] != "OK":
            warnings.append("动作没有成功（%s）。看看原因：目标不在当前页面？需要先滚动？还是选错了元素？" % resp["status"])
        elif not resp["changed"] and action.get("type") not in ("sleep", "setText"):
            warnings.append("执行后页面没有任何变化：可能点到了不响应的元素、目标被遮挡，或者页面还在加载（可以 observe 加 wait_ms 再看）")
        if checks and not all(ok for _, ok in checks):
            warnings.append("预期没有达成。先判断现在在哪、离目标是更近还是更远了，再决定是继续、后退还是换一条路")
        if before is not None and before.pkg != after.pkg and action.get("type") != "launchApp":
            if after.pkg in PERMISSION_PACKAGES:
                warnings.append("弹出了系统权限对话框（%s）。决定允许还是拒绝；流程里通常用拦截器处理它" % after.pkg)
            elif after.pkg not in session.meta.get("launched", []):
                warnings.append("离开了 %s，进入了 %s。如果这不是要去的地方（广告、应用商店、别的应用），用 back 回去"
                                % (before.pkg, after.pkg))
        if page.get("dialog") and (new or before is None or not _top_is_dialog(before)):
            warnings.append("出现了弹窗。如果它不是流程的一部分（广告、更新、评分、权限提示…），关掉它，并在流程里为它加拦截器")
        plan_done = bool(session.meta["plan"]) and all(m["done"] for m in session.meta["plan"])
        recent = [e.get("page") for e in session.journal() if e.get("kind") == "act"][-6:] + [page["id"]]
        productive = resp["status"] == "OK" and (all(ok for _, ok in checks) if checks else resp["changed"])
        if not plan_done and not productive and recent.count(page["id"]) >= 3 and len(set(recent)) <= 3:
            warnings.append("最近几步一直在 %s 附近打转。停下来：重新读页面、换个入口（搜索、菜单、往下翻），或者重新规划"
                            % Session.page_name(page))
        stale = session.steps_without_progress()
        if session.meta["plan"] and stale >= 6 and any(not m["done"] for m in session.meta["plan"]):
            warnings.append("已经 %d 步没有完成新的里程碑。回顾一下路径，确认方向对不对" % stale)
        return warnings

    def resolve_action(self, action):
        """动作可以是 JSON 对象、JSON 文本，或者 `click @12` 这样的简写；@引用会换成验证过的最佳选择器。"""
        if isinstance(action, dict):
            return action, None
        if isinstance(action, (list, tuple)):
            parts = list(action)
        else:
            text = str(action).strip()
            if text.startswith("{"):
                return _json(text), None
            parts = _split(text)
        if not parts:
            raise ApexError("BAD_REQUEST", "empty action")
        if len(parts) == 1 and parts[0].lstrip().startswith("{"):
            return _json(parts[0]), None
        verb, rest = parts[0], parts[1:]
        if verb in ("back", "home", "recents", "notifications"):
            return {"type": verb}, None
        if verb == "sleep":
            return {"type": "sleep", "ms": int(rest[0])}, None
        if verb == "launch":
            return {"type": "launchApp", "package": rest[0]}, None
        if verb == "tap":
            return {"type": "tap", "x": int(rest[0]), "y": int(rest[1])}, None
        if verb in ("click", "longClick", "setText", "scroll", "setProgress"):
            if not rest:
                raise ApexError("BAD_REQUEST", "%s needs a target: @ref or selector JSON" % verb)
            target, note, fallback = self._resolve_target(rest[0], verb)
            if fallback is not None:
                return fallback, note
            act = {"type": verb, "target": target}
            if verb == "setText":
                act["text"] = " ".join(rest[1:])
            if verb == "scroll":
                act["direction"] = rest[1] if len(rest) > 1 else "forward"
            if verb == "setProgress":
                value = rest[1] if len(rest) > 1 else "100%"
                if value.endswith("%"):
                    act["ratio"] = float(value[:-1]) / 100
                else:
                    act["value"] = float(value)
            return act, note
        raise ApexError("BAD_REQUEST", "unknown action %r (click/longClick/setText/scroll/setProgress/tap/back/home/"
                                       "recents/notifications/sleep/launch, or Apex action JSON)" % verb)

    def _resolve_target(self, ref, verb):
        if not ref.startswith("@"):
            return _json(ref), None, None
        screen = self.last_screen()
        n = screen.node(ref)
        purpose = "click" if verb in ("click", "longClick") else "exact"
        ranked = suggest(self.bridge, screen, n, purpose)
        best = best_selector(ranked)
        if best is None:
            if verb in ("click", "longClick"):
                x, y = n.center
                note = ("@%d 没有能定位到它的选择器，改用坐标点击 (%d,%d)。写进流程时要换成更稳定的方式（父节点的特征、"
                        "自定义条件，或按比例坐标）" % (n.h, x, y))
                return None, note, {"type": "tap", "x": x, "y": y}
            raise ApexError("BAD_REQUEST", "@%d has no selector that finds it; use selector JSON instead" % n.h)
        note = "@%d → %s" % (n.h, json.dumps(best["selector"], ensure_ascii=False))
        if not best["unique"]:
            note += "（命中 %d 个，依赖它排在第一个）" % best["count"]
        return best["selector"], note, None

    def suggest(self, ref, purpose="click"):
        screen = self.last_screen()
        n = screen.node(ref)
        ranked = suggest(self.bridge, screen, n, purpose)
        lines = ["@%d %s" % (n.h, describe_node(n))]
        if not ranked:
            lines.append("没有可用的候选（节点没有文本、描述和 id）；考虑用父节点的特征、自定义条件或按比例坐标")
            return Output("\n".join(lines), ok=False)
        for c in ranked:
            mark = "✓" if c["ok"] and c["unique"] else ("~" if c["ok"] else "✗")
            extra = "" if c["unique"] else "（命中 %d 个%s）" % (c["count"], "，目标排第一" if c["ok"] else "")
            lines.append("%s %s  %s%s" % (mark, json.dumps(c["selector"], ensure_ascii=False), c["why"], extra))
        lines.append("✓ 唯一命中目标  ~ 命中目标但依赖顺序  ✗ 先命中的是别的节点")
        return Output("\n".join(lines))

    def find(self, selectors):
        screen = self.last_screen()
        selectors = [s if isinstance(s, dict) else _json(s) for s in selectors]
        resp = self.bridge.request({"cmd": "find", "seq": screen.seq, "selectors": selectors})
        lines = []
        for sel, r in zip(selectors, resp["results"]):
            lines.append("%s → %d 个%s" % (json.dumps(sel, ensure_ascii=False), r["count"],
                                          ": " + " ".join("@%d" % h for h in r["handles"]) if r["handles"] else ""))
            if r.get("explain"):
                lines.append("  " + r["explain"])
        return Output("\n".join(lines))

    def find_text(self, text, scroll=False, direction="forward", max_swipes=10, container=None, screenshot="none"):
        """在当前页面找包含 text 的节点；scroll 为真且没找到时，滚动可滚动区域去找（记为一步）。"""
        screen = self._take()
        hits = [n for n in screen.nodes if (n.visible or n.virtual)
                and any(text in (x or "") for x in (n.text, n.desc))]
        if hits or not scroll:
            if not hits:
                return Output("当前页面没有「%s」。可以加 scroll 往下找，或者换个入口（搜索框、菜单、其他标签页）" % text, ok=False)
            return Output(render_grep(screen, text))
        action = {"type": "scrollTo", "target": {"textContains": text}, "direction": direction,
                  "maxSwipes": max_swipes}
        if container:
            action["container"] = container if isinstance(container, dict) else _json(container)
        return self.act(action, intent="寻找「%s」" % text, expect={"text": text}, screenshot=screenshot)

    def launch(self, package, reset=False, screenshot="none"):
        component = self._launcher(package)
        # NEW_TASK|CLEAR_TASK 回到首页而不杀进程；绝不用强制停止：对宿主包会让无障碍服务被系统关掉
        flags = "0x10008000" if reset else "0x10200000"
        self.adb.shell("am start -n %s -f %s" % (component, flags))
        session = Session.current()
        session.meta["launched"] = sorted(set(session.meta["launched"]) | {package})
        session.save()
        session.log({"kind": "launch", "step": session.meta["steps"], "package": package, "reset": reset})
        time.sleep(0.3)
        out = self.observe(screenshot=screenshot, wait_ms=500)
        out.text = "已%s %s\n" % ("重置并打开" if reset else "打开", component) + out.text
        return out

    def _launcher(self, package):
        out = self.adb.shell("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER -a "
                             "android.intent.action.MAIN %s" % package)
        lines = [l.strip() for l in out.splitlines() if "/" in l]
        if not lines:
            raise ApexError("BAD_REQUEST", "no launcher activity for %s (is it installed?)" % package)
        return lines[-1]

    # ---------------------------------------------------------------- 计划和记录

    def plan(self, goal=None, milestones=None, done=None, note=None, new=False):
        session = Session.start(goal) if new else Session.current()
        if goal or milestones is not None:
            session.set_plan(goal, milestones)
        if done:
            session.mark_done([int(d) for d in done] if isinstance(done, (list, tuple)) else int(done), note)
        return Output(session.render_plan() + "\n会话目录: %s" % session.path)

    def history(self):
        session = Session.current(create=False)
        names = {p["id"]: Session.page_name(p) for p in session.meta["pages"]}
        lines = [session.render_plan(), "", "路径: " + session.path_of_pages(limit=50), "", "步骤:"]
        for e in session.journal():
            if e.get("kind") == "launch":
                lines.append("  （打开 %s%s）" % (e["package"], "，清空任务栈" if e.get("reset") else ""))
                continue
            if e.get("kind") == "run":
                lines.append("  （运行流程 %s：%s，路径 %s，轨迹 %s）" % (e.get("flow"), e.get("status"),
                                                                 " → ".join(e.get("path") or []) or "-", e.get("trace")))
                continue
            if e.get("kind") != "act":
                continue
            if e["status"] != "OK":
                mark = "✗ " + e["status"]
            elif e.get("expect_met") is False:
                mark = "预期未达成"
            else:
                mark = "✓"
            lines.append("  #%d %s %s → %s  [%s] %s" % (
                e["step"], names.get(e.get("from"), e.get("from") or "?"), json.dumps(e["action"], ensure_ascii=False),
                names.get(e["page"], e["page"]), mark, ("意图: " + e["intent"]) if e.get("intent") else ""))
        lines.append("")
        lines.append("页面（样本在 %s）:" % os.path.join(session.path, "pages"))
        for p in session.meta["pages"]:
            tabs = p.get("sig", {}).get("tabs")
            lines.append("  %s  %s/%s%s  到达 %d 次" % (Session.page_name(p), p.get("pkg"), p.get("activity"),
                                                    "  标签页 " + "/".join(tabs) if tabs else "", p["visits"]))
            for sample in (p.get("samples") or [])[1:]:
                lines.append("    样本 %s：%s" % (sample["name"], sample.get("about") or "内容有变化"))
        return Output("\n".join(lines))

    def features(self, page_id):
        """找出某个页面独有的特征：这个页面的所有样本里都有、到过的其他页面的样本里都没有。在设备上验证后给出推荐的 matcher。"""
        session = Session.current(create=False)
        page = session.page(page_id)
        own_names = session.sample_names(page)
        own = [Screen(session.page_snapshot(n)) for n in own_names]
        other_names = [n for p in session.meta["pages"] if p["id"] != page["id"] for n in session.sample_names(p)]
        others = [Screen(session.page_snapshot(n)) for n in other_names]
        cands = [c for c in _feature_candidates(own[0], page.get("title"))
                 if all(_matches_local(sc, c) for sc in own) and not any(_matches_local(o, c) for o in others)]
        in_list = [bool(c.pop("_list", False)) for c in cands]
        total = len(cands)
        cands = cands[:20]
        if not cands:
            return Output("%s 没有找到在它所有样本里都有、而其他页面都没有的特征（样本 %s，其他页面样本 %s）。"
                          "考虑组合多个特征，或用 not 排除相似页面"
                          % (Session.page_name(page), ", ".join(own_names), ", ".join(other_names) or "无"), ok=False)
        # 用设备上真实的匹配逻辑确认：每个候选做成一个状态，拿所有样本去 check
        states = [{"id": "c%d" % i, "match": {"has": c}, "end": "success"} for i, c in enumerate(cands)]
        states.insert(0, {"id": "probe", "match": {"always": True}, "next": [st["id"] for st in states]})
        flow = {"id": "features", "start": "probe", "states": states, "useGlobalInterceptors": False}
        samples = {n: session.page_snapshot(n) for n in own_names + other_names}
        resp = self.bridge.request({"cmd": "check", "flow": flow, "snapshots": samples})
        rows = {st["id"]: st for st in resp["states"]}
        lines = ["%s 的特征（✓ 在它的样本 %s 里都有，其他页面的样本里都没有）:" % (Session.page_name(page),
                                                                     ", ".join(own_names))]
        good = []
        for i, c in enumerate(cands):
            matches = set(rows["c%d" % i]["matches"])
            ok = matches == set(own_names)
            if ok and not in_list[i]:
                good.append(c)
            extra = ""
            if not ok:
                missing = [n for n in own_names if n not in matches]
                wrong = sorted(matches - set(own_names))
                extra = "  " + "；".join(x for x in ("缺 " + ", ".join(missing) if missing else "",
                                                       "也匹配 " + ", ".join(wrong) if wrong else "") if x)
            if in_list[i]:
                extra += "  （列表里的条目，是数据不是页面特征）"
            lines.append("  %s %s%s" % ("✓" if ok else "✗", json.dumps(c, ensure_ascii=False), extra))
        if total > len(cands):
            lines.append("  … 还有 %d 个候选没列出（排在后面的是列表里的文字）" % (total - len(cands)))
        if good:
            matcher = {"package": own[0].pkg, "has": good[:2] if len(good) > 1 else good[0]}
            lines.append("推荐 matcher: %s" % json.dumps(matcher, ensure_ascii=False))
            lines.append("这只是起点，自己判断：标题、页面专属的 id（例如 settings_homepage_container、brightness_slider）、"
                         "固定的标签文字最稳；列表里的条目滚动后会消失，提示消息、状态文字、广告不要用。"
                         "状态要操作的目标最好也写进 match，保证进入状态时它在。只对照了本次到过的 %d 个页面，"
                         "没到过的相似页面可能也有这些特征。" % len(session.meta["pages"]))
        return Output("\n".join(lines), ok=bool(good))

    # ---------------------------------------------------------------- 流程

    def validate(self, flow):
        resp = self.bridge.request(dict({"cmd": "validate"}, **flow_arg(flow)))
        if resp["valid"]:
            f = resp["flow"]
            return Output("✓ %s：start=%s，%d 个状态，拦截器 %s，全局拦截器 %s" % (
                f["id"], f["start"], len(f["states"]), f["interceptors"], f["globalInterceptors"]))
        lines = ["✗ 校验不通过"]
        for p in resp["problems"]:
            lines.append("  %s: %s" % (p["path"] or "(root)", p["message"]))
        return Output("\n".join(lines), ok=False)

    def check(self, flow, samples=None, labels=None, live=False, wait_ms=0):
        """用页面样本检查流程。samples 为空时用本次会话到过的所有页面；labels 把页面编号映射成状态 id，例如 {"P3": "display"}。"""
        snapshots = {}
        if samples:
            for d in samples:
                files = [d] if d.endswith(".json") else [os.path.join(d, f) for f in sorted(os.listdir(d))
                                                         if f.endswith(".json")]
                for path in files:
                    with open(path, encoding="utf-8") as f:
                        data = json.load(f)
                    if "windows" in data:
                        snapshots[os.path.basename(path)[:-5]] = data
        elif not live:
            try:
                session = Session.current(create=False)
                for pid, path in session.page_files().items():
                    with open(path, encoding="utf-8") as f:
                        snapshots[pid] = json.load(f)
            except ApexError:
                pass
        # 样本对应到状态：改名成 "状态id#样本名"（check 认 "状态id#xxx" 为该状态的样本，又能看出是哪个样本）。
        # 写了精确样本名（P3.2）的优先；只写页面编号（P3）时，它的附加样本 P3.2 等也算这个状态，除非另有指定
        labels = labels or {}
        assigned = {}
        for key, state in labels.items():
            if key in snapshots:
                assigned[key] = state
        for key, state in labels.items():
            for name in snapshots:
                if name.startswith(key + ".") and name not in assigned:
                    assigned[name] = state
        for name, state in assigned.items():
            snapshots["%s#%s" % (state, name)] = snapshots.pop(name)
        live = live or not snapshots
        resp = self.bridge.request(dict({"cmd": "check", "snapshots": snapshots, "live": live, "waitMs": wait_ms},
                                        **flow_arg(flow)))
        return Output(render_check(resp), ok=not [w for w in resp["warnings"] if w["kind"] in SEVERE_CHECKS])

    def run(self, flow, timeout=600, quiet=False, save_trace=None, stream=None):
        """运行流程。stream 是逐行回调（CLI 用来实时打印）；返回值里带结果和轨迹的结尾部分。"""
        arg = flow_arg(flow)
        name = arg.get("flowRef") or arg["flow"].get("id")
        result = None
        trace = []
        try:
            for line in self.bridge.stream(dict({"cmd": "run"}, **arg), timeout=timeout):
                if line.get("ev") == "trace":
                    text = "+%.3fs %-11s %s" % (line["t"] / 1000.0, line["type"], line["msg"])
                    trace.append(text)
                    if stream and not quiet:
                        stream(text)
                elif line.get("ev") == "result":
                    result = line["result"]
                elif not line.get("ok", True):
                    check_ok(line)
        except KeyboardInterrupt:
            self.bridge.request({"cmd": "cancel"})
            return Output("已取消", ok=False)
        if result is None:
            raise ApexError("INTERNAL", "run ended without a result")
        path = save_trace
        if path is None:
            try:
                session = Session.current()
                path = os.path.join(session.path, "run-%s.log" % time.strftime("%H%M%S"))
            except OSError:
                path = None
        if path:
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join(trace) + "\n")
        try:
            session = Session.current()
            session.log({"kind": "run", "step": session.meta["steps"], "flow": name,
                         "status": result["status"], "path": result["path"], "trace": path})
            # 运行改变了页面，记下现在在哪，后面的步骤才有正确的起点
            page, _ = session.identify(self._take())
            session.meta["last_page"] = page["id"]
            session.save()
            session.log({"kind": "after-run", "step": session.meta["steps"], "page": page["id"]})
        except (OSError, ApexError):
            pass
        summary = "结果: %s  路径: %s  拦截器: %s  耗时 %.1fs%s" % (
            result["status"], " → ".join(result["path"]) or "-", result.get("interceptors") or {},
            result["durationMs"] / 1000.0, "\n原因: " + result["message"] if result.get("message") else "")
        if stream:
            text = summary
        else:
            tail = trace if len(trace) <= 60 else ["… 前面 %d 行省略" % (len(trace) - 60)] + trace[-60:]
            text = "\n".join(tail) + "\n" + summary
        if path:
            text += "\n完整轨迹: %s" % path
        return Output(text, ok=result["status"] == "SUCCESS")

    def export(self, flow, out=None):
        """把流程（通常是 Java DSL 写的 flowRef）写成 JSON。设备上会确认解析回来和原流程相等；有内联代码时说明在哪。"""
        resp = self.bridge.request(dict({"cmd": "export"}, **flow_arg(flow)))
        if out:
            os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)
            with open(out, "w", encoding="utf-8") as f:
                f.write(resp["json"] + "\n")
            return Output("✓ %s 已写成 JSON：%s（已在设备上确认解析回来与原流程相等）" % (resp["flow"]["id"], out))
        return Output(resp["json"])

    def push(self, flow, name=None):
        if isinstance(flow, str) and is_flow_ref(flow):
            data = json.loads(self.bridge.request({"cmd": "export", "flowRef": flow})["json"])
        else:
            data = _flow(flow)
        name = name or data.get("id")
        if not name or not re.fullmatch(r"[\w.-]+", name):
            raise ApexError("BAD_REQUEST", "flow name may only contain letters, digits, '_', '-', '.'")
        pkg = self.bridge.resolve_package()
        body = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        self.adb.run("shell", "run-as %s sh -c 'mkdir -p files/flows && cat > files/flows/%s.json'" % (pkg, name),
                     input=body)
        return Output("已推送到 %s 的 files/flows/%s.json，宿主 App 的流程列表里可以看到它" % (pkg, name))

    def raw(self, request, timeout=120):
        req = request if isinstance(request, dict) else _json(request)
        return Output("\n".join(json.dumps(l, ensure_ascii=False) for l in self.bridge.stream(req, timeout=timeout)))


# ---------------------------------------------------------------- 辅助

def render_check(resp):
    names = [s["name"] for s in resp["samples"]]
    width = max([len(n) for n in names] + [4])
    rows = [("状态 " + s["id"], s) for s in resp["states"]] + \
           [("拦截器 " + i["id"] + (" (全局)" if i.get("global") else ""), i) for i in resp["interceptors"]]
    lw = max([len(r[0]) for r in rows] + [6])
    out = [" " * lw + "  " + "  ".join(n.ljust(width) for n in names)]
    for label, row in rows:
        cells = []
        for name in names:
            if name in (row.get("errors") or {}):
                cells.append("!".ljust(width))
            else:
                cells.append(("✓" if name in row["matches"] else "·").ljust(width))
        out.append(label.ljust(lw) + "  " + "  ".join(cells))
    if len(names) == 1:
        out.append("")
        for label, row in rows:
            why = (row.get("mismatch") or {}).get(names[0]) or (row.get("errors") or {}).get(names[0])
            if why:
                out.append("%s 不匹配: %s" % (label, why))
    out.append("")
    severe = [w for w in resp["warnings"] if w["kind"] in SEVERE_CHECKS]
    hints = [w for w in resp["warnings"] if w["kind"] not in SEVERE_CHECKS]
    if severe:
        out.append("问题（要修）:")
        for w in severe:
            out.append("  [%s] %s" % (w["kind"], w["message"]))
    if hints:
        out.append("提示（确认是有意的就可以不管，例如更具体的状态排在前面的有序兜底）:")
        for w in hints:
            out.append("  [%s] %s" % (w["kind"], w["message"]))
    if not resp["warnings"]:
        out.append("没有发现问题")
    return "\n".join(out)


# 样本被错误的状态匹配、拦截器误伤正常页面：一定要修。歧义、太宽、没人匹配：可能是有意的
SEVERE_CHECKS = ("expected-mismatch", "wrong-winner", "interceptor-on-state-page")


# 只由这些词组成的 id 是通用的（icon、title、content_parent……），换个页面也有，不适合当页面特征
GENERIC_ID_WORDS = {"content", "container", "frame", "parent", "root", "layout", "main", "panel", "bg", "mask",
                    "wrapper", "recycler", "scroll", "coordinator", "toolbar", "action", "bar", "decor", "sub", "list",
                    "item", "view", "icon", "title", "summary", "text", "txt", "image", "img", "iv", "tv", "ll", "rl",
                    "fl", "arrow", "divider", "line", "button", "btn", "widget", "header", "footer", "appbar", "top",
                    "bottom", "left", "right", "center", "start", "end", "card", "cell", "group", "holder", "area",
                    "entry", "pager", "tab", "tabs", "menu", "nav", "back", "close", "more", "default", "android",
                    "id", "label", "name", "info", "detail", "box", "check", "radio", "switch", "space", "padding",
                    "1", "2", "3"}
# 这些 id 下的文字通常是会变的状态、摘要、提示
VOLATILE_ID = re.compile(r"summary|status|state|message|msg|tip|badge|count|time|date|hint|notice|banner|"
                         r"switch|value", re.IGNORECASE)
STATE_WORDS = {"开启", "关闭", "已开启", "已关闭", "打开", "未开启", "未连接", "已连接", "允许", "不允许", "拒绝",
               "无法访问互联网", "已选择", "未选择", "已勾选", "未勾选", "开", "关", "on", "off", "ON", "OFF"}


def _generic_id(short_id):
    words = [w for w in re.split(r"[_\W]+|(?<=[a-z])(?=[A-Z])", short_id) if w]
    return all(w.lower() in GENERIC_ID_WORDS for w in words)


def _in_list(n):
    """n 在列表的某一项里：最近的可滚动容器下，n 所在的那一项有同类（同 id 或同类名）的兄弟。
    ViewPager 的一页、页面上的悬浮按钮不算。"""
    child = n
    for a in n.ancestors():
        if "scrollable" in a.f:
            same = [c for c in a.children if c is not child and c.cls == child.cls
                    and (c.short_id == child.short_id)]
            if same:
                return True
        child = a
    return False


def _feature_candidates(screen, title):
    """页面特征候选，越贴近页面含义越靠前：标题 > 有语义的 id > 列表外的固定文字 > 列表里的文字。
    通用 id（icon、title、content_parent）、会变的文字（含数字、状态词、摘要/提示/消息里的文字）不列。"""
    ids, texts, list_texts, tabs = [], [], [], []
    for n in screen.nodes:
        if not n.visible:
            continue
        label = (n.text or n.desc or "").strip()
        h = screen.height or 2400
        center = (n.b[1] + n.b[3]) / 2.0
        if "selected" in n.f and label and len(label) <= 12 and not (h * 0.25 < center < h * 0.75):
            # 选中的标签页：同一个 Activity 里区分标签页最稳的特征
            tabs.append({"text" if n.text else "desc": label, "selected": True})
        volatile = (n.short_id and VOLATILE_ID.search(n.short_id)) or any(
            a.short_id and re.search(r"message|banner|notice|tip", a.short_id) for a in n.ancestors())
        for key, value in (("text", n.text), ("desc", n.desc)):
            v = (value or "").strip()
            if v and len(v) <= 20 and not re.search(r"\d", v) and v not in STATE_WORDS and not volatile:
                (list_texts if _in_list(n) else texts).append({key: value})
        if n.short_id and not VOLATILE_ID.search(n.short_id) and not _generic_id(n.short_id):
            (list_texts if _in_list(n) else ids).append({"id": n.short_id})
    cands = []
    usable_title = title and not re.search(r"\d", title)
    for c in ([{"text": title}] if usable_title else []) + tabs + ids + texts + list_texts:
        if c not in cands:
            cands.append(c)
    for c in list_texts:
        if c in cands:
            cands[cands.index(c)] = dict(c, _list=True)
    return cands


def _matches_local(screen, cand):
    """和设备上 Selector 一致的简单匹配（只支持 id/text/desc），用来在验证前粗筛候选。"""
    for n in screen.nodes:
        if not n.visible:
            continue
        if "id" in cand and not (n.id and (n.id == cand["id"] or n.id.endswith(":id/" + cand["id"]))):
            continue
        if "text" in cand and n.text != cand["text"]:
            continue
        if "desc" in cand and n.desc != cand["desc"]:
            continue
        return True
    return False


def _top_is_dialog(screen):
    from .screen import is_fullscreen
    return bool(screen.windows) and not is_fullscreen(screen, screen.windows[0][1])


EXPECT_KEYS = ("text", "gone", "page", "package", "changed", "has", "hasNot")
SELECTOR_KEYS = ("text", "textContains", "textMatches", "desc", "descContains", "descMatches", "id", "className",
                 "clickable", "enabled", "checked", "selected", "scrollable", "stateDesc", "stateDescContains",
                 "progressAtLeast", "progressAtMost", "hasDescendant", "inside", "visible")


def _normalize_expect(expect):
    """expect 的键：text、gone、page、package、changed、has、hasNot。直接传一个选择器（例如
    {"id": "rb_dark_button", "checked": true}）等于 {"has": 选择器}。不认识的键报错，不静默忽略。"""
    if not expect:
        return None
    if isinstance(expect, str):
        return {"text": expect}
    if not isinstance(expect, dict):
        raise ApexError("BAD_REQUEST", "expect must be an object or a text")
    keys = set(expect)
    if keys - set(EXPECT_KEYS) and keys <= set(SELECTOR_KEYS):
        return {"has": expect}
    unknown = keys - set(EXPECT_KEYS)
    if unknown:
        raise ApexError("BAD_REQUEST", "unknown expect key(s) %s; supported: %s, or a selector object"
                        % (sorted(unknown), ", ".join(EXPECT_KEYS)))
    return expect


def _as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def _json(text):
    if isinstance(text, (dict, list)):
        return text
    if os.path.exists(text):
        with open(text, encoding="utf-8") as f:
            return json.load(f)
    try:
        return json.loads(text)
    except ValueError as e:
        raise ApexError("BAD_REQUEST", "not JSON and not a file: %r (%s)" % (text[:60], e))


FLOW_REF = re.compile(r"^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)+(#\w+)?$")


def is_flow_ref(value):
    """形如 com.foo.Flows#addAlarm 或 com.foo.AddAlarmFlow、且不是现有文件的字符串，是宿主代码里 Java DSL 流程的引用。"""
    return isinstance(value, str) and bool(FLOW_REF.match(value)) and not os.path.exists(value) \
        and not value.endswith(".json")


def flow_arg(flow):
    """请求里的流程参数：Java DSL 引用用 flowRef，其他按 JSON（对象、文本或文件）处理。"""
    if isinstance(flow, str) and is_flow_ref(flow):
        return {"flowRef": flow}
    return {"flow": _flow(flow)}


def _flow(flow):
    data = _json(flow) if not isinstance(flow, dict) else flow
    if not isinstance(data, dict):
        raise ApexError("BAD_REQUEST", "flow must be a JSON object")
    return data


def _split(text):
    """按空白切分，花括号里的 JSON 保持完整：`click {"text": "a b"}` → ["click", '{"text": "a b"}']。"""
    parts, buf, depth, quote = [], "", 0, False
    for ch in text:
        if ch == '"' and (not buf.endswith("\\")):
            quote = not quote
        if not quote:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
        if ch.isspace() and depth == 0 and not quote:
            if buf:
                parts.append(buf)
                buf = ""
            continue
        buf += ch
    if buf:
        parts.append(buf)
    return parts
