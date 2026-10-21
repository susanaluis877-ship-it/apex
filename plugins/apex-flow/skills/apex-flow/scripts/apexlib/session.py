"""探索会话：目标和计划、每一步的记录、到过的页面（带编号和样本快照）、截图。

目录结构（默认在当前目录的 .apex/ 下，可用 APEX_HOME 改）：
    .apex/current                     当前会话的目录名
    .apex/sessions/<时间>/session.json 目标、计划、设备、宿主
    .apex/sessions/<时间>/journal.jsonl 每一步一行
    .apex/sessions/<时间>/pages/P<n>.json 每个页面第一次见到时的快照（也是 check 用的样本）
    .apex/sessions/<时间>/shots/<步>.png 截图
"""

import json
import os
import time

from .errors import ApexError
from .screen import SAME_PAGE, _jaccard, content_tokens, page_signature, page_similarity

# 同一页面的新快照和已有样本的内容相似度低于它时另存一份，每页最多存这么多份
SAMPLE_SIMILARITY = 0.8
MAX_SAMPLES = 5


def _about(screen, first_tokens):
    """附加样本和首次到达时比有什么不同，写进 history 方便判断它是什么。"""
    tokens = content_tokens(screen)
    added = [t[2:] for t in sorted(tokens - set(first_tokens)) if t.startswith("t:")][:4]
    switched = [t[2:] for t in sorted(tokens - set(first_tokens)) if t.startswith("c:")][:2]
    parts = []
    if added:
        parts.append("多了 " + " / ".join(added))
    if switched:
        parts.append("开关 " + " / ".join(switched))
    return "；".join(parts) or "内容有变化"


def apex_home():
    return os.environ.get("APEX_HOME") or os.path.join(os.getcwd(), ".apex")


class Session:

    def __init__(self, path):
        self.path = path
        self.meta = self._read_json("session.json", {})
        self.meta.setdefault("plan", [])
        self.meta.setdefault("pages", [])
        self.meta.setdefault("steps", 0)
        self.meta.setdefault("launched", [])

    # ---- 创建和打开 ----

    @classmethod
    def start(cls, goal=None):
        home = apex_home()
        name = time.strftime("%Y%m%d-%H%M%S")
        path = os.path.join(home, "sessions", name)
        os.makedirs(os.path.join(path, "pages"), exist_ok=True)
        os.makedirs(os.path.join(path, "shots"), exist_ok=True)
        with open(os.path.join(home, "current"), "w", encoding="utf-8") as f:
            f.write(name)
        ignore = os.path.join(home, ".gitignore")
        if not os.path.exists(ignore):
            with open(ignore, "w", encoding="utf-8") as f:
                f.write("*\n")
        s = cls(path)
        s.meta["goal"] = goal
        s.meta["created"] = time.strftime("%Y-%m-%d %H:%M:%S")
        s.save()
        return s

    @classmethod
    def current(cls, create=True):
        home = apex_home()
        try:
            with open(os.path.join(home, "current"), encoding="utf-8") as f:
                name = f.read().strip()
            path = os.path.join(home, "sessions", name)
            if os.path.isdir(path):
                return cls(path)
        except OSError:
            pass
        if not create:
            raise ApexError("NO_SESSION", "no exploration session; start one with `plan --goal ...`")
        return cls.start()

    def save(self):
        with open(os.path.join(self.path, "session.json"), "w", encoding="utf-8") as f:
            json.dump(self.meta, f, ensure_ascii=False, indent=1)

    def _read_json(self, name, default):
        try:
            with open(os.path.join(self.path, name), encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return default

    # ---- 页面 ----

    def identify(self, screen):
        """找到快照对应的页面编号；没见过的页面分配新编号并保存样本。返回 (page, 是否新页面)。"""
        sig = page_signature(screen)
        best, best_score = None, 0.0
        for page in self.meta["pages"]:
            score = page_similarity(sig, page["sig"])
            if score > best_score:
                best, best_score = page, score
        tokens = sorted(content_tokens(screen))
        if best is not None and best_score >= SAME_PAGE:
            best["visits"] += 1
            if not best.get("title") and sig["title"]:
                best["title"] = sig["title"]
            # 同一页面内容变化较大时（滚动后、开关状态变了）另存一份样本，check 和 features 会一起用
            samples = best.setdefault("samples", [{"name": best["id"], "tokens": best.get("tokens", [])}])
            similar = max(_jaccard(tokens, s["tokens"]) for s in samples)
            if similar < SAMPLE_SIMILARITY and len(samples) < MAX_SAMPLES:
                name = "%s.%d" % (best["id"], len(samples) + 1)
                samples.append({"name": name, "tokens": tokens, "about": _about(screen, samples[0]["tokens"])})
                self._write_sample(name, screen)
            self.save()
            return best, False
        page_id = "P%d" % (len(self.meta["pages"]) + 1)
        page = {"id": page_id, "title": sig["title"], "pkg": screen.pkg, "activity": sig["activity"],
                "dialog": sig["dialog"], "visits": 1, "sig": sig, "first_step": self.meta["steps"],
                "samples": [{"name": page_id, "tokens": tokens, "about": "首次到达"}]}
        self.meta["pages"].append(page)
        self._write_sample(page_id, screen)
        self.save()
        return page, True

    def _write_sample(self, name, screen):
        with open(os.path.join(self.path, "pages", name + ".json"), "w", encoding="utf-8") as f:
            json.dump(screen.data, f, ensure_ascii=False)

    def sample_names(self, page):
        return [s["name"] for s in page.get("samples") or [{"name": page["id"]}]]

    def page(self, page_id):
        for page in self.meta["pages"]:
            if page["id"] == page_id or page.get("title") == page_id:
                return page
        raise ApexError("BAD_REQUEST", "no page %r in this session (pages: %s)"
                        % (page_id, ", ".join(p["id"] for p in self.meta["pages"]) or "none"))

    def page_snapshot(self, name):
        with open(os.path.join(self.path, "pages", name + ".json"), encoding="utf-8") as f:
            return json.load(f)

    def page_files(self):
        """所有样本：{"P3": 路径, "P3.2": 路径, ...}。"""
        files = {}
        for p in self.meta["pages"]:
            for name in self.sample_names(p):
                files[name] = os.path.join(self.path, "pages", name + ".json")
        return files

    @staticmethod
    def page_name(page):
        if page is None:
            return "?"
        title = page.get("title")
        name = page["id"] + ("「%s」" % title if title else " " + (page.get("activity") or page.get("pkg") or ""))
        return name + ("(弹窗)" if page.get("dialog") else "")

    # ---- 记录 ----

    def next_step(self):
        self.meta["steps"] += 1
        self.save()
        return self.meta["steps"]

    def log(self, entry):
        entry.setdefault("time", time.strftime("%H:%M:%S"))
        with open(os.path.join(self.path, "journal.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def journal(self):
        entries = []
        try:
            with open(os.path.join(self.path, "journal.jsonl"), encoding="utf-8") as f:
                for line in f:
                    if line.strip():
                        entries.append(json.loads(line))
        except OSError:
            pass
        return entries

    def shot_path(self, name):
        """截图路径，同名时加序号，不覆盖之前的截图。"""
        path = os.path.join(self.path, "shots", name + ".png")
        n = 2
        while os.path.exists(path):
            path = os.path.join(self.path, "shots", "%s-%d.png" % (name, n))
            n += 1
        return path

    def path_of_pages(self, limit=12):
        """到过的页面序列（相邻重复合并）。"""
        seq = []
        for e in self.journal():
            page = e.get("page")
            if page and (not seq or seq[-1] != page):
                seq.append(page)
        names = {p["id"]: self.page_name(p) for p in self.meta["pages"]}
        shown = seq[-limit:]
        text = " → ".join(names.get(p, p) for p in shown)
        return ("… → " if len(seq) > limit else "") + text

    # ---- 计划 ----

    def set_plan(self, goal, milestones):
        if goal:
            self.meta["goal"] = goal
        if milestones is not None:
            self.meta["plan"] = [{"title": m.get("title") if isinstance(m, dict) else str(m),
                                  "evidence": m.get("evidence") if isinstance(m, dict) else None,
                                  "done": False} for m in milestones]
            self.meta["plan_step"] = self.meta["steps"]
        self.save()

    def mark_done(self, indexes, note=None):
        plan = self.meta["plan"]
        for index in indexes if isinstance(indexes, (list, tuple)) else [indexes]:
            if not 1 <= index <= len(plan):
                raise ApexError("BAD_REQUEST", "milestone %d does not exist (1..%d)" % (index, len(plan)))
            plan[index - 1]["done"] = True
            plan[index - 1]["done_step"] = self.meta["steps"]
            if note:
                plan[index - 1]["note"] = note
        self.meta["plan_step"] = self.meta["steps"]
        self.save()

    def plan_header(self):
        goal = self.meta.get("goal")
        plan = self.meta["plan"]
        lines = []
        if goal:
            lines.append("目标: " + goal)
        if plan:
            done = sum(1 for m in plan if m["done"])
            current = next((i for i, m in enumerate(plan) if not m["done"]), None)
            if current is None:
                lines.append("计划 %d/%d 全部完成 — 可以开始写流程" % (done, len(plan)))
            else:
                m = plan[current]
                lines.append("计划 %d/%d ▸ %d. %s%s" % (done, len(plan), current + 1, m["title"],
                                                      "（到达的标志：%s）" % m["evidence"] if m.get("evidence") else ""))
        return lines

    def steps_without_progress(self):
        return self.meta["steps"] - self.meta.get("plan_step", 0)

    def render_plan(self):
        lines = self.plan_header()
        for i, m in enumerate(self.meta["plan"], 1):
            lines.append("  %s %d. %s%s%s" % ("✓" if m["done"] else "□", i, m["title"],
                                             "（%s）" % m["evidence"] if m.get("evidence") else "",
                                             " — " + m["note"] if m.get("note") else ""))
        if not self.meta["plan"]:
            lines.append("还没有计划。先把目标拆成几个里程碑，每个写上「到了会看到什么」。")
        return "\n".join(lines)
