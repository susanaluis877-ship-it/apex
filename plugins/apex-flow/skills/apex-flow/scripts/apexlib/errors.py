"""错误类型。"""


class ApexError(Exception):
    """请求失败。code 与设备端 DevtoolsException 的错误码一致，另有 ADB、NO_BRIDGE 等本地错误。"""

    def __init__(self, code, message, path=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.path = path

    def __str__(self):
        return "%s: %s" % (self.code, self.message)
