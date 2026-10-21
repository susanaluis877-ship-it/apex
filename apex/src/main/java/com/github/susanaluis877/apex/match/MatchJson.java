package com.github.susanaluis877.apex.match;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 把页面条件和选择器写成 JSON，格式与 {@code FlowParser} 相同。供 {@code FlowWriter} 使用。
 */
public final class MatchJson {

    private MatchJson() {
    }

    /** @throws IllegalArgumentException 条件里有内联的 {@code custom(screen -> ...)} 时 */
    public static JSONObject write(ScreenMatcher matcher) throws JSONException {
        return matcher.toJson();
    }

    public static JSONObject write(Selector selector) throws JSONException {
        return selector.toJson();
    }
}
