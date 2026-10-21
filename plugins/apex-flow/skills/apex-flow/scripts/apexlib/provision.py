"""准备环境：编译安装宿主（deploy）、首次安装和开启无障碍服务（setup）。

调试桥还不能用时，安装确认和开启服务都用 adb 模式在界面上操作；服务一连上就交还给 Apex。
"""

import glob
import os
import re
import shutil
import subprocess
import tempfile
import time

from . import adbmode
from .errors import ApexError
from .screen import Screen, page_title

SERVICE_CLASS = "com.github.susanaluis877.apex.service.ApexAccessibilityService"
# 安装确认、开启服务确认弹窗里要点的按钮，按优先级排列
INSTALL_WORDS = ["继续安装", "仍然安装", "安装", "允许", "确定", "完成", "Install", "Continue", "Allow", "OK"]
CONFIRM_WORDS = ["允许", "确定", "同意", "开启", "打开", "仍然开启", "知道了", "Allow", "OK", "Turn on"]
CANCEL_WORDS = ("取消", "拒绝", "不允许", "Cancel", "Deny")


# ---------------------------------------------------------------- 编译

def find_project(start=None):
    path = os.path.abspath(start or os.getcwd())
    while True:
        if os.path.exists(os.path.join(path, "gradlew")):
            return path
        parent = os.path.dirname(path)
        if parent == path:
            raise ApexError("BUILD", "no gradlew found from %s upwards; pass --project" % (start or os.getcwd()))
        path = parent


def app_modules(project):
    """包含 Android 应用插件的模块。"""
    modules = []
    for build in glob.glob(os.path.join(project, "*", "build.gradle*")):
        with open(build, encoding="utf-8", errors="replace") as f:
            text = f.read()
        if re.search(r"com\.android\.application|android\.application|plugins\.android\.application", text):
            modules.append(os.path.basename(os.path.dirname(build)))
    return sorted(modules)


def build(project, module, variant="debug"):
    """编译宿主 APK。失败时抛 ApexError，消息里带编译错误。返回 (apk 路径, 耗时秒)。"""
    task = ":%s:assemble%s" % (module, variant[:1].upper() + variant[1:])
    gradlew = os.path.join(project, "gradlew.bat" if os.name == "nt" else "gradlew")
    start = time.time()
    p = subprocess.run([gradlew, task, "--console=plain", "-q"], cwd=project, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=900)
    took = time.time() - start
    if p.returncode != 0:
        raise ApexError("BUILD", "%s failed in %.1fs:\n%s" % (task, took, _build_errors(p.stdout.decode("utf-8",
                                                                                                           "replace"))))
    apks = glob.glob(os.path.join(project, module, "build", "outputs", "apk", variant, "*.apk"))
    if not apks:
        raise ApexError("BUILD", "%s succeeded but no APK under %s/build/outputs/apk/%s" % (task, module, variant))
    return max(apks, key=os.path.getmtime), took


def _build_errors(output):
    """只保留编译错误和它们后面几行，避免把整段 Gradle 输出塞给模型。"""
    lines = output.splitlines()
    keep = []
    for i, line in enumerate(lines):
        if re.search(r"error:|错误:|^e: |What went wrong|FAILURE", line):
            keep.extend(lines[i:i + 4])
    text = "\n".join(dict.fromkeys(keep)) or "\n".join(lines[-40:])
    return text[:6000]


def build_tool(name):
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.expanduser("~/Library/Android/sdk"), os.path.expanduser("~/Android/Sdk")):
        if root:
            found = sorted(glob.glob(os.path.join(root, "build-tools", "*", name)))
            if found:
                return found[-1]
    return None


def apk_package(apk):
    aapt2 = build_tool("aapt2")
    if not aapt2:
        raise ApexError("BUILD", "aapt2 not found in Android build-tools; pass --pkg")
    out = subprocess.run([aapt2, "dump", "packagename", apk], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         timeout=60).stdout.decode().strip()
    if not out:
        raise ApexError("BUILD", "cannot read package name of %s" % apk)
    return out.splitlines()[-1].strip()


# ---------------------------------------------------------------- 安装

def install(kit, apk, timeout=180):
    """adb install -r；安装过程中出现确认界面时用 adb 模式点「继续安装/安装」。需要输入密码时如实报告。"""
    adb = kit.adb
    p = subprocess.Popen([adb.path, "-s", adb.resolve(), "install", "-r", "-t", apk], stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT)
    start = time.time()
    clicked = []
    password = False
    while p.poll() is None:
        elapsed = time.time() - start
        if elapsed > timeout:
            p.kill()
            raise ApexError("INSTALL", "install did not finish in %ds%s" % (timeout, "; the phone asks for a password, "
                            "ask the user to confirm the install on the phone" if password else ""))
        if elapsed > 3 and not kit.apex_ready()[0]:
            try:
                screen = Screen(adbmode.dump(adb))
            except ApexError:
                screen = None
            if screen is not None:
                if any("password" in n.f for n in screen.nodes):
                    password = True
                installer = re.search(r"installer|packageinstaller|appdetail|safecenter|securitypermission",
                                      screen.pkg or "") or any("安装" in t or "nstall" in t for t in screen.texts())
                button = _button(screen, INSTALL_WORDS) if installer else None
                if button is not None and not password:
                    adbmode.tap(adb, *button.center)
                    clicked.append(button.text or button.desc)
        time.sleep(1.5)
    out = p.stdout.read().decode("utf-8", "replace")
    if "Success" not in out:
        raise ApexError("INSTALL", out.strip()[-1500:])
    return time.time() - start, clicked


def _button(screen, words):
    """按 words 的优先级找可点击、可用的按钮（文字完全相同）。"""
    for word in words:
        for n in screen.nodes:
            label = (n.text or n.desc or "").strip()
            if label != word or "disabled" in n.f:
                continue
            owner = n if "clickable" in n.f else next((a for a in n.ancestors() if "clickable" in a.f), None)
            if owner is not None:
                return owner
    return None


def pid_of(adb, package):
    try:
        return adb.shell("pidof %s" % package, timeout=10).strip() or None
    except ApexError:
        return None


def wait_ready(kit, package, old_pid=None, timeout=60):
    """等宿主进程（重新）启动、调试桥可用、无障碍服务连上。返回 (是否就绪, 说明)。"""
    kit.bridge.package = package
    start = time.time()
    last = "bridge not reachable"
    while time.time() - start < timeout:
        pid = pid_of(kit.adb, package)
        if pid and pid != old_pid:
            ready, why = kit.apex_ready(timeout=3)
            if ready:
                return True, "%.1fs" % (time.time() - start)
            last = why
            if why.startswith("无障碍服务没有开启"):
                return False, why
        elif not pid:
            # 进程没起来：服务没开启时系统不会拉起它，先主动启动一次让调试桥起来
            launch_host(kit.adb, package)
        time.sleep(0.5)
    return False, last


def launch_host(adb, package):
    try:
        out = adb.shell("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER -a "
                        "android.intent.action.MAIN %s" % package)
        component = [l.strip() for l in out.splitlines() if "/" in l][-1]
        adb.shell("am start -n %s -f 0x10200000" % component)
    except (ApexError, IndexError):
        pass


# ---------------------------------------------------------------- 开启无障碍服务

def enabled_services(adb):
    out = adb.shell("settings get secure enabled_accessibility_services").strip()
    return [] if out in ("", "null") else [s for s in out.split(":") if s]


def service_enabled(adb, package):
    component = "%s/%s" % (package, SERVICE_CLASS)
    short = "%s/.service.ApexAccessibilityService" % package
    return any(s in (component, short) or s.endswith("/" + SERVICE_CLASS) and s.startswith(package + "/")
               for s in enabled_services(adb))


def enable_service(kit, package, label=None, timeout=60):
    """开启宿主的 Apex 无障碍服务。先试 settings put（追加，不覆盖已开启的其他服务），被系统拒绝时在设置界面里操作。
    返回做了什么的说明列表。"""
    adb = kit.adb
    component = "%s/%s" % (package, SERVICE_CLASS)
    log = []
    if service_enabled(adb, package):
        return ["服务已经是开启的"]
    services = enabled_services(adb)
    try:
        out = adb.shell("settings put secure enabled_accessibility_services %s 2>&1; settings put secure "
                        "accessibility_enabled 1 2>&1; true" % ":".join(services + [component]))
    except ApexError as e:
        out = e.message
    if service_enabled(adb, package):
        return ["用 settings put 开启（追加在已开启的服务后面）"]
    reason = next((l.strip() for l in out.splitlines() if "Exception" in l or "denial" in l.lower()), "没有生效")
    log.append("settings put 被系统拒绝（%s），改为在设置界面里开启" % reason[:100])

    # 直接打开这个服务的详情页（Android 11+ 多数系统支持），不行就打开无障碍设置首页按名字找
    try:
        adb.shell("am start -a android.settings.ACCESSIBILITY_DETAILS_SETTINGS --ecn "
                  "android.intent.extra.COMPONENT_NAME %s" % component)
    except ApexError as e:
        # 有的系统要求 OPEN_ACCESSIBILITY_DETAILS_SETTINGS 权限，shell 没有
        log.append("不能直接打开服务详情页（%s），改从无障碍设置里找" % ("没有权限" if "Permission" in e.message
                                                              else e.code))
        adb.shell("am start -a android.settings.ACCESSIBILITY_SETTINGS -f 0x10008000")
    labels = [l for l in (label, service_label(adb, package), "Apex") if l]
    labels = list(dict.fromkeys(labels))
    log.append("按名字找服务：%s" % " / ".join(labels))
    # 和 Apex 的状态机一样：每一轮看当前屏幕上有什么就处理什么，直到服务开启或超时
    start = time.time()
    scrolls = 0
    while time.time() - start < timeout:
        time.sleep(1)
        if service_enabled(adb, package):
            log.append("服务已开启")
            adbmode.key(adb, "home")
            return log
        screen = Screen(adbmode.dump(adb))
        # 只处理提到这个服务名字的确认弹窗，别的弹窗一概不碰
        ours = _dialog_open(screen) and _mentions(screen, labels)
        box = next((n for n in screen.nodes if "checkable" in n.f and "checked" not in n.f
                    and _in_dialog(screen, n)), None) if ours else None
        if box is not None:
            # 确认弹窗里「我已了解风险」之类的勾选框
            adbmode.tap(adb, *box.center)
            log.append("勾选了弹窗里的 %s" % (box.text or box.desc or "确认框"))
            continue
        if ours:
            button = _button(screen, CONFIRM_WORDS)
            if button is not None:
                adbmode.tap(adb, *button.center)
                log.append("点了确认按钮 %s" % (button.text or button.desc or ""))
                continue
            waiting = [n for n in screen.nodes if "disabled" in n.f]
            log.append("等确认按钮可用%s" % ("（%s）" % waiting[0].text if waiting and waiting[0].text else ""))
            continue
        switch = _service_switch(screen, labels)
        if switch is not None:
            adbmode.tap(adb, *switch.center)
            log.append("点了服务开关")
            continue
        entry = _find_label(screen, labels)
        if entry is not None:
            owner = entry if "clickable" in entry.f else next((a for a in entry.ancestors() if "clickable" in a.f),
                                                                entry)
            adbmode.tap(adb, *owner.center)
            log.append("在列表里点了 %s" % (entry.text or entry.desc))
            continue
        group = next((n for n in screen.nodes if re.search(r"已下载|已安装的服务|Downloaded|Installed", n.text or "")),
                     None)
        if group is not None:
            adbmode.tap(adb, *group.center)
            log.append("进入 %s" % group.text)
            continue
        if scrolls < 8:
            w, h = screen.width or 1080, screen.height or 2400
            adbmode.swipe(adb, w // 2, int(h * 0.75), w // 2, int(h * 0.3))
            scrolls += 1
            continue
        break
    raise ApexError("SETUP", "service still not enabled after %ds; steps: %s. Ask the user to enable it in "
                             "Settings → Accessibility" % (timeout, "；".join(log)))


def _find_label(screen, labels):
    for label in labels:
        for n in screen.nodes:
            if label.lower() in (n.text or n.desc or "").lower():
                return n
    return None


def _mentions(screen, labels):
    """当前页面是这个服务的详情页（页面上有它的名字）。"""
    return _find_label(screen, labels) is not None


def _dialog_open(screen):
    """顶层窗口不是全屏：系统确认弹窗。"""
    if not screen.windows:
        return False
    root = screen.windows[0][1]
    w = root.b[2] - root.b[0]
    h = root.b[3] - root.b[1]
    return bool(screen.width) and (w < screen.width * 0.95 or h < screen.height * 0.9)


def _service_switch(screen, labels):
    """这个服务的开关（未打开）：开关所在的那一行写着服务名，或者页面标题就是服务名（服务详情页）。
    无障碍设置首页上也有服务名（在列表里），但那里的其他开关绝不能碰。"""
    title = page_title(screen) or ""
    on_detail_page = any(label.lower() in title.lower() for label in labels)
    for n in screen.nodes:
        if "checkable" not in n.f or "checked" in n.f or not re.search(r"Switch|Toggle|Compound", n.cls):
            continue
        row = next((a for a in n.ancestors() if "clickable" in a.f), n.parent)
        row_text = " ".join((x.text or x.desc or "") for x in row.walk()) if row is not None else ""
        if on_detail_page or any(label.lower() in row_text.lower() for label in labels):
            return n
    return None


def _in_dialog(screen, n):
    root = screen.windows[0][1] if screen.windows else None
    if root is None:
        return False
    w = root.b[2] - root.b[0]
    h = root.b[3] - root.b[1]
    small_window = screen.width and (w < screen.width * 0.95 or h < screen.height * 0.9)
    return bool(small_window) or n.b[1] > (screen.height or 2400) * 0.3


def service_label(adb, package):
    """无障碍设置里显示的服务名：宿主可以覆盖 SDK 的字符串资源 apex_accessibility_label，从已安装的 APK 里读出来。"""
    aapt2 = build_tool("aapt2")
    if not aapt2:
        return None
    try:
        path = adb.shell("pm path %s" % package).strip().splitlines()[0].replace("package:", "")
    except (ApexError, IndexError):
        return None
    local = os.path.join(tempfile.mkdtemp(prefix="apex-"), "base.apk")
    try:
        adb.run("pull", path, local, timeout=120)
        out = subprocess.run([aapt2, "dump", "resources", local], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             timeout=120).stdout.decode("utf-8", "replace")
    except (ApexError, OSError, subprocess.SubprocessError):
        return None
    finally:
        shutil.rmtree(os.path.dirname(local), ignore_errors=True)
    m = re.search(r"string/apex_accessibility_label\s*\n(?:\s*\([^)]*\)[^\n]*\n)*?\s*\(\)\s*\"([^\"]+)\"", out)
    return m.group(1) if m else None


def app_label(apk):
    aapt2 = build_tool("aapt2")
    if not aapt2 or not apk:
        return None
    out = subprocess.run([aapt2, "dump", "badging", apk], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         timeout=60).stdout.decode("utf-8", "replace")
    m = re.search(r"application-label(?:-zh[^:]*)?:'([^']+)'", out) or re.search(r"application-label:'([^']+)'", out)
    return m.group(1) if m else None
