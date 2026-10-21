"""adb 和调试桥连接。每个请求一个连接、一行 JSON 请求，响应一行或多行 JSON。"""

import json
import os
import re
import shutil
import socket
import subprocess

from .errors import ApexError

SOCKET_PREFIX = "apex_"


def find_adb():
    adb = shutil.which("adb")
    if adb:
        return adb
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.expanduser("~/Library/Android/sdk"), os.path.expanduser("~/Android/Sdk")):
        if root:
            path = os.path.join(root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
            if os.path.exists(path):
                return path
    raise ApexError("ADB", "adb not found; install Android platform-tools or set ANDROID_HOME")


class Adb:
    def __init__(self, serial=None):
        self.path = find_adb()
        self.serial = serial or os.environ.get("ANDROID_SERIAL")

    def devices(self):
        out = self._run([self.path, "devices"], timeout=15).decode("utf-8", "replace")
        result = []
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                result.append((parts[0], parts[1]))
        return result

    def resolve(self):
        """确定要用的设备：显式指定 > 只连了一台。"""
        if self.serial:
            return self.serial
        ready = [s for s, state in self.devices() if state == "device"]
        if len(ready) == 1:
            self.serial = ready[0]
            return self.serial
        if not ready:
            raise ApexError("ADB", "no device connected (adb devices is empty)")
        raise ApexError("ADB", "several devices connected: %s; pass -s SERIAL or set ANDROID_SERIAL"
                        % ", ".join(ready))

    def run(self, *args, input=None, timeout=30):
        return self._run([self.path, "-s", self.resolve()] + list(args), input=input, timeout=timeout)

    def shell(self, command, input=None, timeout=30):
        return self.run("shell", command, input=input, timeout=timeout).decode("utf-8", "replace")

    @staticmethod
    def _run(cmd, input=None, timeout=30):
        try:
            p = subprocess.run(cmd, input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise ApexError("ADB", "timed out: %s" % " ".join(cmd[1:]))
        if p.returncode != 0:
            err = (p.stderr or p.stdout).decode("utf-8", "replace").strip()
            raise ApexError("ADB", "%s failed: %s" % (" ".join(cmd[1:]), err))
        return p.stdout


class Bridge:
    def __init__(self, adb, package=None):
        self.adb = adb
        self.package = package or os.environ.get("APEX_PACKAGE")
        self._port = None

    def sockets(self):
        """设备上所有 Apex 调试桥对应的包名。"""
        out = self.adb.shell("cat /proc/net/unix")
        names = set()
        for line in out.splitlines():
            m = re.search(r"@" + SOCKET_PREFIX + r"([\w.]+)\s*$", line)
            if m:
                names.add(m.group(1))
        return sorted(names)

    def resolve_package(self):
        if self.package:
            return self.package
        found = self.sockets()
        if len(found) == 1:
            self.package = found[0]
            return self.package
        if not found:
            raise ApexError("NO_BRIDGE", "no Apex devtools bridge on the device. Start the host app (a debuggable "
                                         "build that calls Apex.init) and make sure ApexConfig.setDevtools is not false")
        raise ApexError("NO_BRIDGE", "several apps run the bridge: %s; pass --pkg" % ", ".join(found))

    def port(self):
        if self._port:
            return self._port
        name = SOCKET_PREFIX + self.resolve_package()
        serial = self.adb.resolve()
        listing = self.adb._run([self.adb.path, "forward", "--list"], timeout=15).decode("utf-8", "replace")
        for line in listing.splitlines():
            parts = line.split()
            if len(parts) == 3 and parts[0] == serial and parts[2] == "localabstract:" + name:
                self._port = int(parts[1].split(":")[1])
                return self._port
        out = self.adb.run("forward", "tcp:0", "localabstract:" + name).decode().strip()
        self._port = int(out.splitlines()[-1])
        return self._port

    def _connect(self, timeout):
        try:
            return socket.create_connection(("127.0.0.1", self.port()), timeout=timeout)
        except OSError as e:
            raise ApexError("NO_BRIDGE", "cannot connect to the bridge: %s" % e)

    def stream(self, payload, timeout=120):
        """发送一个请求，逐行产出响应。"""
        conn = self._connect(timeout)
        try:
            conn.sendall((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
            buf = b""
            while True:
                try:
                    chunk = conn.recv(65536)
                except socket.timeout:
                    raise ApexError("TIMEOUT", "no response from the bridge within %ss" % timeout)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if line.strip():
                        yield json.loads(line.decode("utf-8"))
            if buf.strip():
                yield json.loads(buf.decode("utf-8"))
        finally:
            conn.close()

    def request(self, payload, timeout=120):
        """发送请求并返回唯一的一行响应；失败时抛 ApexError。"""
        lines = list(self.stream(payload, timeout))
        if not lines:
            raise ApexError("NO_BRIDGE", "the bridge closed the connection without answering (peer rejected? "
                                         "only adb/shell, root and the app itself may connect)")
        return check_ok(lines[-1])


def check_ok(response):
    if response.get("ok"):
        return response
    err = response.get("error") or {}
    raise ApexError(err.get("code", "ERROR"), err.get("message", json.dumps(response)), err.get("path"))
