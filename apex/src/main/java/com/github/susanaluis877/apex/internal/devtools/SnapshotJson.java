package com.github.susanaluis877.apex.internal.devtools;

import com.github.susanaluis877.apex.node.Bounds;
import com.github.susanaluis877.apex.node.Range;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;
import com.github.susanaluis877.apex.node.UiWindow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 快照和 JSON 互转。快照会被宿主机保存成页面样本，再传回设备做 {@code check}，所以格式带版本号。
 *
 * <p>格式（{@code format: 1}）：
 * <pre>
 * {"format": 1, "seq": 5, "pkg": "com.foo", "activity": "com.foo.Main", "w": 1080, "h": 2400, "partial": false,
 *  "windows": [{"id": 3, "title": "Foo", "layer": 2, "active": true, "root": NODE}]}
 * NODE = {"h": 0, "cls": "android.widget.Button", "id": "com.foo:id/ok", "text": "OK", "desc": "...",
 *         "hint": "...", "state": "...", "pkg": "com.foo", "b": [l, t, r, b],
 *         "range": {"type": "int", "min": 0, "max": 100, "cur": 45},
 *         "f": ["clickable", "checked", ...], "ch": [NODE, ...]}
 * </pre>
 * 值为 null 的字段省略；{@code pkg} 和父节点相同时省略；{@code f} 只列出为真的标记，
 * 但可用和可见默认为真，所以写成 {@code disabled}、{@code invisible}。
 * 读取时只用 {@code opt} 加 {@code instanceof}，不依赖 Android 和 Maven 版 org.json 的差异。
 */
public final class SnapshotJson {

    public static final int FORMAT = 1;

    private static final String[] FLAG_NAMES = {"clickable", "longClickable", "checkable", "checked", "selected",
            "scrollable", "editable", "focused", "scrollForward", "scrollBackward", "setProgress",
            "disabled", "invisible"};

    private SnapshotJson() {
    }

    public static JSONObject write(ScreenSnapshot screen) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("format", FORMAT);
        o.put("seq", screen.getSeq());
        o.putOpt("pkg", screen.getPackageName());
        o.putOpt("activity", screen.getActivity());
        o.put("w", screen.getScreenWidth());
        o.put("h", screen.getScreenHeight());
        o.put("partial", screen.isPartial());
        JSONArray windows = new JSONArray();
        for (UiWindow window : screen.getWindows()) {
            JSONObject w = new JSONObject();
            w.put("id", window.getId());
            w.putOpt("title", window.getTitle());
            w.put("layer", window.getLayer());
            w.put("active", window.isActive());
            w.put("root", writeNode(window.getRoot(), null));
            windows.put(w);
        }
        o.put("windows", windows);
        return o;
    }

    private static JSONObject writeNode(UiNode node, String parentPkg) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("h", node.getHandle());
        o.putOpt("cls", node.getClassName());
        o.putOpt("id", node.getViewId());
        o.putOpt("text", node.getText());
        o.putOpt("desc", node.getDesc());
        o.putOpt("hint", node.getHint());
        o.putOpt("state", node.getStateDescription());
        if (node.getPackageName() != null && !node.getPackageName().equals(parentPkg)) {
            o.put("pkg", node.getPackageName());
        }
        Bounds b = node.getBounds();
        o.put("b", new JSONArray().put(b.getLeft()).put(b.getTop()).put(b.getRight()).put(b.getBottom()));
        Range range = node.getRange();
        if (range != null) {
            o.put("range", new JSONObject()
                    .put("type", range.getType().name().toLowerCase(Locale.ROOT))
                    .put("min", (double) range.getMin())
                    .put("max", (double) range.getMax())
                    .put("cur", (double) range.getCurrent()));
        }
        JSONArray flags = new JSONArray();
        boolean[] values = flagValues(node);
        for (int i = 0; i < FLAG_NAMES.length; i++) {
            if (values[i]) {
                flags.put(FLAG_NAMES[i]);
            }
        }
        if (flags.length() > 0) {
            o.put("f", flags);
        }
        if (!node.getChildren().isEmpty()) {
            JSONArray children = new JSONArray();
            for (UiNode child : node.getChildren()) {
                children.put(writeNode(child, node.getPackageName()));
            }
            o.put("ch", children);
        }
        return o;
    }

    private static boolean[] flagValues(UiNode n) {
        return new boolean[]{n.isClickable(), n.isLongClickable(), n.isCheckable(), n.isChecked(), n.isSelected(),
                n.isScrollable(), n.isEditable(), n.isFocused(), n.canScrollForward(), n.canScrollBackward(),
                n.canSetProgress(), !n.isEnabled(), !n.isVisible()};
    }

    /**
     * @throws DevtoolsException 格式不对时，消息里带出错位置
     */
    public static ScreenSnapshot read(Object json) throws DevtoolsException {
        JSONObject o = object(json, "");
        Object format = o.opt("format");
        if (format != null && !(format instanceof Number && ((Number) format).intValue() == FORMAT)) {
            throw bad("format", "unsupported snapshot format " + format + " (supported: " + FORMAT + ")");
        }
        JSONArray windows = array(o.opt("windows"), "windows");
        List<UiWindow> list = new ArrayList<>();
        int[] nextHandle = {0};
        for (int i = 0; i < windows.length(); i++) {
            String path = "windows[" + i + "]";
            JSONObject w = object(windows.opt(i), path);
            UiNode root = readNode(object(w.opt("root"), path + ".root"), null, path + ".root", nextHandle).build();
            list.add(new UiWindow(intOr(w, "id", -1), string(w, "title"), intOr(w, "layer", 0),
                    Boolean.TRUE.equals(w.opt("active")), root));
        }
        return new ScreenSnapshot(longOr(o, "seq", 0), string(o, "pkg"), string(o, "activity"), list,
                intOr(o, "w", 0), intOr(o, "h", 0), Boolean.TRUE.equals(o.opt("partial")));
    }

    private static UiNode.Builder readNode(JSONObject o, String parentPkg, String path, int[] nextHandle)
            throws DevtoolsException {
        // 没写 h 的节点按先序编号，和设备上的编号方式一致
        int handle = nextHandle[0]++;
        Object h = o.opt("h");
        if (h instanceof Number) {
            handle = ((Number) h).intValue();
        }
        String pkg = string(o, "pkg");
        if (pkg == null) {
            pkg = parentPkg;
        }
        UiNode.Builder b = new UiNode.Builder().handle(handle).className(string(o, "cls")).viewId(string(o, "id"))
                .text(string(o, "text")).desc(string(o, "desc")).hint(string(o, "hint"))
                .stateDescription(string(o, "state")).packageName(pkg);
        Object bounds = o.opt("b");
        if (bounds != null) {
            JSONArray a = array(bounds, path + ".b");
            if (a.length() != 4) {
                throw bad(path + ".b", "expected [left, top, right, bottom]");
            }
            int[] v = new int[4];
            for (int i = 0; i < 4; i++) {
                Object item = a.opt(i);
                if (!(item instanceof Number)) {
                    throw bad(path + ".b[" + i + "]", "expected number");
                }
                v[i] = ((Number) item).intValue();
            }
            b.bounds(v[0], v[1], v[2], v[3]);
        }
        Object range = o.opt("range");
        if (range != null) {
            JSONObject r = object(range, path + ".range");
            Range.Type type;
            try {
                type = Range.Type.valueOf(String.valueOf(r.opt("type")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw bad(path + ".range.type", "expected int, float or percent");
            }
            b.range(new Range(type, floatOf(r, "min", path), floatOf(r, "max", path), floatOf(r, "cur", path)));
        }
        Object flags = o.opt("f");
        if (flags != null) {
            JSONArray a = array(flags, path + ".f");
            for (int i = 0; i < a.length(); i++) {
                Object name = a.opt(i);
                if (!applyFlag(b, name)) {
                    throw bad(path + ".f[" + i + "]", "unknown flag " + name);
                }
            }
        }
        Object children = o.opt("ch");
        if (children != null) {
            JSONArray a = array(children, path + ".ch");
            for (int i = 0; i < a.length(); i++) {
                String p = path + ".ch[" + i + "]";
                b.child(readNode(object(a.opt(i), p), pkg, p, nextHandle));
            }
        }
        return b;
    }

    private static boolean applyFlag(UiNode.Builder b, Object name) {
        if (!(name instanceof String)) {
            return false;
        }
        switch ((String) name) {
            case "clickable":
                b.clickable(true);
                return true;
            case "longClickable":
                b.longClickable(true);
                return true;
            case "checkable":
                b.checkable(true);
                return true;
            case "checked":
                b.checked(true);
                return true;
            case "selected":
                b.selected(true);
                return true;
            case "scrollable":
                b.scrollable(true);
                return true;
            case "editable":
                b.editable(true);
                return true;
            case "focused":
                b.focused(true);
                return true;
            case "scrollForward":
                b.canScrollForward(true);
                return true;
            case "scrollBackward":
                b.canScrollBackward(true);
                return true;
            case "setProgress":
                b.canSetProgress(true);
                return true;
            case "disabled":
                b.enabled(false);
                return true;
            case "invisible":
                b.visible(false);
                return true;
            default:
                return false;
        }
    }

    private static JSONObject object(Object v, String path) throws DevtoolsException {
        if (!(v instanceof JSONObject)) {
            throw bad(path, "expected object");
        }
        return (JSONObject) v;
    }

    private static JSONArray array(Object v, String path) throws DevtoolsException {
        if (!(v instanceof JSONArray)) {
            throw bad(path, "expected array");
        }
        return (JSONArray) v;
    }

    private static String string(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    private static int intOr(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    private static long longOr(JSONObject o, String key, long fallback) {
        Object v = o.opt(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    private static float floatOf(JSONObject o, String key, String path) throws DevtoolsException {
        Object v = o.opt(key);
        if (!(v instanceof Number)) {
            throw bad(path + ".range." + key, "expected number");
        }
        return ((Number) v).floatValue();
    }

    private static DevtoolsException bad(String path, String message) {
        return new DevtoolsException(DevtoolsException.BAD_REQUEST,
                "snapshot" + (path.isEmpty() ? "" : "." + path) + ": " + message);
    }
}
