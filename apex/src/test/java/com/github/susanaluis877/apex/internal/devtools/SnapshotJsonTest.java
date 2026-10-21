package com.github.susanaluis877.apex.internal.devtools;

import static com.github.susanaluis877.apex.testutil.Trees.PKG;
import static com.github.susanaluis877.apex.testutil.Trees.button;
import static com.github.susanaluis877.apex.testutil.Trees.frame;
import static com.github.susanaluis877.apex.testutil.Trees.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.susanaluis877.apex.internal.ScreenDumper;
import com.github.susanaluis877.apex.internal.engine.Fingerprint;
import com.github.susanaluis877.apex.node.Range;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;
import com.github.susanaluis877.apex.node.UiWindow;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class SnapshotJsonTest {

    @Test
    public void roundTripKeepsEverything() throws Exception {
        UiNode.Builder slider = new UiNode.Builder().handle(3).className("android.widget.SeekBar").packageName(PKG)
                .viewId(PKG + ":id/brightness").desc("亮度").bounds(10, 20, 1000, 80)
                .range(new Range(Range.Type.INT, 0, 100, 45)).canSetProgress(true).focused(true);
        UiNode.Builder toggle = new UiNode.Builder().handle(4).className("android.widget.Switch").packageName(PKG)
                .checkable(true).checked(true).stateDescription("已开启").clickable(true).longClickable(true)
                .selected(true);
        UiNode.Builder input = new UiNode.Builder().handle(5).className("android.widget.EditText").packageName(PKG)
                .editable(true).hint("搜索设置").enabled(false).text("\"quoted\"\nline");
        UiNode.Builder list = new UiNode.Builder().handle(2).className("androidx.recyclerview.widget.RecyclerView")
                .packageName(PKG).scrollable(true).canScrollForward(true).canScrollBackward(true)
                .bounds(0, 100, 1080, 2000).child(slider).child(toggle).child(input);
        UiNode.Builder other = new UiNode.Builder().handle(6).className("android.view.View")
                .packageName("com.other").visible(false);
        UiNode root = frame(list).handle(1).build();
        UiNode dialog = frame(other).handle(0).build();
        ScreenSnapshot s = new ScreenSnapshot(7, PKG, PKG + ".Settings",
                Arrays.asList(new UiWindow(9, "Dialog", 3, true, dialog), new UiWindow(8, null, 1, false, root)),
                1080, 2400, true);

        JSONObject json = SnapshotJson.write(s);
        ScreenSnapshot back = SnapshotJson.read(new JSONObject(json.toString()));

        assertEquals(ScreenDumper.dump(s), ScreenDumper.dump(back));
        assertEquals(Fingerprint.of(s), Fingerprint.of(back));
        assertEquals(7, back.getSeq());
        assertTrue(back.isPartial());
        assertEquals("Dialog", back.getWindows().get(0).getTitle());
        assertEquals(9, back.getWindows().get(0).getId());
        assertFalse(back.getWindows().get(1).isActive());
        assertEquals(s.nodes().size(), back.nodes().size());
        for (int i = 0; i < s.nodes().size(); i++) {
            UiNode a = s.nodes().get(i);
            UiNode b = back.nodes().get(i);
            assertEquals(a.getHandle(), b.getHandle());
            assertEquals(a.getPackageName(), b.getPackageName());
            assertEquals(a.getRange(), b.getRange());
            assertEquals(a.isFocused(), b.isFocused());
            assertEquals(a.canScrollBackward(), b.canScrollBackward());
            assertEquals(a.canSetProgress(), b.canSetProgress());
            assertEquals(a.isLongClickable(), b.isLongClickable());
        }
    }

    @Test
    public void childPackageIsOmittedWhenSameAsParent() throws Exception {
        ScreenSnapshot s = com.github.susanaluis877.apex.testutil.Trees.screen(frame(button("ok", "OK")));
        JSONObject root = SnapshotJson.write(s).getJSONArray("windows").getJSONObject(0).getJSONObject("root");
        assertEquals(PKG, root.getString("pkg"));
        assertFalse(root.getJSONArray("ch").getJSONObject(0).has("pkg"));
    }

    @Test
    public void missingHandlesArePreorderNumbers() throws Exception {
        ScreenSnapshot s = SnapshotJson.read(new JSONObject("{\"windows\": [{\"root\": {\"cls\": \"A\", \"ch\":"
                + " [{\"cls\": \"B\", \"ch\": [{\"cls\": \"C\"}]}, {\"cls\": \"D\"}]}}]}"));
        assertEquals(4, s.nodes().size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i, s.nodes().get(i).getHandle());
        }
        assertNull(s.getPackageName());
        assertTrue(s.nodes().get(0).isVisible());
        assertTrue(s.nodes().get(0).isEnabled());
    }

    @Test
    public void reportsWhereTheInputIsWrong() {
        assertBad("{\"windows\": [{\"root\": {\"b\": [1, 2]}}]}", "snapshot.windows[0].root.b");
        assertBad("{\"windows\": [{\"root\": {\"ch\": [{\"f\": [\"clicky\"]}]}}]}", "snapshot.windows[0].root.ch[0].f[0]");
        assertBad("{\"format\": 2, \"windows\": []}", "snapshot.format");
        assertBad("{}", "snapshot.windows");
    }

    @Test
    public void textDumpIncludesNewFields() {
        UiNode.Builder slider = new UiNode.Builder().className("android.widget.SeekBar").range(
                new Range(Range.Type.FLOAT, 0, 1, 0.5f)).canSetProgress(true);
        UiNode.Builder list = new UiNode.Builder().className("ListView").scrollable(true).canScrollForward(true)
                .child(slider).child(text("x").stateDescription("on").hint("h"));
        String dump = ScreenDumper.dump(com.github.susanaluis877.apex.testutil.Trees.screen(list));
        assertTrue(dump, dump.contains("ListView [0,0][0,0] scrollable(fwd)"));
        assertTrue(dump, dump.contains("SeekBar [0,0][0,0] range=0.50/0..1 set-progress"));
        assertTrue(dump, dump.contains("TextView text=\"x\" hint=\"h\" state=\"on\""));
    }

    private static void assertBad(String json, String pathPrefix) {
        try {
            SnapshotJson.read(new JSONObject(json));
            fail("expected failure for " + json);
        } catch (DevtoolsException e) {
            assertEquals(DevtoolsException.BAD_REQUEST, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith(pathPrefix + ":"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
