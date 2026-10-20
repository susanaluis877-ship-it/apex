package com.github.susanaluis877.apex.demo;

import static com.github.susanaluis877.apex.action.Actions.back;
import static com.github.susanaluis877.apex.action.Actions.click;
import static com.github.susanaluis877.apex.match.Match.allOf;
import static com.github.susanaluis877.apex.match.Match.has;
import static com.github.susanaluis877.apex.match.Match.pkg;

import android.content.Context;

import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.FlowParseException;
import com.github.susanaluis877.apex.flow.FlowParser;
import com.github.susanaluis877.apex.match.By;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Demo 的“领取奖励”流程，分别用 Java DSL 和 JSON（assets/demo_flow.json）定义，两者完全等价。
 *
 * <pre>
 * home ──click(Start)──▶ task ──click(Claim)──▶ done (success)
 *   └──────────────────▶ sold_out ──back──▶ (failure)
 * 拦截器 close_ad：任何时候出现 AD 弹窗就点 Close
 * </pre>
 */
final class DemoFlows {

    private static final String PKG = "com.github.susanaluis877.apex.demo";

    private DemoFlows() {
    }

    static Flow dsl() {
        return new Flow.Builder("demo.claim")
                .start("home").timeoutMs(60_000).maxSteps(20)
                .defaults(d -> d.timeoutMs(10_000).retryIntervalMs(3_000).maxRetries(2))
                .state("home", s -> s
                        .match(allOf(pkg(PKG), has(By.id("demo_start"))))
                        .action(click(By.id("demo_start")))
                        .next("task", "sold_out"))
                .state("task", s -> s
                        .match(has(By.id("demo_claim").enabled(true)))
                        .action(click(By.id("demo_claim")))
                        .next("done").timeoutMs(15_000))
                .state("done", s -> s.match(has(By.textContains("Reward claimed"))).endSuccess())
                .state("sold_out", s -> s.match(has(By.text("Sold out"))).action(back()).endFailure())
                .interceptor("close_ad", i -> i
                        .match(allOf(has(By.text("AD")), has(By.id("demo_ad_close").clickable(true))))
                        .action(click(By.id("demo_ad_close")))
                        .maxTimes(3))
                .build();
    }

    static Flow json(Context context) throws IOException, FlowParseException {
        try (InputStream in = context.getAssets().open("demo_flow.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return FlowParser.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
