package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.team.MessageBus;
import com.example.agent.team.TeamManager;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.Console;
import com.example.agent.util.PathSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S09：多 Agent 团队 —— 持久化命名 Agent + 消息总线通信。
 * <p>
 * 与 Subagent（s04）的关键区别：
 * <pre>
 * Subagent: spawn → execute → return → destroyed    （无状态）
 * Teammate: spawn → work → idle → work → shutdown    （有状态）
 * </pre>
 * <p>
 * 架构：
 * <pre>
 * Lead Agent (REPL)
 *   ├── spawn_teammate → alice (Virtual Thread)
 *   ├── spawn_teammate → bob   (Virtual Thread)
 *   └── MessageBus (.team/inbox/*.jsonl)
 *         ├── send_message(lead → alice)
 *         ├── send_message(alice → bob)
 *         └── broadcast(lead → all)
 * </pre>
 * <p>
 * REPL 命令：/team, /inbox
 * <p>
 * 关键洞察："Teammate 是有名字的、有记忆的、可以随时对话的长期合作者。"
 * <p>
 * 对应 Python 原版：s09_agent_teams.py
 */
public class S09AgentTeams {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        Path teamDir = workDir.resolve(".team");
        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));

        String systemPrompt = "You are a team lead at " + workDir
                + ". Spawn teammates and communicate via inboxes.";

        // ---- 工具定义 ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")),
                        List.of("path")),
                AgentLoop.defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                        List.of("path", "content")),
                AgentLoop.defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                AgentLoop.defineTool("spawn_teammate",
                        "Spawn a persistent teammate (runs in background thread).",
                        Map.of("name", Map.of("type", "string"),
                                "role", Map.of("type", "string"),
                                "prompt", Map.of("type", "string")),
                        List.of("name", "role", "prompt")),
                AgentLoop.defineTool("list_teammates", "List all teammates and their status.",
                        Map.of(), null),
                AgentLoop.defineTool("send_message", "Send a message to a teammate's inbox.",
                        Map.of("to", Map.of("type", "string"),
                                "content", Map.of("type", "string"),
                                "msg_type", Map.of("type", "string",
                                        "enum", List.of("message", "broadcast", "shutdown_request",
                                                "shutdown_response", "plan_approval_response"))),
                        List.of("to", "content")),
                AgentLoop.defineTool("read_inbox", "Read and drain the lead's inbox.",
                        Map.of(), null),
                AgentLoop.defineTool("broadcast", "Broadcast a message to all teammates.",
                        Map.of("content", Map.of("type", "string")),
                        List.of("content"))
        );

        // ---- AgentLoop（用于获取 client 和 model） ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);

        // ---- TeamManager 需要 client 和 model ----
        TeamManager teamMgr = new TeamManager(teamDir, bus,
                agent.getClient(), agent.getModel(), workDir, sandbox);

        // ---- 工具分发器 ----
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("spawn_teammate", input ->
                teamMgr.spawn((String) input.get("name"),
                        (String) input.get("role"),
                        (String) input.get("prompt")));
        dispatcher.register("list_teammates", input -> teamMgr.listAll());
        dispatcher.register("send_message", input ->
                bus.send("lead", (String) input.get("to"),
                        (String) input.get("content"),
                        (String) input.getOrDefault("msg_type", "message"), null));
        dispatcher.register("read_inbox", input -> {
            try {
                var messages = bus.readInbox("lead");
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            } catch (Exception e) {
                return "[]";
            }
        });
        dispatcher.register("broadcast", input ->
                bus.broadcast("lead", (String) input.get("content"), teamMgr.memberNames()));

        // ---- Lead inbox 自动注入：每轮 LLM 调用前读取 lead 的收件箱 ----
        agent.setPreLLMHook(() -> {
            var inbox = bus.readInbox("lead");
            if (inbox.isEmpty()) return null;
            var blocks = new ArrayList<ContentBlockParam>();
            try {
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text("<inbox>" + MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(inbox) + "</inbox>")
                        .build()));
            } catch (Exception ignored) {}
            return blocks;
        });

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(Console.cyan("s09 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            // REPL 斜杠命令
            if ("/team".equals(query)) {
                System.out.println(teamMgr.listAll());
                continue;
            }
            if ("/inbox".equals(query)) {
                try {
                    var msgs = bus.readInbox("lead");
                    System.out.println(msgs.isEmpty() ? "Inbox empty."
                            : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msgs));
                } catch (Exception e) {
                    System.out.println("Inbox empty.");
                }
                continue;
            }

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
