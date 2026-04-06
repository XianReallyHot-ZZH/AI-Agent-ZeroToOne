package com.example.agent.team;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.Console;
import com.example.agent.util.PathSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 团队管理器：持久化命名 Agent + Virtual Thread 并发运行。
 * <p>
 * 与 Subagent（s04）的关键区别：
 * - Subagent: spawn → execute → return summary → destroyed
 * - Teammate: spawn → work → idle → work → ... → shutdown
 * <p>
 * 团队配置持久化在 .team/config.json。
 * <p>
 * 对应 Python 原版：s09_agent_teams.py 中的 TeammateManager 类。
 */
public class TeamManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path teamDir;
    private final Path configPath;
    private final MessageBus bus;
    private final AnthropicClient client;
    private final String model;
    private final Path workDir;
    private final PathSandbox sandbox;

    /** 团队配置：{team_name, members: [{name, role, status}]} */
    private Map<String, Object> config;

    /** 活跃线程引用 */
    private final Map<String, Thread> threads = new HashMap<>();

    public TeamManager(Path teamDir, MessageBus bus,
                       AnthropicClient client, String model,
                       Path workDir, PathSandbox sandbox) {
        this.teamDir = teamDir;
        this.configPath = teamDir.resolve("config.json");
        this.bus = bus;
        this.client = client;
        this.model = model;
        this.workDir = workDir;
        this.sandbox = sandbox;

        try {
            Files.createDirectories(teamDir);
        } catch (IOException ignored) {}

        this.config = loadConfig();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        if (Files.exists(configPath)) {
            try {
                return MAPPER.readValue(Files.readString(configPath), Map.class);
            } catch (IOException ignored) {}
        }
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("team_name", "default");
        cfg.put("members", new ArrayList<Map<String, Object>>());
        return cfg;
    }

    private synchronized void saveConfig() {
        try {
            Files.writeString(configPath,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        } catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private synchronized Map<String, Object> findMember(String name) {
        var members = (List<Map<String, Object>>) config.get("members");
        return members.stream()
                .filter(m -> name.equals(m.get("name")))
                .findFirst().orElse(null);
    }

    /**
     * 生成一个持久化 teammate（Virtual Thread 运行独立 Agent 循环）。
     * <p>
     * 对应 Python: TeammateManager.spawn(name, role, prompt)
     */
    @SuppressWarnings("unchecked")
    public synchronized String spawn(String name, String role, String prompt) {
        var member = findMember(name);
        if (member != null) {
            String status = (String) member.get("status");
            if (!"idle".equals(status) && !"shutdown".equals(status)) {
                return "Error: '" + name + "' is currently " + status;
            }
            member.put("status", "working");
            member.put("role", role);
        } else {
            member = new LinkedHashMap<>(Map.of("name", name, "role", role, "status", "working"));
            ((List<Map<String, Object>>) config.get("members")).add(member);
        }
        saveConfig();

        Thread thread = Thread.ofVirtual()
                .name("agent-" + name)
                .start(() -> teammateLoop(name, role, prompt));
        threads.put(name, thread);

        return "Spawned '" + name + "' (role: " + role + ")";
    }

    /**
     * Teammate 工作循环：独立的 system prompt、消息列表、工具集。
     * <p>
     * 对应 Python: TeammateManager._teammate_loop(name, role, prompt)
     */
    private void teammateLoop(String name, String role, String prompt) {
        String sysPrompt = "You are '" + name + "', role: " + role + ", at " + workDir + ". "
                + "Use send_message to communicate. Complete your task.";

        // 独立上下文
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000)
                .system(sysPrompt);

        // Teammate 工具集
        List<Tool> tools = teammateTools();
        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }
        paramsBuilder.addUserMessage(prompt);

        // Teammate 工具分发器
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("send_message", input ->
                bus.send(name, (String) input.get("to"), (String) input.get("content"),
                        (String) input.getOrDefault("msg_type", "message"), null));
        dispatcher.register("read_inbox", input -> {
            try {
                return MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(bus.readInbox(name));
            } catch (IOException e) {
                return "[]";
            }
        });

        // 工作循环（最多 50 轮）
        for (int round = 0; round < 50; round++) {
            // 检查收件箱
            var inbox = bus.readInbox(name);
            for (var msg : inbox) {
                try {
                    paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg));
                } catch (IOException ignored) {}
            }

            try {
                Message response = client.messages().create(paramsBuilder.build());
                paramsBuilder.addMessage(response);

                if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                    break;
                }

                // 执行工具
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.isToolUse()) {
                        ToolUseBlock toolUse = block.asToolUse();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> input = (Map<String, Object>) AgentLoop.jsonValueToObject(toolUse._input());
                        String output = dispatcher.dispatch(toolUse.name(), input != null ? input : Map.of());
                        System.out.println(Console.dim("  [" + name + "] "
                                + toolUse.name() + ": "
                                + output.substring(0, Math.min(120, output.length()))));
                        results.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(toolUse.id())
                                        .content(output)
                                        .build()));
                    }
                }
                paramsBuilder.addUserMessageOfBlockParams(results);
            } catch (Exception e) {
                System.out.println(Console.toolError("[" + name + "]", e.getMessage()));
                break;
            }
        }

        // 更新状态（若已被标记为 shutdown 则保持不变）
        var member = findMember(name);
        if (member != null && !"shutdown".equals(member.get("status"))) {
            setStatus(name, "idle");
        }
    }

    private synchronized void setStatus(String name, String status) {
        var member = findMember(name);
        if (member != null) {
            member.put("status", status);
            saveConfig();
        }
    }

    /**
     * Teammate 可用的工具列表。
     */
    private List<Tool> teammateTools() {
        return List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string")), List.of("path")),
                AgentLoop.defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                        List.of("path", "content")),
                AgentLoop.defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                AgentLoop.defineTool("send_message", "Send message to a teammate.",
                        Map.of("to", Map.of("type", "string"),
                                "content", Map.of("type", "string"),
                                "msg_type", Map.of("type", "string",
                                        "enum", List.of("message", "broadcast", "shutdown_request",
                                                "shutdown_response", "plan_approval_response"))),
                        List.of("to", "content")),
                AgentLoop.defineTool("read_inbox", "Read and drain your inbox.",
                        Map.of(), null)
        );
    }

    /**
     * 列出所有 teammate 状态。
     */
    @SuppressWarnings("unchecked")
    public String listAll() {
        var members = (List<Map<String, Object>>) config.get("members");
        if (members.isEmpty()) return "No teammates.";

        var lines = new ArrayList<String>();
        lines.add("Team: " + config.get("team_name"));
        for (var m : members) {
            lines.add("  " + m.get("name") + " (" + m.get("role") + "): " + m.get("status"));
        }
        return String.join("\n", lines);
    }

    /**
     * 获取所有 teammate 名称。
     */
    @SuppressWarnings("unchecked")
    public List<String> memberNames() {
        var members = (List<Map<String, Object>>) config.get("members");
        return members.stream().map(m -> (String) m.get("name")).toList();
    }
}
