#!/usr/bin/env python3
"""Apex devtools：通过 adb 连接宿主 App 里的 Apex 调试桥，像人一样看页面、做动作、记录探索过程，并校验和运行流程。

    python3 apex.py <命令> [参数]      命令行，见 -h
    python3 apex.py mcp                MCP 服务（stdio），给 Claude Code 等客户端用

只依赖 Python 3.8+ 标准库和 adb。调试桥只在 debuggable 包（debug 构建）里启动，监听抽象 socket apex_<包名>。
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from apexlib.errors import ApexError  # noqa: E402
from apexlib.tools import Toolkit  # noqa: E402

SHOT_CHOICES = ["annotated", "plain", "none"]


def build_parser():
    p = argparse.ArgumentParser(prog="apex.py", description="Apex devtools：查看页面、做动作、记录探索、校验和运行流程")
    p.add_argument("-s", "--serial", help="设备序列号，默认 ANDROID_SERIAL 或唯一连接的设备")
    p.add_argument("--pkg", help="宿主包名，默认 APEX_PACKAGE 或设备上唯一的调试桥")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("mcp", help="以 MCP 服务方式运行（stdio）")
    sub.add_parser("doctor", help="检查 adb、调试桥、无障碍服务和协议版本")

    for name in ("observe", "screen"):
        s = sub.add_parser(name, help="看当前页面（screen 是 observe 的别名）")
        s.add_argument("--full", action="store_true", help="完整节点树")
        s.add_argument("--grep", help="只列出文本/描述/id 包含该字符串的节点")
        s.add_argument("--save", metavar="PATH", help="把快照另存为页面样本（JSON）")
        s.add_argument("--wait", type=int, default=0, metavar="MS", help="先等这么久再等界面安静")
        s.add_argument("--shot", choices=SHOT_CHOICES, default="none", help="截图（保存到会话目录）")

    s = sub.add_parser("act", help="执行一个动作，显示结果、变化、预期核对和新页面")
    s.add_argument("action", nargs="+", help="click @12 | longClick @12 | setText @12 文本 | scroll @12 forward | "
                                             "setProgress @12 80%% 或 setProgress @12 4481（绝对值） | tap X Y | back | home | "
                                             "launch PKG | sleep MS | 动作 JSON")
    s.add_argument("--intent", help="为什么做这一步")
    s.add_argument("--expect", help='预期：一段应出现的文字，或 JSON，键有 text/gone/page/package/changed/has/hasNot，'
                                    '也可以直接写选择器，例如 {"id": "switch", "checked": true}')
    s.add_argument("--wait", type=int, default=0, metavar="MS", help="动作后先等这么久再等界面安静")
    s.add_argument("--shot", choices=SHOT_CHOICES, default="none")

    s = sub.add_parser("deploy", help="编译宿主、覆盖安装、等服务重连（改了 Java DSL 流程后用）")
    s.add_argument("--module", help="Android 应用模块，默认自动找唯一的一个")
    s.add_argument("--project", help="Gradle 工程目录，默认从当前目录往上找 gradlew")
    s.add_argument("--variant", default="debug")

    s = sub.add_parser("setup", help="准备环境：首次安装宿主（处理安装确认）并开启 Apex 无障碍服务；调试桥没就绪时用 adb 操作")
    s.add_argument("--module", help="先编译并安装这个模块")
    s.add_argument("--apk", help="或者直接安装这个 APK")
    s.add_argument("--project")

    s = sub.add_parser("suggest", help="为节点生成并在设备上验证候选选择器")
    s.add_argument("ref", help="节点引用，例如 @12")
    s.add_argument("--for", dest="purpose", choices=["click", "exact"], default="click")

    s = sub.add_parser("find", help="在最近的快照上验证选择器")
    s.add_argument("selectors", nargs="+", help="选择器 JSON")

    s = sub.add_parser("find-text", help="在当前页面找文字；--scroll 时滚动去找")
    s.add_argument("text")
    s.add_argument("--scroll", action="store_true")
    s.add_argument("--direction", default="forward")
    s.add_argument("--max", type=int, default=10, dest="max_swipes")
    s.add_argument("--shot", choices=SHOT_CHOICES, default="none")

    s = sub.add_parser("launch", help="打开 App（不会强制停止）")
    s.add_argument("package")
    s.add_argument("--reset", action="store_true", help="清空任务栈后打开，回到首页")
    s.add_argument("--shot", choices=SHOT_CHOICES, default="none")

    s = sub.add_parser("plan", help="设置目标和里程碑，或标记完成")
    s.add_argument("--goal")
    s.add_argument("--milestones", help='JSON 数组：[{"title": "...", "evidence": "..."}]')
    s.add_argument("--done", type=int, nargs="+", help="完成的里程碑序号，可以写多个")
    s.add_argument("--note")
    s.add_argument("--new", action="store_true", help="开始新的探索会话")

    sub.add_parser("history", help="本次探索的完整记录")

    s = sub.add_parser("features", help="某个页面独有的特征和推荐 matcher")
    s.add_argument("page", help="页面编号（P3）或标题")

    s = sub.add_parser("validate", help="校验流程（Java DSL 引用 com.foo.Flows#method，或 JSON）")
    s.add_argument("flow")

    s = sub.add_parser("check", help="检查流程与页面样本的匹配关系（默认用本次会话的页面）")
    s.add_argument("flow")
    s.add_argument("--samples", nargs="*", metavar="DIR", help="样本目录或文件")
    s.add_argument("--label", nargs="*", default=[], metavar="P3=state", help="页面编号对应的状态 id")
    s.add_argument("--live", action="store_true", help="检查当前页面")
    s.add_argument("--wait", type=int, default=0, metavar="MS")

    s = sub.add_parser("run", help="在设备上运行流程，实时输出轨迹")
    s.add_argument("flow")
    s.add_argument("--timeout", type=int, default=600, help="最长等待秒数")
    s.add_argument("--quiet", action="store_true")
    s.add_argument("--save-trace", metavar="PATH")

    s = sub.add_parser("export", help="把流程（Java DSL 引用或 JSON）写成 JSON，并确认解析回来与原流程相等")
    s.add_argument("flow", help="com.foo.Flows#method，或流程文件")
    s.add_argument("-o", "--out", help="写到这个文件")

    s = sub.add_parser("push", help="把流程推送到宿主 App 的 files/flows/")
    s.add_argument("flow")
    s.add_argument("--name")

    s = sub.add_parser("raw", help="发送原始协议请求")
    s.add_argument("request")
    s.add_argument("--timeout", type=int, default=120)
    return p


def dispatch(kit, args):
    c = args.command
    if c == "doctor":
        return kit.doctor()
    if c in ("observe", "screen"):
        return kit.observe(screenshot=args.shot, full=args.full, grep=args.grep, wait_ms=args.wait, save=args.save)
    if c == "act":
        expect = None
        if args.expect:
            expect = json.loads(args.expect) if args.expect.strip().startswith("{") else {"text": args.expect}
        return kit.act(args.action, intent=args.intent, expect=expect, wait_ms=args.wait, screenshot=args.shot)
    if c == "deploy":
        return kit.deploy(module=args.module, project=args.project, variant=args.variant)
    if c == "setup":
        return kit.setup(module=args.module, apk=args.apk, project=args.project, package=args.pkg)
    if c == "suggest":
        return kit.suggest(args.ref, args.purpose)
    if c == "find":
        return kit.find(args.selectors)
    if c == "find-text":
        return kit.find_text(args.text, scroll=args.scroll, direction=args.direction, max_swipes=args.max_swipes,
                             screenshot=args.shot)
    if c == "launch":
        return kit.launch(args.package, reset=args.reset, screenshot=args.shot)
    if c == "plan":
        milestones = json.loads(args.milestones) if args.milestones else None
        return kit.plan(goal=args.goal, milestones=milestones, done=args.done, note=args.note, new=args.new)
    if c == "history":
        return kit.history()
    if c == "features":
        return kit.features(args.page)
    if c == "validate":
        return kit.validate(args.flow)
    if c == "check":
        labels = dict(item.split("=", 1) for item in args.label)
        return kit.check(args.flow, samples=args.samples, labels=labels, live=args.live, wait_ms=args.wait)
    if c == "run":
        return kit.run(args.flow, timeout=args.timeout, quiet=args.quiet, save_trace=args.save_trace,
                       stream=lambda line: print(line, flush=True))
    if c == "export":
        return kit.export(args.flow, args.out)
    if c == "push":
        return kit.push(args.flow, args.name)
    if c == "raw":
        return kit.raw(args.request, args.timeout)
    raise ApexError("BAD_REQUEST", "unknown command %s" % c)


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.command == "mcp":
        from apexlib import mcp
        mcp.serve(lambda: Toolkit(args.serial, args.pkg))
        return 0
    try:
        out = dispatch(Toolkit(args.serial, args.pkg), args)
    except ApexError as e:
        print("错误 %s" % e, file=sys.stderr)
        if e.path:
            print("位置: %s" % e.path, file=sys.stderr)
        return 2
    print(out.text)
    return 0 if out.ok else 1


if __name__ == "__main__":
    sys.exit(main())
