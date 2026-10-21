"""MCP 服务（stdio，按行分隔的 JSON-RPC 2.0），只用标准库。

和 CLI 共用 Toolkit。用 MCP 的主要原因是工具结果可以同时带截图和文本：一次 observe/act 就能拿到带编号框的截图和页面结构。
"""

import base64
import json
import sys
import traceback

from .errors import ApexError
from .tools import Toolkit

SERVER_VERSION = "0.5.0"
KNOWN_VERSIONS = ("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

INSTRUCTIONS = (
    "Apex devtools：通过宿主 App 里的 Apex 调试桥查看和操作真机屏幕，用来探索目标 App 并编写 Apex 流程。"
    "先 doctor，再用 plan 定目标和里程碑，然后 observe → 思考 → act（必带 intent，最好带 expect）循环。"
    "文本一律从节点树复制，截图只用来理解布局；@引用只在当前页面有效，写进流程的是 act 返回的选择器。")

SCREENSHOT = {"type": "string", "enum": ["annotated", "plain", "none"],
              "description": "annotated：可操作元素画编号框（编号即 @引用）；plain：原图；none：不截图"}
FLOW = {"description": "流程：宿主代码里 Java DSL 流程的引用（\"com.foo.Flows#addAlarm\"，改代码后要先 deploy），"
                       "或流程 JSON 对象、JSON 文件路径",
        "anyOf": [{"type": "object"}, {"type": "string"}]}

TOOLS = [
    {"name": "doctor", "description": "检查 adb、调试桥、无障碍服务和协议版本。开始前先调用一次；有 ✗ 时按提示让人处理。",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "setup",
     "description": "准备环境：需要时编译并安装宿主（首次安装会处理手机上的安装确认），然后开启 Apex 无障碍服务"
                    "（系统允许时用 settings，否则在设置界面里点）。调试桥没就绪时全程用 adb 操作。doctor 报告服务没开启或"
                    "宿主没装时用它。",
     "inputSchema": {"type": "object", "properties": {
         "module": {"type": "string", "description": "先编译并安装这个 Android 应用模块"},
         "apk": {"type": "string", "description": "或者直接安装这个 APK"},
         "project": {"type": "string"}, "package": {"type": "string", "description": "宿主包名"}}}},
    {"name": "deploy",
     "description": "编译宿主、覆盖安装、等进程重启和无障碍服务重连，一般几秒到几十秒。改了宿主代码里的 Java DSL 流程后用它，"
                    "再用 run / check / validate 引用 \"com.foo.Flows#method\"。编译失败时返回编译错误。",
     "inputSchema": {"type": "object", "properties": {
         "module": {"type": "string"}, "project": {"type": "string"}, "variant": {"type": "string"}}}},
    {"name": "observe",
     "description": "看当前页面：页面编号（P1、P2…，同一页面再次到达编号不变）、走过的路径、计划进度、页面结构"
                    "（可操作元素带 @引用、类型、标签、状态、中心坐标），默认附带编号框截图。"
                    "页面在加载时用 wait_ms 多等一会儿再看。",
     "inputSchema": {"type": "object", "properties": {
         "screenshot": SCREENSHOT,
         "grep": {"type": "string", "description": "只列出文本/描述/id 包含它的节点（含不可见节点），并指出点击会落在哪个祖先"},
         "full": {"type": "boolean", "description": "完整节点树（很长，只在页面结构不够用时看）"},
         "wait_ms": {"type": "integer", "description": "先等这么久再等界面安静"}}}},
    {"name": "act",
     "description": "执行一个动作，返回结果、和上一屏的变化、预期是否达成、警告（没变化、离开应用、弹窗、打转、长时间没进展）"
                    "以及新页面。action 可以是简写：click @12、longClick @12、setText @12 文本、scroll @12 forward、"
                    "setProgress @12 80%、tap X Y、back、home、launch 包名、sleep 毫秒；也可以是 Apex 动作 JSON，例如 "
                    "{\"type\":\"scrollTo\",\"target\":{\"text\":\"显示与亮度\"}}。@引用会自动换成在设备上验证过的最稳定选择器，"
                    "结果里会显示用了哪个——写流程时直接用它。付款、发送、删除、下单这类不可逆操作先问人。",
     "inputSchema": {"type": "object", "required": ["action", "intent"], "properties": {
         "action": {"description": "简写字符串或 Apex 动作 JSON", "anyOf": [{"type": "string"}, {"type": "object"}]},
         "intent": {"type": "string", "description": "为什么做这一步，例如「进入显示设置」"},
         "expect": {"type": "object", "description": "做完应该看到什么，用来自动核对",
                    "properties": {"text": {"description": "应出现的文字（包含即可），可为数组"},
                                   "gone": {"description": "应消失的文字，可为数组"},
                                   "page": {"type": "string", "description": "应到达的页面编号或标题"},
                                   "package": {"type": "string"},
                                   "changed": {"type": "boolean"}}},
         "wait_ms": {"type": "integer", "description": "动作后先等这么久再等界面安静（页面要加载时用）"},
         "screenshot": SCREENSHOT}}},
    {"name": "suggest", "description": "为 @引用 的节点生成候选选择器，并在设备上验证是否唯一命中。",
     "inputSchema": {"type": "object", "required": ["ref"], "properties": {
         "ref": {"type": "string", "description": "例如 @12，来自最近一次 observe/act"},
         "purpose": {"type": "string", "enum": ["click", "exact"],
                     "description": "click：命中目标或它名下的子节点都算；exact：必须命中节点本身（has 条件用这个）"}}}},
    {"name": "find", "description": "在最近一次快照上验证选择器，返回命中数量和不匹配原因。",
     "inputSchema": {"type": "object", "required": ["selectors"], "properties": {
         "selectors": {"type": "array", "items": {"type": "object"}}}}},
    {"name": "find_text",
     "description": "在当前页面找包含某段文字的节点；scroll=true 且没找到时，滚动可滚动区域去找（记为一步）。"
                    "找不到入口时先试这个，再考虑搜索框、菜单或其他标签页。",
     "inputSchema": {"type": "object", "required": ["text"], "properties": {
         "text": {"type": "string"}, "scroll": {"type": "boolean"},
         "direction": {"type": "string", "enum": ["forward", "backward", "up", "down", "left", "right"]},
         "max_swipes": {"type": "integer"},
         "container": {"type": "object", "description": "要滚动的容器选择器，默认第一个可滚动区域"},
         "screenshot": SCREENSHOT}}},
    {"name": "launch", "description": "打开 App 首页；reset=true 时清掉它的任务栈回到首页（不会强制停止，不影响无障碍服务）。",
     "inputSchema": {"type": "object", "required": ["package"], "properties": {
         "package": {"type": "string"}, "reset": {"type": "boolean"}, "screenshot": SCREENSHOT}}},
    {"name": "plan",
     "description": "设置或更新目标和里程碑（每个里程碑写清「到了会看到什么」），或标记某个里程碑完成。"
                    "new=true 开始新的探索会话。之后每次 observe/act 都会显示进度。",
     "inputSchema": {"type": "object", "properties": {
         "goal": {"type": "string"},
         "milestones": {"type": "array", "items": {"type": "object", "properties": {
             "title": {"type": "string"}, "evidence": {"type": "string"}}}},
         "done": {"description": "完成的里程碑序号（从 1 开始），可以是数组", "anyOf": [
             {"type": "integer"}, {"type": "array", "items": {"type": "integer"}}]},
         "note": {"type": "string"},
         "new": {"type": "boolean"}}}},
    {"name": "history", "description": "本次探索的完整记录：每一步从哪个页面、做了什么、到了哪、是否符合预期，以及到过的页面和样本位置。写流程前看一遍。",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "features",
     "description": "找出某个页面独有的特征（本次会话到过的其他页面都没有），在设备上验证后给出推荐的状态 matcher。",
     "inputSchema": {"type": "object", "required": ["page"], "properties": {
         "page": {"type": "string", "description": "页面编号（P3）或标题"}}}},
    {"name": "validate", "description": "在设备上解析并校验流程 JSON（包括自定义名字是否已在宿主注册），返回所有问题和 JSON 路径。",
     "inputSchema": {"type": "object", "required": ["flow"], "properties": {"flow": FLOW}}},
    {"name": "check",
     "description": "检查流程的每个状态和拦截器能匹配哪些页面样本，报告：样本没被自己的状态匹配、多个候选同时匹配（谁会胜出）、"
                    "拦截器误伤正常页面、状态太宽、样本没人匹配。默认用本次会话到过的所有页面；labels 把页面编号对应到状态 id。",
     "inputSchema": {"type": "object", "required": ["flow"], "properties": {
         "flow": FLOW,
         "labels": {"type": "object", "description": "例如 {\"P3\": \"display\"}：P3 是状态 display 的样本"},
         "samples": {"type": "array", "items": {"type": "string"}, "description": "样本目录或文件，代替会话页面"},
         "live": {"type": "boolean", "description": "检查当前页面"}}}},
    {"name": "run", "description": "在设备上运行流程，返回结果、路径和轨迹（失败时轨迹里有每个候选状态不匹配的原因和最后一屏）。",
     "inputSchema": {"type": "object", "required": ["flow"], "properties": {
         "flow": FLOW, "timeout_s": {"type": "integer"}}}},
    {"name": "status",
     "description": "当前或最近一次运行的状态、结果和最近的轨迹，包括在宿主 App 界面里点「运行」发起的运行。"
                    "流程运行期间 observe / act 拿不到页面，用它看进度。",
     "inputSchema": {"type": "object", "properties": {
         "limit": {"type": "integer", "description": "最多返回多少条轨迹，默认 40；-1 为全部"}}}},
    {"name": "export",
     "description": "把流程（通常是 Java DSL 写的 flowRef）写成 JSON，只在项目确实需要 JSON 时用（远程下发、放进 assets、"
                    "推到 runner）。设备上会确认解析回来和原流程相等；有内联代码时会说明在哪个状态、哪个动作，不能转。",
     "inputSchema": {"type": "object", "required": ["flow"], "properties": {
         "flow": FLOW, "out": {"type": "string", "description": "写到这个文件；不给时直接返回 JSON"}}}},
    {"name": "push", "description": "把流程推送到宿主 App 的 files/flows/，宿主的流程列表里会出现它（debug 包才行）。",
     "inputSchema": {"type": "object", "required": ["flow"], "properties": {
         "flow": FLOW, "name": {"type": "string"}}}},
]


def call_tool(kit, name, args):
    shot = args.get("screenshot")
    if name == "doctor":
        return kit.doctor()
    if name == "setup":
        return kit.setup(module=args.get("module"), apk=args.get("apk"), project=args.get("project"),
                         package=args.get("package"))
    if name == "deploy":
        return kit.deploy(module=args.get("module"), project=args.get("project"),
                          variant=args.get("variant", "debug"))
    if name == "observe":
        return kit.observe(screenshot=shot or "annotated", full=args.get("full", False), grep=args.get("grep"),
                           wait_ms=args.get("wait_ms", 0))
    if name == "act":
        return kit.act(args["action"], intent=args.get("intent"), expect=args.get("expect"),
                       wait_ms=args.get("wait_ms", 0), screenshot=shot or "annotated")
    if name == "suggest":
        return kit.suggest(args["ref"], args.get("purpose", "click"))
    if name == "find":
        return kit.find(args["selectors"])
    if name == "find_text":
        return kit.find_text(args["text"], scroll=args.get("scroll", False), direction=args.get("direction", "forward"),
                             max_swipes=args.get("max_swipes", 10), container=args.get("container"),
                             screenshot=shot or "annotated")
    if name == "launch":
        return kit.launch(args["package"], reset=args.get("reset", False), screenshot=shot or "annotated")
    if name == "plan":
        return kit.plan(goal=args.get("goal"), milestones=args.get("milestones"), done=args.get("done"),
                        note=args.get("note"), new=args.get("new", False))
    if name == "history":
        return kit.history()
    if name == "features":
        return kit.features(args["page"])
    if name == "validate":
        return kit.validate(args["flow"])
    if name == "status":
        return kit.status(limit=args.get("limit", 40))
    if name == "check":
        return kit.check(args["flow"], samples=args.get("samples"), labels=args.get("labels"),
                         live=args.get("live", False))
    if name == "run":
        return kit.run(args["flow"], timeout=args.get("timeout_s", 600))
    if name == "export":
        return kit.export(args["flow"], args.get("out"))
    if name == "push":
        return kit.push(args["flow"], args.get("name"))
    raise ApexError("BAD_REQUEST", "unknown tool %s" % name)


def serve(kit_factory, stdin=None, stdout=None):
    stdin = stdin or sys.stdin
    stdout = stdout or sys.stdout

    def send(message):
        stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
        stdout.flush()

    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            send({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
            continue
        if "id" not in msg:
            continue  # 通知，例如 notifications/initialized
        method = msg.get("method")
        params = msg.get("params") or {}
        try:
            if method == "initialize":
                version = params.get("protocolVersion")
                result = {"protocolVersion": version if version in KNOWN_VERSIONS else KNOWN_VERSIONS[2],
                          "capabilities": {"tools": {"listChanged": False}},
                          "serverInfo": {"name": "apex", "version": SERVER_VERSION},
                          "instructions": INSTRUCTIONS}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": TOOLS}
            elif method == "tools/call":
                result = tool_result(kit_factory, params.get("name"), params.get("arguments") or {})
            else:
                send({"jsonrpc": "2.0", "id": msg["id"], "error": {"code": -32601, "message": "method not found: %s"
                                                                                               % method}})
                continue
            send({"jsonrpc": "2.0", "id": msg["id"], "result": result})
        except Exception as e:  # noqa: BLE001  服务不能因为一个请求崩掉
            traceback.print_exc(file=sys.stderr)
            send({"jsonrpc": "2.0", "id": msg["id"], "error": {"code": -32603, "message": str(e)}})


def tool_result(kit_factory, name, args):
    try:
        out = call_tool(kit_factory(), name, args)
    except ApexError as e:
        text = "错误 %s" % e + ("\n位置: %s" % e.path if e.path else "")
        return {"content": [{"type": "text", "text": text}], "isError": True}
    except KeyError as e:
        return {"content": [{"type": "text", "text": "缺少参数 %s" % e}], "isError": True}
    content = [{"type": "text", "text": out.text}]
    if out.image:
        content.append({"type": "image", "data": base64.b64encode(out.image).decode("ascii"),
                        "mimeType": "image/png"})
    # 动作没成功不算工具出错，结果里已经写清楚了；只有请求本身失败才标 isError
    return {"content": content, "isError": False}


def main():
    serve(lambda: Toolkit())

