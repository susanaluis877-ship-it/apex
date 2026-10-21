"""adb 模式：Apex 调试桥还不能用（宿主没装、无障碍服务没开）时，用 adb 看页面和操作，完成安装确认、开启服务这类准备工作。

- 看页面：`uiautomator dump`，转换成和 Apex 快照相同的 JSON，页面结构的显示方式不变。
- 操作：`input tap / swipe / keyevent / text`。

uiautomator 连接期间系统会让所有无障碍服务断开，所以只在 Apex 服务没连上时使用；服务一连上就换回 Apex。
"""

import re
import time
import xml.etree.ElementTree as ET

from .errors import ApexError

KEYCODES = {"back": 4, "home": 3, "recents": 187, "enter": 66, "wakeup": 224}
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def dump(adb, retries=3):
    """返回 Apex 快照格式的 JSON（dict）。"""
    last = None
    for _ in range(retries):
        out = adb.run("exec-out", "uiautomator dump /dev/tty", timeout=30).decode("utf-8", "replace")
        start = out.find("<?xml")
        end = out.rfind("</hierarchy>")
        if start >= 0 and end > start:
            return xml_to_snapshot(out[start:end + len("</hierarchy>")], screen_size(adb))
        last = out.strip()[:200]
        time.sleep(1)
    raise ApexError("ADB", "uiautomator dump failed: %s" % last)


def screen_size(adb):
    out = adb.shell("wm size", timeout=10)
    sizes = re.findall(r"(\d+)x(\d+)", out)
    if not sizes:
        return 0, 0
    w, h = sizes[-1]  # Override size 在 Physical size 后面
    return int(w), int(h)


def xml_to_snapshot(xml, size=(0, 0)):
    root = ET.fromstring(xml)
    counter = [0]
    windows = []
    for i, top in enumerate(n for n in root if n.tag == "node"):
        windows.append({"id": i, "layer": len(list(root)) - i, "active": i == 0, "root": _node(top, counter)})
    pkg = windows[0]["root"].get("pkg") if windows else None
    return {"format": 1, "seq": 0, "pkg": pkg, "activity": None, "w": size[0], "h": size[1], "partial": False,
            "windows": windows, "source": "uiautomator"}


def _node(el, counter):
    a = el.attrib
    handle = counter[0]
    counter[0] += 1
    o = {"h": handle, "cls": a.get("class") or ""}
    if a.get("resource-id"):
        o["id"] = a["resource-id"]
    if a.get("text"):
        o["text"] = a["text"]
    if a.get("content-desc"):
        o["desc"] = a["content-desc"]
    if a.get("package"):
        o["pkg"] = a["package"]
    m = BOUNDS.match(a.get("bounds", ""))
    o["b"] = [int(v) for v in m.groups()] if m else [0, 0, 0, 0]
    flags = []
    for attr, flag in (("clickable", "clickable"), ("long-clickable", "longClickable"), ("checkable", "checkable"),
                       ("checked", "checked"), ("selected", "selected"), ("scrollable", "scrollable"),
                       ("focused", "focused")):
        if a.get(attr) == "true":
            flags.append(flag)
    if "EditText" in o["cls"]:
        flags.append("editable")
    if a.get("enabled") == "false":
        flags.append("disabled")
    if a.get("password") == "true":
        flags.append("password")
    if flags:
        o["f"] = flags
    children = [_node(c, counter) for c in el if c.tag == "node"]
    if children:
        o["ch"] = children
    return o


def tap(adb, x, y):
    adb.shell("input tap %d %d" % (x, y))


def swipe(adb, x1, y1, x2, y2, ms=300):
    adb.shell("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, ms))


def key(adb, name):
    code = KEYCODES.get(name, name)
    adb.shell("input keyevent %s" % code)


def text(adb, value):
    if any(ord(c) > 127 for c in value):
        raise ApexError("BAD_REQUEST", "adb input text only supports ASCII; enter non-ASCII text after Apex is ready")
    escaped = re.sub(r"([\\\\'\"`$&|;<>()* ])", lambda m: "%s" if m.group(1) == " " else "\\\\" + m.group(1), value)
    adb.shell("input text %s" % escaped)
