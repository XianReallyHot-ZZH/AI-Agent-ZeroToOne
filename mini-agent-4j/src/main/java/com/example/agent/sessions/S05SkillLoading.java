package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.skills.SkillLoader;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.Console;
import com.example.agent.util.PathSandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S05：技能加载 —— 按需加载领域知识，不撑爆系统提示词。
 * <p>
 * 两层注入：
 * - Layer 1（廉价）：技能名称 + 短描述注入 system prompt（~100 tokens/skill）
 * - Layer 2（按需）：模型调用 load_skill 工具时，完整技能正文通过 tool_result 返回
 * <p>
 * 关键洞察："别把所有东西塞进系统提示词。按需加载。"
 * <p>
 * 对应 Python 原版：s05_skill_loading.py
 */
public class S05SkillLoading {

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);

        // 技能目录默认在项目根目录的 skills/ 下
        Path skillsDir = workDir.resolve("skills");
        SkillLoader skillLoader = new SkillLoader(skillsDir);

        // Layer 1：技能描述注入 system prompt
        String systemPrompt = "You are a coding agent at " + workDir + ".\n"
                + "Use load_skill to access specialized knowledge before tackling unfamiliar topics.\n\n"
                + "Skills available:\n" + skillLoader.getDescriptions();

        // ---- 工具定义 ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
                        List.of("path")),
                AgentLoop.defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("path", "content")),
                AgentLoop.defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                AgentLoop.defineTool("load_skill", "Load specialized knowledge by name.",
                        Map.of("name", Map.of("type", "string", "description", "Skill name to load")),
                        List.of("name"))
        );

        // ---- 工具分发器 ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        // Layer 2：load_skill 返回完整技能内容
        dispatcher.register("load_skill", input -> skillLoader.getContent((String) input.get("name")));

        // ---- Agent 循环 ----
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(Console.cyan("s05 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;
            paramsBuilder.addUserMessage(query);
            try {
                agent.agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }
}
