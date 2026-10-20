package com.github.susanaluis877.apex;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 保证模型、匹配、解析和引擎代码不直接依赖 Android API，从而能在 JVM 上完整测试。
 *
 * <p>单测开启了 {@code isReturnDefaultValues}，误用的 Android API 不会在测试里报错，所以要单独检查。
 */
public class PurityTest {

    private static final String ROOT = "src/main/java/com/github/susanaluis877/apex/";
    private static final String[] PURE_PACKAGES = {"flow", "node", "match", "action", "internal/engine"};

    @Test
    public void purePackagesDoNotImportAndroid() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (String pkg : PURE_PACKAGES) {
            File dir = new File(ROOT + pkg);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
            assertTrue("missing package dir " + dir.getAbsolutePath(), files != null && files.length > 0);
            for (File file : files) {
                checked++;
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if (line.startsWith("import android.") || line.startsWith("import static android.")) {
                        violations.add(pkg + "/" + file.getName() + ": " + line);
                    }
                }
            }
        }
        assertTrue("no files checked", checked > 0);
        assertTrue("Android imports in pure packages:\n" + String.join("\n", violations), violations.isEmpty());
    }
}
