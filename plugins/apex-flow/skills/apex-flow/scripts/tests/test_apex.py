"""apex.py 的单测：页面整理、选择器候选、变化对比、协议收发。运行：python3 -m unittest discover -s tests"""

import json
import os
import socket
import sys
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import types  # noqa: E402

from apexlib import device, errors, screen, selectors, tools  # noqa: E402

# 测试里统一用 apex.xxx 访问各模块的函数
apex = types.SimpleNamespace(**{k: v for m in (errors, device, screen, selectors, tools) for k, v in vars(m).items()
                                if not k.startswith("__")})


def node(cls, h, b, **kw):
    o = {"h": h, "cls": cls, "b": b}
    f = kw.pop("f", None)
    if f:
        o["f"] = f
    ch = kw.pop("ch", None)
    if ch:
        o["ch"] = ch
    o.update(kw)
    return o


def settings_page():
    """仿照系统设置「显示与亮度」页：标题、返回、开关行、滑块、可滚动列表。"""
    rows = [
        node("android.widget.LinearLayout", 4, [0, 400, 1080, 560], f=["clickable"], ch=[
            node("android.widget.TextView", 5, [48, 420, 600, 480], text="深色模式"),
            node("android.widget.Switch", 6, [900, 440, 1040, 520], id="android:id/switch_widget",
                 f=["checkable"]),
        ]),
        node("android.widget.SeekBar", 7, [48, 600, 1032, 680], desc="亮度", f=["setProgress"],
             range={"type": "int", "min": 0, "max": 100, "cur": 45}),
        node("android.widget.LinearLayout", 8, [0, 700, 1080, 860], f=["clickable"], ch=[
            node("android.widget.TextView", 9, [48, 720, 600, 780], text="字体大小"),
            node("android.widget.TextView", 10, [48, 790, 600, 840], text="标准"),
        ]),
        node("android.widget.TextView", 11, [48, 900, 600, 960], text="已用 23 分钟"),
    ]
    root = node("android.widget.FrameLayout", 0, [0, 0, 1080, 2400], pkg="com.android.settings", ch=[
        node("android.widget.ImageButton", 1, [0, 100, 140, 240], desc="向上导航", f=["clickable"]),
        node("android.widget.TextView", 2, [160, 120, 600, 220], text="显示与亮度"),
        node("androidx.recyclerview.widget.RecyclerView", 3, [0, 300, 1080, 2400],
             id="com.android.settings:id/recycler_view", f=["scrollable", "scrollForward"], ch=rows),
    ])
    return {"format": 1, "seq": 12, "pkg": "com.android.settings", "activity": "com.android.settings.SubSettings",
            "w": 1080, "h": 2400, "partial": False,
            "windows": [{"id": 1, "layer": 1, "active": True, "root": root}]}


class OutlineTest(unittest.TestCase):

    def setUp(self):
        self.screen = apex.Screen(settings_page())

    def test_outline_shows_kinds_labels_and_states(self):
        text = apex.render_outline(self.screen)
        self.assertIn("seq=12  com.android.settings  SubSettings  1080x2400", text)
        self.assertIn("标题: 显示与亮度", text)
        self.assertIn('@1    返回 "向上导航"', text)
        self.assertIn('@3    列表 ↓还有内容', text)
        self.assertIn('@4    按钮 含「深色模式」 [关]', text)
        self.assertIn('@7    滑块 "亮度" 45/0..100 可设值', text)
        self.assertIn('@8    按钮 含「字体大小」「标准」', text, "拼起来的标签分段显示，不当成一个文本")
        self.assertIn('@11   文本 "已用 23 分钟"', text)
        # 已经出现在按钮标签里的文本不再单独列出
        self.assertNotIn('文本 "字体大小"', text)

    def test_grep_points_to_click_owner(self):
        text = apex.render_grep(self.screen, "字体")
        self.assertIn("@9 TextView", text)
        self.assertIn("← 点击会落在 @8 LinearLayout", text)

    def test_title_and_children(self):
        self.assertEqual("显示与亮度", apex.page_title(self.screen))
        self.assertEqual("字体大小 | 标准", apex.label_of(self.screen.node("@8")))
        self.assertIs(self.screen.node("@6"), apex.checkable_inside(self.screen.node("@4")))

    def test_bad_ref(self):
        with self.assertRaises(apex.ApexError):
            self.screen.node("@99")
        with self.assertRaises(apex.ApexError):
            apex.parse_ref("abc")


class SelectorTest(unittest.TestCase):

    def setUp(self):
        self.screen = apex.Screen(settings_page())

    def test_candidates_prefer_id_then_desc_then_text(self):
        sw = [c[0] for c in apex.selector_candidates(self.screen, self.screen.node("@6"))]
        self.assertEqual({"id": "switch_widget"}, sw[0])
        slider = [c[0] for c in apex.selector_candidates(self.screen, self.screen.node("@7"))]
        self.assertEqual({"desc": "亮度"}, slider[0])

    def test_container_without_features_uses_child_texts(self):
        cands = apex.selector_candidates(self.screen, self.screen.node("@4"))
        self.assertEqual({"text": "深色模式"}, cands[0][0])
        self.assertIn("容器", cands[0][1])
        self.assertIn({"clickable": True, "hasDescendant": {"text": "深色模式"}}, [c[0] for c in cands])

    def test_container_prefers_text_without_digits(self):
        page = settings_page()
        row = page["windows"][0]["root"]["ch"][2]["ch"][2]
        row["ch"][0]["text"] = "07:30"
        row["ch"][1]["text"] = "法定工作日 | Apex"
        screen = apex.Screen(page)
        cands = [c[0] for c in apex.selector_candidates(screen, screen.node("@8"))]
        self.assertEqual({"text": "法定工作日 | Apex"}, cands[0])
        self.assertEqual({"text": "07:30"}, cands[-1])

    def test_dynamic_text_gets_regex(self):
        cands = [c[0] for c in apex.selector_candidates(self.screen, self.screen.node("@11"))]
        self.assertEqual({"textMatches": "已用\\ \\d+(?:[.,]\\d+)*\\ 分钟"}, cands[0])
        import re
        self.assertTrue(re.fullmatch(cands[0]["textMatches"], "已用 5 分钟"))

    def test_suggest_ranks_unique_hits_first(self):
        class FakeBridge:
            def request(self, payload):
                # 第一个候选（文本 深色模式）命中两次，第二个之后都唯一
                results = []
                for i, sel in enumerate(payload["selectors"]):
                    if i == 0:
                        results.append({"count": 2, "handles": [5, 40]})
                    else:
                        results.append({"count": 1, "handles": [5]})
                return {"results": results}

        n = self.screen.node("@4")
        ranked = apex.suggest(FakeBridge(), self.screen, n, "click")
        self.assertTrue(ranked[0]["unique"] and ranked[0]["ok"])
        self.assertFalse(ranked[-1]["unique"])
        self.assertEqual(ranked[0], apex.best_selector(ranked))

    def test_click_owner(self):
        self.assertIs(self.screen.node("@4"), apex.click_owner(self.screen.node("@5")))
        self.assertIs(self.screen.node("@1"), apex.click_owner(self.screen.node("@1")))


class DiffTest(unittest.TestCase):

    def test_reports_page_switch_and_texts(self):
        before = apex.Screen(settings_page())
        page = settings_page()
        page["activity"] = "com.android.settings.Brightness"
        page["seq"] = 13
        page["windows"][0]["root"]["ch"][1]["text"] = "亮度调节"
        after = apex.Screen(page)
        changes = apex.diff_screens(before, after)
        self.assertIn("切换页面: SubSettings → Brightness", changes)
        self.assertIn("新增文本: 亮度调节", changes)
        self.assertIn("消失文本: 显示与亮度", changes)


class CheckRenderTest(unittest.TestCase):

    def test_matrix_and_warnings(self):
        resp = {"samples": [{"name": "home"}, {"name": "task"}],
                "states": [{"id": "home", "matches": ["home"]}, {"id": "task", "matches": ["home", "task"]}],
                "interceptors": [{"id": "ad", "global": True, "matches": [], "errors": {"task": "boom"}}],
                "warnings": [{"kind": "ambiguous", "message": "x"}]}
        text = apex.render_check(resp)
        self.assertIn("状态 home", text)
        self.assertIn("拦截器 ad (全局)", text)
        self.assertIn("!", text)
        self.assertIn("[ambiguous] x", text)


class ProtocolTest(unittest.TestCase):

    def test_stream_reads_lines_until_close(self):
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]
        received = []

        def serve():
            conn, _ = server.accept()
            data = b""
            while not data.endswith(b"\n"):
                data += conn.recv(1024)
            received.append(json.loads(data))
            conn.sendall(b'{"ev": "trace", "msg": "a"}\n{"ok": true, "ev": "result"')
            conn.sendall(b', "result": {"status": "SUCCESS"}}\n')
            conn.close()

        t = threading.Thread(target=serve)
        t.start()
        bridge = apex.Bridge.__new__(apex.Bridge)
        bridge._port = port
        lines = list(bridge.stream({"cmd": "run"}, timeout=5))
        t.join()
        server.close()
        self.assertEqual({"cmd": "run"}, received[0])
        self.assertEqual("trace", lines[0]["ev"])
        self.assertEqual("SUCCESS", lines[1]["result"]["status"])

    def test_check_ok_raises_with_code_and_path(self):
        with self.assertRaises(apex.ApexError) as ctx:
            apex.check_ok({"ok": False, "error": {"code": "PARSE_ERROR", "message": "m", "path": "flow.states"}})
        self.assertEqual("PARSE_ERROR", ctx.exception.code)
        self.assertEqual("flow.states", ctx.exception.path)


class SafetyTest(unittest.TestCase):

    def test_never_force_stops(self):
        root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
        for dirpath, _, files in os.walk(root):
            for name in files:
                if name.endswith(".py") and not name.startswith("test_"):
                    with open(os.path.join(dirpath, name), encoding="utf-8") as f:
                        self.assertNotIn("force-stop", f.read(), name)


if __name__ == "__main__":
    unittest.main()
