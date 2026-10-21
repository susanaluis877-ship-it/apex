#!/usr/bin/env python3
"""PreToolUse 钩子：拦下会破坏 Apex 运行环境的 shell 命令。

- UiAutomation 类工具（uiautomator、uiautomator2 / uiauto.dev、Appium、Maestro）连接期间会让所有无障碍服务断开。
- 强制停止会把宿主 App 的无障碍服务从已开启列表里移除，要人重新去设置里开启。
"""

import json
import re
import sys

RULES = [
    (re.compile(r"\badb\b.*\buiautomator\b|\buiautomator2\b|\buiautodev\b|\batx-agent\b|\bweditor\b"
                r"|\bmaestro\s+(test|studio|hierarchy|mcp|record)\b"),
     "UiAutomation 类工具会让 Apex 的无障碍服务断开，而且看到的节点树和 Apex 不一样。"
     "请用 apex.py observe / act（或 apex MCP 的同名工具）：Apex 没就绪（宿主没装、服务没开）时它们会自动改用 adb 模式，"
     "安装和开启服务用 apex.py setup。"),
    (re.compile(r"\bam\s+(force-stop|kill-all)\b|\bam\s+start\b[^|;&]*\s-S\b|\bam\s+stop-app\b"),
     "强制停止会让宿主 App 的无障碍服务被系统关掉，需要人重新开启。"
     "要回到 App 首页请用 apex.py launch <包名> --reset（清任务栈但不杀进程）。确实要强制停止别的 App 时，请让人手动执行。"),
]


def main():
    try:
        command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
    except ValueError:
        return 0
    for pattern, reason in RULES:
        if pattern.search(command):
            print("已拦截：" + reason, file=sys.stderr)
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
