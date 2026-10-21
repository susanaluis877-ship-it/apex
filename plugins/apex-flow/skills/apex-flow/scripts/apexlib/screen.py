"""快照模型和页面整理：把节点树整理成人能看懂的结构，对比动作前后的变化。"""

import json
import re

from .errors import ApexError


class Node:
    __slots__ = ("h", "cls", "id", "text", "desc", "hint", "state", "pkg", "b", "range", "f", "children",
                 "parent", "window", "depth")

    def __init__(self, o, parent, window, depth, pkg):
        self.h = o.get("h", -1)
        self.cls = o.get("cls") or ""
        self.id = o.get("id")
        self.text = o.get("text")
        self.desc = o.get("desc")
        self.hint = o.get("hint")
        self.state = o.get("state")
        self.pkg = o.get("pkg") or pkg
        self.b = o.get("b") or [0, 0, 0, 0]
        self.range = o.get("range")
        self.f = set(o.get("f") or [])
        self.parent = parent
        self.window = window
        self.depth = depth
        self.children = [Node(c, self, window, depth + 1, self.pkg) for c in o.get("ch") or []]

    @property
    def simple(self):
        return self.cls.rsplit(".", 1)[-1]

    @property
    def short_id(self):
        if not self.id:
            return None
        return self.id.split(":id/", 1)[1] if ":id/" in self.id else self.id

    @property
    def visible(self):
        return "invisible" not in self.f and self.b[2] > self.b[0] and self.b[3] > self.b[1]

    @property
    def virtual(self):
        """被系统标成不可见、但可以操作的虚拟节点，例如滚轮选择器的每一列。"""
        return "invisible" in self.f and is_interactive(self) and bool(self.text or self.desc) \
            and self.b[2] > self.b[0] and self.b[3] > self.b[1] \
            and (self.parent is None or "invisible" not in self.parent.f)

    @property
    def center(self):
        return ((self.b[0] + self.b[2]) // 2, (self.b[1] + self.b[3]) // 2)

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()

    def ancestors(self):
        n = self.parent
        while n is not None:
            yield n
            n = n.parent


class Screen:
    """快照 JSON 的只读视图。nodes 按窗口从上到下、窗口内先序排列，与设备上的查找顺序一致。"""

    def __init__(self, data):
        self.data = data
        self.seq = data.get("seq")
        self.pkg = data.get("pkg")
        self.activity = data.get("activity")
        self.width = data.get("w", 0)
        self.height = data.get("h", 0)
        self.partial = data.get("partial", False)
        self.windows = []
        for i, w in enumerate(data.get("windows") or []):
            self.windows.append((w, Node(w["root"], None, i, 0, None)))
        self.nodes = [n for _, root in self.windows for n in root.walk()]
        self.by_handle = {n.h: n for n in self.nodes}

    def node(self, ref):
        h = parse_ref(ref)
        if h not in self.by_handle:
            raise ApexError("BAD_REQUEST", "no node @%s in snapshot %s" % (h, self.seq))
        return self.by_handle[h]

    def texts(self):
        out = []
        for n in self.nodes:
            if n.visible or n.virtual:
                for t in (n.text, n.desc):
                    if t and t.strip():
                        out.append(t.strip())
        return out


def parse_ref(ref):
    s = str(ref).strip()
    if s.startswith("@"):
        s = s[1:]
    if not s.lstrip("-").isdigit():
        raise ApexError("BAD_REQUEST", "expected a node ref like @12, got %r" % ref)
    return int(s)


INTERACTIVE = {"clickable", "longClickable", "checkable", "editable", "scrollable", "setProgress"}
BACK_WORDS = ("返回", "向上导航", "上一层级", "navigate up", "back")


def is_interactive(n):
    return bool(n.f & INTERACTIVE) or n.range is not None


def own_label(n):
    return n.text or n.desc or n.hint


def owned(n):
    """属于 n 的子孙：不穿过其他可操作节点（列表里的每一行各自是一个按钮，不算列表的）。"""
    for c in n.children:
        yield c
        if not is_interactive(c):
            yield from owned(c)


def label_parts(n, limit=3):
    """节点的可读标签，分段：自己的文本/描述/提示；没有时取它名下各个子孙的文本（和读屏软件的做法一样）。列表不取标签。"""
    own = own_label(n)
    if own:
        return [own.strip()]
    if "scrollable" in n.f:
        return []
    parts = []
    for c in owned(n):
        if not c.visible or is_interactive(c):
            continue
        t = c.text or c.desc
        if t and t.strip() and t.strip() not in parts:
            parts.append(t.strip())
        if len(parts) >= limit:
            break
    return parts


def label_of(n, limit=3):
    return " | ".join(label_parts(n, limit))


def checkable_inside(n):
    """n 自己可勾选，或者是一行里带着开关/复选框的可点击容器时，返回那个可勾选节点。"""
    if "checkable" in n.f:
        return n
    if "scrollable" in n.f or not n.f & {"clickable", "longClickable"}:
        return None
    for c in owned(n):
        if "checkable" in c.f and c.visible:
            return c
    return None


def kind_of(n):
    if "editable" in n.f:
        return "输入框"
    if n.range is not None or "setProgress" in n.f:
        return "滑块"
    if "checkable" in n.f:
        low = n.cls.lower()
        return "开关" if "switch" in low or "toggle" in low else "复选框"
    if "scrollable" in n.f:
        own = own_label(n) or ""
        return "滚轮" if "选择器" in own or "picker" in n.cls.lower() else "列表"
    label = (own_label(n) or "").lower()
    if any(w in label for w in BACK_WORDS) and len(label) <= 12:
        return "返回"
    if n.f & {"clickable", "longClickable"}:
        return "按钮"
    return "文本"


def fmt_num(v):
    return str(int(v)) if float(v).is_integer() else "%.2f" % v


def value_of(n):
    bits = []
    if n.range is not None:
        r = n.range
        bits.append("%s/%s..%s%s" % (fmt_num(r["cur"]), fmt_num(r["min"]), fmt_num(r["max"]),
                                     "%" if r.get("type") == "percent" else ""))
    if "setProgress" in n.f:
        bits.append("可设值")
    box = checkable_inside(n)
    if box is not None:
        bits.append("[开]" if "checked" in box.f else "[关]")
    if n.state and n.state not in bits:
        bits.append("状态=" + n.state)
    if "editable" in n.f:
        if n.text:
            bits.append("内容=%s" % json.dumps(n.text, ensure_ascii=False))
        if n.hint and n.hint != n.text:
            bits.append("提示=%s" % json.dumps(n.hint, ensure_ascii=False))
    if "scrollable" in n.f:
        arrows = ("↑" if "scrollBackward" in n.f else "") + ("↓" if "scrollForward" in n.f else "")
        if arrows:
            bits.append(arrows + "还有内容")
    if "selected" in n.f:
        bits.append("已选中")
    if "disabled" in n.f:
        bits.append("不可用")
    return " ".join(bits)


def page_title(screen):
    """页面标题：窗口标题出现在顶部区域时用它；否则取顶部区域里第一段不可点击的短文本。"""
    limit = screen.height * 0.18 if screen.height else 400
    for w, root in screen.windows:
        title = (w.get("title") or "").strip()
        if title and any(n.visible and (n.text or "").strip() == title and n.b[1] < limit for n in root.walk()):
            return title
    for _, root in screen.windows[-1:] if screen.windows else []:
        for n in root.walk():
            t = n.text
            if t and n.visible and n.b[1] < limit and len(t) <= 20 and "clickable" not in n.f \
                    and not any("clickable" in a.f for a in n.ancestors() if a.b[3] - a.b[1] < 200):
                return t.strip()
    return None


def window_desc(screen, index, w, root):
    full = screen.width and root.b[2] - root.b[0] >= screen.width * 0.95 and \
        root.b[3] - root.b[1] >= screen.height * 0.9
    parts = ["窗口%d" % index]
    if len(screen.windows) > 1 and index < len(screen.windows) - 1:
        parts.append("上层" + ("" if full else "弹窗"))
    if w.get("title"):
        parts.append(json.dumps(w["title"], ensure_ascii=False))
    if not full:
        parts.append("[%d,%d][%d,%d]" % tuple(root.b))
    return " ".join(parts)


def short_activity(activity):
    if not activity:
        return "?"
    return activity.rsplit(".", 1)[-1]


def occluded(screen):
    """可能被遮住的可操作节点：后绘制的（先序更靠后的）其他节点盖住了它的中心。例如多选模式下底部的删除工具栏
    盖住了标签栏，标签按钮还在树里，点它其实会点到删除。只统计不大于半屏、自己可点击或有 id 的遮挡物。"""
    order = {n.h: i for i, n in enumerate(screen.nodes)}
    area = (screen.width or 1080) * (screen.height or 2400)
    covered = {}
    for n in screen.nodes:
        if not (n.visible and is_interactive(n)):
            continue
        cx, cy = n.center
        ancestors = set(a.h for a in n.ancestors())
        for m in screen.nodes[order[n.h] + 1:]:
            if not m.visible or m.h in ancestors or m.window != n.window:
                continue
            if any(a is n for a in m.ancestors()):
                continue
            b = m.b
            if not (b[0] <= cx < b[2] and b[1] <= cy < b[3]):
                continue
            if (b[2] - b[0]) * (b[3] - b[1]) > area / 2 or not (m.f & {"clickable", "longClickable"} or m.id):
                continue
            covered[n.h] = m
            break
    return covered


def render_outline(screen, max_lines=160):
    """把页面整理成人能看懂的结构：可操作的元素（带 @引用、类型、标签、状态、中心坐标）和独立文本。"""
    lines = ["seq=%s  %s  %s  %dx%d%s" % (screen.seq, screen.pkg, short_activity(screen.activity), screen.width,
                                         screen.height, "  (快照不完整)" if screen.partial else "")]
    title = page_title(screen)
    if title:
        lines.append("标题: " + title)
    body = []
    hidden = occluded(screen)
    for index, (w, root) in enumerate(screen.windows):
        body.append("[%s]" % window_desc(screen, index, w, root))
        _outline(root, 0, set(), body, hidden)
    if len(body) > max_lines:
        hidden = len(body) - max_lines
        body = body[:max_lines] + ["… 还有 %d 行，用 --full 查看完整树或 --grep 搜索" % hidden]
    return "\n".join(lines + body)


def _outline(n, level, covered, out, hidden=None):
    hidden = hidden or {}
    if not n.visible and not n.virtual:
        return
    shown = False
    if is_interactive(n):
        label = label_of(n)
        x, y = n.center
        line = "%s@%-4d %s" % ("  " * level, n.h, kind_of(n))
        if label and own_label(n):
            line += " " + json.dumps(label, ensure_ascii=False)
        elif label:
            # 由子孙文字拼成的标签不是任何节点的文本，分开列出，复制时取其中一段
            line += " 含" + "".join("「%s」" % part for part in label_parts(n))
        value = value_of(n)
        if value:
            line += " " + value
        if n.short_id and not label:
            line += " id=" + n.short_id
        line += "  (%d,%d)" % (x, y)
        if n.virtual:
            line += "  (系统标为不可见的虚拟节点，选择器要加 \"visible\": false)"
        if n.h in hidden:
            m = hidden[n.h]
            line += "  ⚠可能被 @%d %s 盖住，点它可能点到上面那个" % (m.h, m.short_id or m.simple)
        out.append(line)
        shown = True
        # 标签里已经出现的子孙文本不再单独列出
        if label:
            for c in owned(n):
                t = (c.text or c.desc or "").strip()
                if t and not is_interactive(c) and t in label:
                    covered.add(c.h)
    elif (n.text or n.desc) and n.h not in covered:
        t = (n.text or n.desc).strip()
        if t:
            out.append("%s@%-4d 文本 %s" % ("  " * level, n.h, json.dumps(t, ensure_ascii=False)))
            shown = True
    for c in n.children:
        _outline(c, level + (1 if shown and is_interactive(n) else 0), covered, out, hidden)


def render_full(screen):
    lines = ["seq=%s pkg=%s activity=%s size=%dx%d%s" % (screen.seq, screen.pkg, screen.activity, screen.width,
                                                         screen.height, " (partial)" if screen.partial else "")]
    for index, (w, root) in enumerate(screen.windows):
        lines.append("[window %d] layer=%s%s%s" % (index, w.get("layer"), " active" if w.get("active") else "",
                                                   " title=%s" % json.dumps(w["title"], ensure_ascii=False)
                                                   if w.get("title") else ""))
        for n in root.walk():
            lines.append("%s@%d %s" % ("  " * (n.depth + 1), n.h, describe_node(n)))
    return "\n".join(lines)


def describe_node(n):
    parts = [n.simple or "?"]
    if n.id:
        parts.append("id=" + n.short_id)
    for key, value in (("text", n.text), ("desc", n.desc), ("hint", n.hint), ("state", n.state)):
        if value is not None:
            parts.append("%s=%s" % (key, json.dumps(value, ensure_ascii=False)))
    parts.append("[%d,%d][%d,%d]" % tuple(n.b))
    if n.range is not None:
        parts.append("range=%s/%s..%s" % (fmt_num(n.range["cur"]), fmt_num(n.range["min"]), fmt_num(n.range["max"])))
    parts.extend(sorted(n.f))
    return " ".join(parts)


def render_grep(screen, pattern):
    rx = re.compile(re.escape(pattern), re.IGNORECASE)
    lines = []
    for n in screen.nodes:
        hay = " ".join(x for x in (n.text, n.desc, n.hint, n.id, n.state) if x)
        if rx.search(hay):
            owner = next((a for a in n.ancestors() if a.f & {"clickable", "longClickable"}), None)
            line = "@%d %s" % (n.h, describe_node(n))
            if owner is not None and not (n.f & {"clickable", "longClickable"}):
                line += "   ← 点击会落在 @%d %s" % (owner.h, owner.simple)
            if not n.visible:
                line += "   (不可见)"
            lines.append(line)
    return "\n".join(lines) if lines else "没有节点包含 %r" % pattern


def diff_screens(before, after):
    """动作前后的变化：页面切换、窗口增减、文本增减。"""
    out = []
    if before.pkg != after.pkg:
        out.append("切换应用: %s → %s" % (before.pkg, after.pkg))
    elif before.activity != after.activity:
        out.append("切换页面: %s → %s" % (short_activity(before.activity), short_activity(after.activity)))
    if len(after.windows) > len(before.windows):
        out.append("新出现 %d 个窗口（弹窗？）" % (len(after.windows) - len(before.windows)))
    elif len(after.windows) < len(before.windows):
        out.append("关闭了 %d 个窗口" % (len(before.windows) - len(after.windows)))
    old, new = before.texts(), after.texts()
    added = [t for t in dict.fromkeys(new) if t not in old]
    removed = [t for t in dict.fromkeys(old) if t not in new]
    if added:
        out.append("新增文本: " + clip_list(added))
    if removed:
        out.append("消失文本: " + clip_list(removed))
    old_states, new_states = control_states(before), control_states(after)
    changed = ["%s: %s → %s" % (k, old_states[k], v) for k, v in new_states.items()
               if k in old_states and old_states[k] != v]
    if changed:
        out.append("状态变化: " + clip_list(changed, width=48))
    return out


def control_states(screen):
    """开关、滑块、输入框的当前值，按「标签」索引，用来对比动作前后的变化。"""
    states = {}
    for n in screen.nodes:
        if not n.visible:
            continue
        if "checkable" in n.f or n.range is not None or "editable" in n.f:
            key = label_of(n) or n.short_id or "@%d" % n.h
            if "checkable" in n.f:
                value = "开" if "checked" in n.f else "关"
            elif n.range is not None:
                value = fmt_num(n.range["cur"])
            else:
                value = json.dumps(n.text or "", ensure_ascii=False)
            states.setdefault(key, value)
    return states


def clip_list(items, limit=8, width=24):
    shown = [t if len(t) <= width else t[:width - 1] + "…" for t in items[:limit]]
    more = " …等 %d 项" % len(items) if len(items) > limit else ""
    return " / ".join(shown) + more


# ---- 页面身份 ----

DIGITS = re.compile(r"\d")
SAME_PAGE = 0.6


def is_fullscreen(screen, root):
    return bool(screen.width) and root.b[2] - root.b[0] >= screen.width * 0.95 \
        and root.b[3] - root.b[1] >= screen.height * 0.9


def page_signature(screen):
    """页面身份特征：包名、Activity、标题、顶层是不是弹窗，加上稳定的 id 和不含数字的短文本。"""
    ids, texts = set(), set()
    for n in screen.nodes:
        if not (n.visible or n.virtual):
            continue
        if n.short_id:
            ids.add(n.short_id)
        for t in (n.text, n.desc):
            if t and len(t.strip()) <= 20 and not DIGITS.search(t):
                texts.add(t.strip())
    dialog = bool(screen.windows) and not is_fullscreen(screen, screen.windows[0][1])
    return {"pkg": screen.pkg, "activity": short_activity(screen.activity), "title": page_title(screen),
            "dialog": dialog, "ids": sorted(ids), "texts": sorted(texts), "tabs": selected_tabs(screen)}


def selected_tabs(screen):
    """选中的标签页：在屏幕顶部或底部标签栏区域里、selected 的短文字（选中的那个标签常常不可点击）。
    同一个 Activity 里的不同标签页靠它区分。"""
    tabs = set()
    h = screen.height or 2400
    for n in screen.nodes:
        if not (n.visible and "selected" in n.f):
            continue
        center = (n.b[1] + n.b[3]) / 2.0
        if h * 0.25 < center < h * 0.75:
            continue
        label = label_of(n)
        if label and len(label) <= 20:
            tabs.add(label)
    return sorted(tabs)


def content_tokens(screen):
    """页面内容的特征：文字、id，以及每个开关的状态。同一页面内容变化较大时另存一份样本。"""
    tokens = set()
    for n in screen.nodes:
        if not (n.visible or n.virtual):
            continue
        for t in (n.text, n.desc):
            if t and t.strip():
                tokens.add("t:" + t.strip())
        if n.short_id:
            tokens.add("i:" + n.short_id)
        if "checkable" in n.f:
            tokens.add("c:%s=%s" % (n.short_id or (n.text or n.desc or n.h), "checked" in n.f))
    return tokens


def _jaccard(a, b):
    a, b = set(a), set(b)
    return len(a & b) / len(a | b) if a | b else 1.0


def page_similarity(a, b):
    """两个页面特征的相似度，0~1。包名、弹窗状态或选中的标签页不同直接为 0；两边都有标题时只看标题是否相同。"""
    if a["pkg"] != b["pkg"] or a["dialog"] != b["dialog"]:
        return 0.0
    # 切换标签的瞬间可能两个标签都是 selected，所以只有完全不相交才算不同的标签页
    if a.get("tabs") and b.get("tabs") and not set(a["tabs"]) & set(b["tabs"]):
        return 0.0
    # activity 不明（例如宿主刚重装、还没收到窗口事件）时不拿它区分页面
    unknown = "?" in (a["activity"], b["activity"])
    same_activity = unknown or a["activity"] == b["activity"]
    if a["title"] and b["title"]:
        return 1.0 if a["title"] == b["title"] and same_activity else 0.0
    return 0.5 * same_activity + 0.25 * _jaccard(a["ids"], b["ids"]) + 0.25 * _jaccard(a["texts"], b["texts"])
