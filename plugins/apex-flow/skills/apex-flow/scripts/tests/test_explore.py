"""探索相关模块的单测：截图编码、会话和页面身份、预期核对、动作简写、MCP 握手。"""

import copy
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from apexlib import mcp, shot  # noqa: E402
from apexlib.errors import ApexError  # noqa: E402
from apexlib.screen import Screen, page_signature, page_similarity  # noqa: E402
from apexlib.session import Session  # noqa: E402
from apexlib.tools import Output, Toolkit, _feature_candidates, _matches_local, _split  # noqa: E402
from test_apex import settings_page  # noqa: E402


def raw_screencap(width, height, header=16, color=(10, 20, 30)):
    head = struct.pack("<III", width, height, 1) + (b"\0" * (header - 12))
    return head + bytes(list(color) + [255]) * (width * height)


def decode_png(data):
    """最小 PNG 解码（只支持本工具生成的 8 位 RGB、滤波 0），返回 (宽, 高, 像素)。"""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, width, height = 8, b"", 0, 0
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height = struct.unpack(">II", body[:8])
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = width * 3 + 1
    pixels = b"".join(raw[y * stride + 1:(y + 1) * stride] for y in range(height))
    return width, height, pixels


class ShotTest(unittest.TestCase):

    def test_decode_downscales_and_png_round_trips(self):
        for header in (12, 16):
            image, step = shot.decode_raw(raw_screencap(1080, 40, header))
            self.assertEqual(2, step)
            self.assertEqual((540, 20), (image.width, image.height))
            w, h, pixels = decode_png(image.png())
            self.assertEqual((540, 20), (w, h))
            self.assertEqual(bytes([0, 16, 16]), pixels[:3], "每个通道只保留高 4 位")

    def test_annotation_draws_box_and_label(self):
        image, step = shot.decode_raw(raw_screencap(1080, 400))
        shot.annotate(image, step, [(12, [100, 100, 500, 300])])
        w, _, pixels = decode_png(image.png())
        color = bytes(shot.PALETTE[12 % len(shot.PALETTE)])
        # 框的左边线在 x=50（缩小一半），标签背景从 (52,52) 开始
        self.assertEqual(color, pixels[(120 * w + 50) * 3:(120 * w + 50) * 3 + 3])
        self.assertEqual(color, pixels[(52 * w + 53) * 3:(52 * w + 53) * 3 + 3])

    def test_rejects_unknown_format(self):
        with self.assertRaises(ApexError):
            shot.decode_raw(struct.pack("<III", 10, 10, 5) + b"\0" * 400)


class SessionTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["APEX_HOME"] = self.tmp.name

    def tearDown(self):
        del os.environ["APEX_HOME"]
        self.tmp.cleanup()

    def test_pages_get_stable_ids(self):
        s = Session.start("调亮度")
        page = settings_page()
        p1, new1 = s.identify(Screen(page))
        again = copy.deepcopy(page)
        again["seq"] = 20
        p1b, new2 = s.identify(Screen(again))
        other = copy.deepcopy(page)
        other["windows"][0]["root"]["ch"][1]["text"] = "声音与振动"
        p2, new3 = s.identify(Screen(other))
        self.assertEqual(("P1", True), (p1["id"], new1))
        self.assertEqual(("P1", False), (p1b["id"], new2))
        self.assertEqual(("P2", True), (p2["id"], new3))
        self.assertEqual(2, Session.current().meta["pages"][0]["visits"])
        self.assertTrue(os.path.exists(os.path.join(s.path, "pages", "P2.json")))

    def test_same_page_with_different_content_gets_extra_sample(self):
        s = Session.start()
        page = settings_page()
        s.identify(Screen(page))
        toggled = copy.deepcopy(page)
        row = toggled["windows"][0]["root"]["ch"][2]["ch"][0]
        row["ch"][1]["f"] = ["checkable", "checked"]
        for i in range(6):
            toggled["windows"][0]["root"]["ch"][2]["ch"].append(
                {"h": 100 + i, "cls": "android.widget.TextView", "b": [0, 1000 + i * 60, 600, 1050 + i * 60],
                 "text": "新的一行 %s" % "abcdef"[i]})
        p1, new = s.identify(Screen(toggled))
        self.assertEqual(("P1", False), (p1["id"], new))
        self.assertEqual(["P1", "P1.2"], s.sample_names(p1))
        self.assertEqual({"P1", "P1.2"}, set(s.page_files()))
        s.identify(Screen(toggled))
        self.assertEqual(2, len(s.sample_names(Session.current().meta["pages"][0])), "内容相近时不再另存")

    def test_check_labels_prefer_exact_sample_names(self):
        captured = {}

        class FakeBridge:
            def request(self, payload):
                captured.update(payload["snapshots"])
                return {"samples": [], "states": [], "interceptors": [], "warnings": []}

        s = Session.start()
        page = settings_page()
        s.identify(Screen(page))
        other = copy.deepcopy(page)
        for i in range(6):
            other["windows"][0]["root"]["ch"][2]["ch"].append(
                {"h": 100 + i, "cls": "android.widget.TextView", "b": [0, 0, 10, 10], "text": "行 %s" % "abcdef"[i]})
        s.identify(Screen(other))
        kit = Toolkit.__new__(Toolkit)
        kit.bridge = FakeBridge()
        # 参数顺序不影响结果：P1.2 写在 P1 后面也生效
        kit.check({"id": "f"}, labels={"P1": "home", "P1.2": "other_tab"})
        self.assertEqual({"home#P1", "other_tab#P1.2"}, set(captured))
        captured.clear()
        kit.check({"id": "f"}, labels={"P1": "home"})
        self.assertEqual({"home#P1", "home#P1.2"}, set(captured))

    def test_plan_progress_and_journal_path(self):
        s = Session.start()
        s.set_plan("把亮度调到最高", [{"title": "打开设置", "evidence": "标题 设置"}, {"title": "调亮度"}])
        self.assertIn("计划 0/2 ▸ 1. 打开设置（到达的标志：标题 设置）", "\n".join(s.plan_header()))
        s.next_step()
        s.mark_done(1)
        self.assertIn("计划 1/2 ▸ 2. 调亮度", "\n".join(s.plan_header()))
        s.mark_done([1, 2])
        self.assertIn("全部完成", "\n".join(s.plan_header()))
        s.meta["pages"] = [{"id": "P1", "title": "设置", "visits": 1}, {"id": "P2", "title": "显示与亮度", "visits": 1}]
        for page in ("P1", "P1", "P2"):
            s.log({"kind": "act", "page": page})
        self.assertEqual("P1「设置」 → P2「显示与亮度」", s.path_of_pages())
        with self.assertRaises(ApexError):
            s.mark_done(5)


class PageIdentityTest(unittest.TestCase):

    def test_title_prefers_window_title_when_shown(self):
        page = settings_page()
        page["windows"][0]["title"] = "显示与亮度"
        root = page["windows"][0]["root"]
        root["ch"].insert(0, {"h": 90, "cls": "android.widget.TextView", "b": [0, 0, 400, 90], "text": "小节标题"})
        from apexlib.screen import page_title
        self.assertEqual("显示与亮度", page_title(Screen(page)))
        page["windows"][0]["title"] = "时钟"
        self.assertEqual("小节标题", page_title(Screen(page)), "窗口标题不在页面上时不用它")

    def test_selected_tab_separates_pages(self):
        def tabbed(selected):
            page = settings_page()
            page["windows"][0]["root"]["ch"].append(
                {"h": 200, "cls": "android.widget.LinearLayout", "b": [0, 2200, 1080, 2400], "f": ["clickable"], "ch": [
                    {"h": 201 + i, "cls": "android.widget.TextView", "b": [i * 270, 2200, i * 270 + 270, 2400],
                     "text": name, "f": ["selected"] if name == selected else []}
                    for i, name in enumerate(["闹钟", "世界时钟"])]})
            page["windows"][0]["root"]["ch"][1]["text"] = "多个标签页"
            return page_signature(Screen(page))
        a, b = tabbed("闹钟"), tabbed("世界时钟")
        self.assertEqual(["闹钟"], a["tabs"])
        self.assertEqual(0.0, page_similarity(a, b))
        self.assertEqual(1.0, page_similarity(a, tabbed("闹钟")))

    def test_virtual_nodes_need_a_visible_parent(self):
        page = settings_page()
        hidden_tab = {"h": 300, "cls": "android.widget.FrameLayout", "b": [0, 0, 1080, 800], "f": ["invisible"],
                      "ch": [{"h": 301, "cls": "android.widget.NumberPicker", "b": [0, 0, 500, 800],
                              "text": "5分钟", "f": ["invisible", "scrollable"]}]}
        page["windows"][0]["root"]["ch"].append(hidden_tab)
        screen = Screen(page)
        self.assertFalse(screen.node("@301").virtual)

    def test_dialog_and_title_decide(self):
        base = page_signature(Screen(settings_page()))
        dialog = dict(base, dialog=True)
        self.assertEqual(0.0, page_similarity(base, dialog))
        renamed = dict(base, title="声音")
        self.assertEqual(0.0, page_similarity(base, renamed))
        untitled = dict(base, title=None)
        self.assertGreaterEqual(page_similarity(base, untitled), 0.6)


class ToolHelpersTest(unittest.TestCase):

    def test_split_keeps_json_together(self):
        self.assertEqual(["click", '{"text": "a b"}'], _split('click {"text": "a b"}'))
        self.assertEqual(["setText", "@3", "hello", "world"], _split("setText @3 hello world"))

    def test_expectations(self):
        screen = Screen(settings_page())
        page = {"id": "P2", "title": "显示与亮度"}
        checks = Toolkit._check_expect({"text": ["亮度", "深色"], "gone": "WLAN", "page": "显示与亮度",
                                         "changed": True}, screen, page, True)
        self.assertTrue(all(ok for _, ok in checks), checks)
        checks = Toolkit._check_expect({"text": "声音"}, screen, page, False)
        self.assertEqual([("页面上有「声音」", False)], checks)

    def test_expect_accepts_selectors_and_rejects_unknown_keys(self):
        from apexlib.tools import _normalize_expect
        self.assertEqual({"has": {"id": "rb_dark_button", "checked": True}},
                         _normalize_expect({"id": "rb_dark_button", "checked": True}))
        self.assertEqual({"text": "亮度"}, _normalize_expect("亮度"))
        self.assertEqual({"text": "亮度"}, _normalize_expect({"text": "亮度"}))
        with self.assertRaises(ApexError):
            _normalize_expect({"txet": "亮度"})

    def test_feature_candidates_skip_dynamic_text(self):
        screen = Screen(settings_page())
        cands = _feature_candidates(screen, "显示与亮度")
        self.assertEqual({"text": "显示与亮度"}, cands[0])
        self.assertNotIn({"id": "recycler_view"}, cands, "通用 id 不当页面特征")
        from apexlib.tools import _generic_id
        self.assertTrue(_generic_id("icon"))
        self.assertTrue(_generic_id("content_parent"))
        self.assertFalse(_generic_id("settings_homepage_container"))
        self.assertFalse(_generic_id("rb_dark_button"))
        self.assertFalse(_generic_id("brightnessSlider"))
        self.assertNotIn({"text": "已用 23 分钟"}, cands)
        self.assertTrue(_matches_local(screen, {"id": "switch_widget"}))
        self.assertFalse(_matches_local(screen, {"text": "不存在"}))

    def test_simple_actions_need_no_device(self):
        kit = Toolkit.__new__(Toolkit)
        self.assertEqual(({"type": "back"}, None), kit.resolve_action("back"))
        self.assertEqual(({"type": "tap", "x": 1, "y": 2}, None), kit.resolve_action("tap 1 2"))
        self.assertEqual(({"type": "launchApp", "package": "a.b"}, None), kit.resolve_action("launch a.b"))
        act, _ = kit.resolve_action('setProgress {"id": "slider"} 80%')
        self.assertEqual({"type": "setProgress", "target": {"id": "slider"}, "ratio": 0.8}, act)
        with self.assertRaises(ApexError):
            kit.resolve_action("jump @1")


class McpTest(unittest.TestCase):

    def run_server(self, messages, kit=None):
        stdin = io.StringIO("\n".join(json.dumps(m) for m in messages) + "\n")
        stdout = io.StringIO()
        mcp.serve(lambda: kit, stdin=stdin, stdout=stdout)
        return [json.loads(l) for l in stdout.getvalue().splitlines()]

    def test_handshake_and_tool_list(self):
        out = self.run_server([
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "nope"},
        ])
        self.assertEqual(3, len(out))
        self.assertEqual("2025-06-18", out[0]["result"]["protocolVersion"])
        self.assertEqual("apex", out[0]["result"]["serverInfo"]["name"])
        names = [t["name"] for t in out[1]["result"]["tools"]]
        self.assertIn("observe", names)
        self.assertIn("act", names)
        self.assertEqual(-32601, out[2]["error"]["code"])

    def test_tool_call_returns_text_and_image(self):
        class Kit:
            def observe(self, **kwargs):
                return Output("页面 P1", image=b"\x89PNG")

            def validate(self, flow):
                raise ApexError("PARSE_ERROR", "bad", "flow.states")

            def act(self, action, **kwargs):
                raise AssertionError("缺少必填参数时不应该调用")

        out = self.run_server([
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "observe", "arguments": {}}},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "validate",
                                                                         "arguments": {"flow": {}}}},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "act", "arguments": {}}},
        ], kit=Kit())
        content = out[0]["result"]["content"]
        self.assertEqual("页面 P1", content[0]["text"])
        self.assertEqual("image/png", content[1]["mimeType"])
        self.assertTrue(out[1]["result"]["isError"])
        self.assertIn("flow.states", out[1]["result"]["content"][0]["text"])
        self.assertTrue(out[2]["result"]["isError"])


if __name__ == "__main__":
    unittest.main()


class ProvisionTest(unittest.TestCase):
    """开启服务时只能碰这个服务自己的开关。"""

    @staticmethod
    def page(title, rows):
        children = [{"h": 1, "cls": "android.widget.TextView", "b": [160, 120, 600, 220], "text": title}]
        h = 2
        for i, (label, has_switch) in enumerate(rows):
            row = {"h": h, "cls": "android.widget.LinearLayout", "b": [0, 400 + i * 200, 1080, 580 + i * 200],
                   "f": ["clickable"], "ch": [{"h": h + 1, "cls": "android.widget.TextView",
                                               "b": [48, 420 + i * 200, 600, 480 + i * 200], "text": label}]}
            if has_switch:
                row["ch"].append({"h": h + 2, "cls": "android.widget.Switch", "b": [900, 440 + i * 200, 1000,
                                                                                    520 + i * 200],
                                  "f": ["checkable"]})
            children.append(row)
            h += 3
        return Screen({"format": 1, "seq": 1, "pkg": "com.android.settings", "w": 1080, "h": 2400,
                       "windows": [{"id": 0, "root": {"h": 0, "cls": "android.widget.FrameLayout",
                                                      "b": [0, 0, 1080, 2400], "ch": children}}]})

    def test_list_page_switches_of_other_rows_are_never_picked(self):
        from apexlib.provision import _service_switch
        screen = self.page("无障碍", [("按电源键结束通话", True), ("锁屏快捷启用", True), ("Apex Demo 自动化", False)])
        self.assertIsNone(_service_switch(screen, ["Apex Demo 自动化", "Apex"]))

    def test_detail_page_or_own_row_switch_is_picked(self):
        from apexlib.provision import _service_switch
        detail = self.page("Apex Demo 自动化", [("使用 Apex Demo 自动化", True)])
        self.assertIsNotNone(_service_switch(detail, ["Apex Demo 自动化"]))
        row = self.page("无障碍", [("锁屏快捷启用", True), ("Apex Demo 自动化", True)])
        picked = _service_switch(row, ["Apex Demo 自动化"])
        self.assertEqual(7, picked.h, "选中的是 Apex 那一行的开关，不是锁屏快捷启用（4）")


class AdbModeTest(unittest.TestCase):

    XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.packageinstaller"
 content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false"
 focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,1200][1080,2400]">
<node index="0" text="继续安装" resource-id="com.android.packageinstaller:id/ok" class="android.widget.Button"
 package="com.android.packageinstaller" content-desc="" checkable="false" checked="false" clickable="true"
 enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false"
 selected="false" bounds="[540,2200][1032,2340]" />
<node index="1" text="" resource-id="" class="android.widget.EditText" package="com.android.packageinstaller"
 content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="true"
 scrollable="false" long-clickable="false" password="true" selected="false" bounds="[48,1800][1032,1900]" />
</node></hierarchy>"""

    def test_uiautomator_xml_becomes_an_apex_snapshot(self):
        from apexlib import adbmode
        from apexlib.provision import _button
        from apexlib.screen import render_outline
        screen = Screen(adbmode.xml_to_snapshot(self.XML, (1080, 2400)))
        self.assertEqual("com.android.packageinstaller", screen.pkg)
        self.assertEqual(3, len(screen.nodes))
        self.assertIn('@1    按钮 "继续安装"  (786,2270)', render_outline(screen))
        self.assertIn("password", screen.node("@2").f)
        self.assertIn("editable", screen.node("@2").f)
        self.assertEqual(1, _button(screen, ["继续安装", "安装"]).h)
        self.assertIsNone(_button(screen, ["允许"]))


class OcclusionAndListTest(unittest.TestCase):

    def test_toolbar_drawn_later_covers_tab_buttons(self):
        from apexlib.screen import occluded, render_outline
        root = {"h": 0, "cls": "android.widget.FrameLayout", "b": [0, 0, 1080, 2400], "ch": [
            {"h": 1, "cls": "android.widget.LinearLayout", "b": [0, 2200, 1080, 2400], "ch": [
                {"h": 2, "cls": "android.widget.TextView", "b": [0, 2200, 540, 2400], "text": "世界时钟",
                 "f": ["clickable"]}]},
            {"h": 3, "cls": "android.widget.LinearLayout", "id": "p:id/navigation_delete", "b": [0, 2150, 1080, 2400],
             "f": ["clickable"], "ch": [{"h": 4, "cls": "android.widget.TextView", "b": [400, 2200, 700, 2400],
                                         "text": "删除"}]}]}
        screen = Screen({"format": 1, "seq": 1, "pkg": "p", "w": 1080, "h": 2400,
                         "windows": [{"id": 0, "root": root}]})
        self.assertEqual(3, occluded(screen)[2].h)
        self.assertIn("⚠可能被 @3 navigation_delete 盖住", render_outline(screen))
        self.assertNotIn(3, occluded(screen), "遮挡物自己不算被遮挡")

    def test_list_items_need_similar_siblings(self):
        from apexlib.tools import _in_list
        def item(h, y):
            return {"h": h, "cls": "android.widget.LinearLayout", "id": "p:id/card", "b": [0, y, 1080, y + 200],
                    "ch": [{"h": h + 1, "cls": "android.widget.TextView", "id": "p:id/time", "b": [0, y, 400, y + 100],
                            "text": "07:00"}]}
        root = {"h": 0, "cls": "android.widget.FrameLayout", "b": [0, 0, 1080, 2400], "ch": [
            {"h": 1, "cls": "androidx.recyclerview.widget.RecyclerView", "b": [0, 0, 1080, 2000], "f": ["scrollable"],
             "ch": [item(2, 0), item(4, 200)]},
            {"h": 6, "cls": "android.widget.FrameLayout", "b": [0, 0, 1080, 2400], "f": ["scrollable"], "ch": [
                {"h": 7, "cls": "android.widget.ImageView", "b": [900, 2100, 1040, 2240], "desc": "添加",
                 "f": ["clickable"]}]}]}
        screen = Screen({"format": 1, "seq": 1, "pkg": "p", "w": 1080, "h": 2400, "windows": [{"id": 0, "root": root}]})
        self.assertTrue(_in_list(screen.node("@3")))
        self.assertFalse(_in_list(screen.node("@7")), "只有一个孩子的可滚动容器（ViewPager 的一页）不算列表")
