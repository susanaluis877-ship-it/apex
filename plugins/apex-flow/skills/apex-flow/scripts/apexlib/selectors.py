"""选择器建议：根据节点属性生成候选，在设备上用真实的 Selector 验证，再按稳定性排序。"""

import re


DYNAMIC = re.compile(r"\d")


def selector_candidates(screen, n):
    """为节点生成候选选择器（还没验证），按稳定性从高到低排列。每项为 (selector, 说明)。"""
    cands = []

    def add(sel, why):
        if sel not in [c[0] for c in cands]:
            cands.append((sel, why))

    sid = n.short_id
    if sid:
        note = "id 看起来像混淆过的" if re.fullmatch(r"[a-z]{1,2}\d?|[a-z]\d{1,3}", sid) else "资源 id"
        add({"id": sid}, note)
    if n.desc:
        if DYNAMIC.search(n.desc):
            add({"descMatches": dynamic_regex(n.desc)}, "描述含数字，用正则容忍变化")
        add({"desc": n.desc}, "描述" + ("（含数字，可能会变）" if DYNAMIC.search(n.desc) else ""))
    if n.text:
        if DYNAMIC.search(n.text):
            add({"textMatches": dynamic_regex(n.text)}, "文本含数字，用正则容忍变化")
        add({"text": n.text}, "文本" + ("（含数字，可能会变）" if DYNAMIC.search(n.text) else ""))
    if sid and n.text:
        add({"id": sid, "text": n.text}, "id + 文本")
    if n.text and n.simple:
        add({"text": n.text, "className": n.simple}, "文本 + 类名")
    if n.desc and sid:
        add({"id": sid, "desc": n.desc}, "id + 描述")
    # 自己没有特征的可点击容器：用里面的文本定位，click 会落到最近的可点击祖先。
    # 不含数字的文字优先（时间、数量会变）；也给出关系写法：含有某段文字的那个可点击容器
    if not (sid or n.text or n.desc):
        stable, dynamic = [], []
        for c in n.walk():
            if c is n or not c.visible:
                continue
            for key, value in (("text", c.text), ("desc", c.desc)):
                if value:
                    (dynamic if DYNAMIC.search(value) else stable).append((key, value))
        for key, value in stable[:3]:
            add({key: value}, "子节点%s，点击会落到这个容器" % ("文本" if key == "text" else "描述"))
        for key, value in stable[:2]:
            add({"clickable": True, "hasDescendant": {key: value}}, "含有这段%s的可点击容器"
                % ("文本" if key == "text" else "描述"))
        for c in n.walk():
            if c is not n and c.visible and c.short_id:
                add({"id": c.short_id}, "子节点 id，点击会落到这个容器")
                break
        for key, value in dynamic[:2]:
            add({key: value}, "子节点%s（含数字，可能会变）" % ("文本" if key == "text" else "描述"))
    if n.f & {"checkable"} and sid:
        add({"id": sid, "checked": "checked" in n.f}, "id + 当前勾选状态")
    if not n.visible:
        # Apex 的选择器默认只匹配可见节点
        cands = [(dict(sel, visible=False), why + "（不可见节点）") for sel, why in cands]
    return cands


def dynamic_regex(text):
    parts = re.split(r"(\d+(?:[.,]\d+)*)", text)
    return "".join("\\d+(?:[.,]\\d+)*" if i % 2 else re.escape(p) for i, p in enumerate(parts))


def click_owner(n):
    """click 实际落到的节点：自己或最近的可点击祖先。祖先是整个列表、月视图这类容器时，SDK 改按 n 的中心坐标点，落点就是 n。"""
    if "clickable" in n.f:
        return n
    for a in n.ancestors():
        if "clickable" in a.f:
            return n if is_container_of(a, n) else a
    return n


def _area(b):
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


def is_container_of(ancestor, n):
    """和 SDK 的 ActionRunner.isContainerOf 一致：列表 / 滚动容器，或占屏幕四分之一以上、面积是目标 8 倍以上。"""
    cls = ancestor.cls
    if "scrollable" in ancestor.f or cls.endswith(("RecyclerView", "ListView", "GridView", "ScrollView")) \
            or "ViewPager" in cls:
        return True
    root = ancestor
    while root.parent is not None:
        root = root.parent
    a, t, s = _area(ancestor.b), _area(n.b), _area(root.b)
    return t > 0 and s > 0 and a * 4 >= s and a >= t * 8


def suggest(bridge, screen, n, purpose="click"):
    """在设备上验证候选选择器，返回排序后的列表：[{selector, count, first, ok, why}]。"""
    cands = selector_candidates(screen, n)
    if not cands:
        return []
    hidden_variants = [dict(c[0], visible=False) for c in cands]
    resp = bridge.request({"cmd": "find", "seq": screen.seq, "selectors": [c[0] for c in cands] + hidden_variants})
    results = resp["results"][:len(cands)]
    all_counts = [r["count"] for r in resp["results"][len(cands):]]
    target = click_owner(n) if purpose == "click" else n
    ranked = []
    for order, ((sel, why), r) in enumerate(zip(cands, results)):
        first = r["handles"][0] if r["handles"] else None
        hit = first is not None and (first == n.h or (purpose == "click" and first in screen.by_handle
                                                      and click_owner(screen.by_handle[first]) is target))
        hidden = max(0, all_counts[order] - r["count"]) if sel.get("visible") is not False else 0
        if hidden:
            why += "；另有 %d 个不可见的同样节点（其他标签页或隐藏内容），换个页面状态时可能点到它们" % hidden
        ranked.append({"selector": sel, "count": r["count"], "first": first, "ok": hit, "why": why,
                       "unique": r["count"] == 1, "order": order, "hidden": hidden})
    # 能命中目标且唯一的最好（不可见节点里也没有同样的更好）；其次是命中目标但依赖顺序（排第一个）的
    ranked.sort(key=lambda c: (not (c["ok"] and c["unique"]), bool(c["hidden"]), not c["ok"], c["order"]))
    return ranked


def best_selector(ranked):
    for c in ranked:
        if c["ok"]:
            return c
    return None
