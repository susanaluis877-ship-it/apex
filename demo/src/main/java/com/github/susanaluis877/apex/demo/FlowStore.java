package com.github.susanaluis877.apex.demo;

import android.content.Context;

import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.FlowParseException;
import com.github.susanaluis877.apex.flow.FlowParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 可运行的流程：打包在 {@code assets/flows/} 里的内置流程，加上从电脑推送到 {@code files/flows/} 的流程
 * （{@code apex.py push flow.json}，同名时推送的优先）。
 */
final class FlowStore {

    static final String DIR = "flows";

    /** 一个流程。解析失败时 {@link #flow} 为 null，{@link #error} 说明原因。 */
    static final class Entry {
        final String name;
        /** 来源：代码、内置、推送。 */
        final String source;
        final Flow flow;
        final String error;

        Entry(String name, String source, Flow flow, String error) {
            this.name = name;
            this.source = source;
            this.flow = flow;
            this.error = error;
        }
    }

    private FlowStore() {
    }

    static List<Entry> list(Context context) {
        List<Entry> entries = new ArrayList<>();
        File[] files = new File(context.getFilesDir(), DIR).listFiles((dir, name) -> name.endsWith(".json"));
        List<String> pushedNames = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - 5);
                pushedNames.add(name);
                try (InputStream in = new FileInputStream(file)) {
                    entries.add(parse(name, "推送", in));
                } catch (IOException e) {
                    entries.add(new Entry(name, "推送", null, e.toString()));
                }
            }
        }
        try {
            String[] assets = context.getAssets().list(DIR);
            if (assets != null) {
                Arrays.sort(assets);
                for (String asset : assets) {
                    String name = asset.endsWith(".json") ? asset.substring(0, asset.length() - 5) : null;
                    if (name == null || pushedNames.contains(name)) {
                        continue;
                    }
                    try (InputStream in = context.getAssets().open(DIR + "/" + asset)) {
                        entries.add(parse(name, "内置", in));
                    }
                }
            }
        } catch (IOException e) {
            entries.add(new Entry("assets/" + DIR, "内置", null, e.toString()));
        }
        return entries;
    }

    static Entry find(Context context, String name) {
        for (Entry entry : list(context)) {
            if (entry.name.equals(name) || entry.flow != null && entry.flow.getId().equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private static Entry parse(String name, String source, InputStream in) throws IOException {
        try {
            return new Entry(name, source, FlowParser.parse(read(in)), null);
        } catch (FlowParseException e) {
            return new Entry(name, source, null, e.getMessage());
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
