package com.example.agent.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能加载器：两层注入，避免撑爆系统提示词。
 * <p>
 * Layer 1（廉价）：技能名称和简短描述注入 system prompt（~100 tokens/skill）
 * Layer 2（按需）：完整技能内容通过 tool_result 返回
 * <p>
 * 技能目录结构：
 * <pre>
 * skills/
 *   pdf/
 *     SKILL.md          ← YAML frontmatter (name, description) + body
 *   code-review/
 *     SKILL.md
 * </pre>
 * <p>
 * 对应 Python 原版：s05_skill_loading.py 中的 SkillLoader 类。
 */
public class SkillLoader {

    /** YAML frontmatter 正则：--- 开头，--- 结尾，后跟正文（兼容 CRLF / LF） */
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n(.*)", Pattern.DOTALL);

    /** 技能名 -> {meta: Map, body: String} */
    private final Map<String, Map<String, Object>> skills = new LinkedHashMap<>();

    /**
     * 扫描技能目录，加载所有 SKILL.md 文件。
     *
     * @param skillsDir 技能目录路径（如 project/skills）
     */
    public SkillLoader(Path skillsDir) {
        if (!Files.exists(skillsDir)) {
            return;
        }
        try (var stream = Files.walk(skillsDir)) {
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(this::loadSkillFile);
        } catch (IOException e) {
            // 技能目录扫描失败时静默忽略
        }
    }

    /**
     * 加载单个 SKILL.md 文件。
     */
    private void loadSkillFile(Path file) {
        try {
            String text = Files.readString(file);
            var parsed = parseFrontmatter(text);

            @SuppressWarnings("unchecked")
            Map<String, String> meta = (Map<String, String>) parsed.get("meta");
            String body = (String) parsed.get("body");

            // 技能名优先取 frontmatter 中的 name，否则取父目录名
            String name = meta.getOrDefault("name", file.getParent().getFileName().toString());
            skills.put(name, Map.of("meta", meta, "body", body, "path", file.toString()));
        } catch (IOException e) {
            // 单个技能文件加载失败时静默忽略
        }
    }

    /**
     * 解析 YAML frontmatter。
     * <p>
     * 支持：简单 key: value、块标量 key: | (保留换行)、key: > (折叠换行)、缩进续行。
     * 不引入完整 YAML 解析器，覆盖 SKILL.md 中常见的写法。
     */
    private Map<String, Object> parseFrontmatter(String text) {
        Matcher match = FRONTMATTER_PATTERN.matcher(text);
        if (!match.matches()) {
            return Map.of("meta", Map.of(), "body", text);
        }

        String frontmatter = match.group(1);
        String[] lines = frontmatter.split("\\r?\\n");

        Map<String, String> meta = new LinkedHashMap<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            // 缩进行属于上一个 key 的续行，跳过（已在下面消费）
            if (line.startsWith(" ") || line.startsWith("\t")) {
                i++;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                i++;
                continue;
            }

            String key = line.substring(0, colon).strip();
            String rest = line.substring(colon + 1).strip();

            // 块标量 key: | 或 key: >
            if (rest.equals("|") || rest.equals(">")) {
                boolean literal = rest.equals("|");
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < lines.length) {
                    String cont = lines[i];
                    // 块标量结束条件：非空行且没有缩进
                    if (!cont.isEmpty() && !cont.startsWith(" ") && !cont.startsWith("\t")) {
                        break;
                    }
                    // 去掉公共缩进（至少一个空格/tab）
                    if (!cont.isEmpty()) {
                        cont = cont.replaceFirst("^[ \\t]{1,}", "");
                    }
                    sb.append(cont).append(literal ? "\n" : " ");
                    i++;
                }
                String value = literal ? sb.toString().stripTrailing() : sb.toString().strip();
                meta.put(key, value);
                continue;
            }

            // 简单 key: value（可能跨行续行，如 Keywords: 那一行）
            // 检查后续缩进行的续行
            StringBuilder valueSb = new StringBuilder(rest);
            i++;
            while (i < lines.length) {
                String cont = lines[i];
                if (cont.isEmpty() || (!cont.startsWith(" ") && !cont.startsWith("\t"))) {
                    break;
                }
                valueSb.append(" ").append(cont.strip());
                i++;
            }
            meta.put(key, valueSb.toString().strip());
        }

        return Map.of("meta", meta, "body", match.group(2).strip());
    }

    /**
     * Layer 1：获取所有技能的简短描述（注入 system prompt）。
     * <p>
     * 对应 Python: get_descriptions()
     */
    @SuppressWarnings("unchecked")
    public String getDescriptions() {
        if (skills.isEmpty()) {
            return "(no skills available)";
        }

        var lines = new java.util.ArrayList<String>();
        for (var entry : skills.entrySet()) {
            Map<String, String> meta = (Map<String, String>) entry.getValue().get("meta");
            String desc = meta.getOrDefault("description", "No description");
            String tags = meta.getOrDefault("tags", "");
            String line = "  - " + entry.getKey() + ": " + desc;
            if (!tags.isEmpty()) {
                line += " [" + tags + "]";
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    /**
     * Layer 2：获取完整技能内容（通过 tool_result 返回）。
     * <p>
     * 对应 Python: get_content(name)
     */
    public String getContent(String name) {
        var skill = skills.get(name);
        if (skill == null) {
            return "Error: Unknown skill '" + name + "'. Available: "
                    + String.join(", ", skills.keySet());
        }
        return "<skill name=\"" + name + "\">\n" + skill.get("body") + "\n</skill>";
    }
}
