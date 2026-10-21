"""截图：用 `adb exec-out screencap` 取原始像素，缩小后在可操作元素上画编号框（与页面结构里的 @引用一致），编码成 PNG。

只用标准库：原始 RGBA 不需要解码，缩小用切片抽样，PNG 用 zlib 编码。
每个颜色通道只保留高 4 位，截图体积小一半以上，看布局足够；仍然太大时再缩小，免得超过 MCP 工具结果的大小限制。
"""

import struct
import zlib

from .errors import ApexError

# 5x7 点阵数字，每行 5 位，高位在左
DIGITS = {
    "0": (0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
    "1": (0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
    "2": (0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
    "3": (0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
    "4": (0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
    "5": (0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
    "6": (0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
    "7": (0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
    "8": (0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
    "9": (0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
}
PALETTE = [(230, 25, 75), (0, 130, 200), (60, 160, 60), (245, 130, 48), (145, 30, 180), (0, 150, 150),
           (200, 40, 160), (120, 90, 20)]
TARGET_WIDTH = 540
MAX_PNG_BYTES = 80_000
QUANTIZE = bytes(i & 0xF0 for i in range(256))


class Image:
    """RGB 图像，像素按行存放。"""

    def __init__(self, width, height, pixels):
        self.width = width
        self.height = height
        self.pixels = pixels

    def fill(self, x0, y0, x1, y1, color):
        x0, x1 = max(0, x0), min(self.width, x1)
        y0, y1 = max(0, y0), min(self.height, y1)
        if x0 >= x1 or y0 >= y1:
            return
        row = bytes(color) * (x1 - x0)
        for y in range(y0, y1):
            start = (y * self.width + x0) * 3
            self.pixels[start:start + len(row)] = row

    def rect(self, x0, y0, x1, y1, color, thickness=2):
        self.fill(x0, y0, x1, y0 + thickness, color)
        self.fill(x0, y1 - thickness, x1, y1, color)
        self.fill(x0, y0, x0 + thickness, y1, color)
        self.fill(x1 - thickness, y0, x1, y1, color)

    def label(self, x, y, text, color, scale=2):
        """在 (x, y) 画一个实心底色的数字标签。"""
        w = len(text) * 6 * scale + 2 * scale
        h = 7 * scale + 4 * scale
        x = min(max(0, x), max(0, self.width - w))
        y = min(max(0, y), max(0, self.height - h))
        self.fill(x, y, x + w, y + h, color)
        cx = x + 2 * scale
        for ch in text:
            rows = DIGITS.get(ch)
            if rows:
                for r, bits in enumerate(rows):
                    for c in range(5):
                        if bits & (0x10 >> c):
                            px = cx + c * scale
                            py = y + 2 * scale + r * scale
                            self.fill(px, py, px + scale, py + scale, (255, 255, 255))
            cx += 6 * scale

    def png(self):
        raw = bytearray()
        stride = self.width * 3
        for y in range(self.height):
            raw.append(0)
            raw += self.pixels[y * stride:(y + 1) * stride]

        def chunk(kind, data):
            body = kind + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

        header = struct.pack(">IIBBBBB", self.width, self.height, 8, 2, 0, 0, 0)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) \
            + chunk(b"IEND", b"")


def decode_raw(data, target_width=TARGET_WIDTH, step=None):
    """解析 screencap 的原始输出（宽、高、格式[、色彩空间] + RGBA），按 step 抽样缩小（默认缩到大约 target_width 宽）。
    返回 (Image, 缩小倍数)。"""
    if len(data) < 12:
        raise ApexError("SCREENSHOT", "screencap returned %d bytes" % len(data))
    width, height, fmt = struct.unpack("<III", data[:12])
    header = len(data) - width * height * 4
    if fmt != 1 or header not in (12, 16) or width == 0:
        raise ApexError("SCREENSHOT", "unsupported screencap output (%dx%d format %d, header %d)"
                        % (width, height, fmt, header))
    step = step or max(1, int(width / float(target_width) + 0.5))
    out_w, out_h = width // step, height // step
    pixels = bytearray(out_w * out_h * 3)
    row_bytes = width * 4
    for y in range(out_h):
        start = header + y * step * row_bytes
        row = data[start:start + row_bytes]
        o = y * out_w * 3
        pixels[o:o + out_w * 3:3] = row[0:out_w * step * 4:4 * step]
        pixels[o + 1:o + out_w * 3:3] = row[1:out_w * step * 4:4 * step]
        pixels[o + 2:o + out_w * 3:3] = row[2:out_w * step * 4:4 * step]
    return Image(out_w, out_h, bytearray(bytes(pixels).translate(QUANTIZE))), step


def screenshot(adb, boxes=None, max_bytes=MAX_PNG_BYTES):
    """截屏并编码成 PNG；boxes 不为 None 时画编号框。超过 max_bytes 时逐步缩小。"""
    data = adb.run("exec-out", "screencap", timeout=30)
    image, step = decode_raw(data)
    for extra in range(3):
        if extra:
            image, step = decode_raw(data, step=step + 1)
        if boxes is not None:
            annotate(image, step, boxes)
        png = image.png()
        if len(png) <= max_bytes:
            break
    return png


def annotate(image, step, boxes):
    """boxes: [(handle, [l, t, r, b]), ...]，坐标是屏幕像素。按面积从大到小画，小元素的框和标签在上层。"""
    ordered = sorted(boxes, key=lambda hb: -(hb[1][2] - hb[1][0]) * (hb[1][3] - hb[1][1]))
    for i, (handle, b) in enumerate(ordered):
        color = PALETTE[handle % len(PALETTE)]
        x0, y0, x1, y1 = (v // step for v in b)
        image.rect(x0, y0, x1, y1, color)
        image.label(x0 + 2, y0 + 2, str(handle), color)
    return image
