package com.example.agent;

import com.example.agent.sessions.*;

import java.util.Map;

/**
 * 统一启动入口：通过 session 名称分发到对应的 main 方法。
 * <p>
 * 用法：
 * <pre>
 * java -jar mini-agent-4j.jar s03          # 运行 S03TodoWrite
 * java -jar mini-agent-4j.jar S03TodoWrite  # 也支持全类名
 * java -jar mini-agent-4j.jar               # 默认运行 S01AgentLoop
 * </pre>
 */
public class Launcher {

    private static final Map<String, Class<?>> SESSIONS = Map.ofEntries(
            Map.entry("s01", S01AgentLoop.class),
            Map.entry("s02", S02ToolUse.class),
            Map.entry("s03", S03TodoWrite.class),
            Map.entry("s04", S04Subagent.class),
            Map.entry("s05", S05SkillLoading.class),
            Map.entry("s06", S06ContextCompact.class),
            Map.entry("s07", S07TaskSystem.class),
            Map.entry("s08", S08BackgroundTasks.class),
            Map.entry("s09", S09AgentTeams.class),
            Map.entry("s10", S10TeamProtocols.class),
            Map.entry("s11", S11AutonomousAgents.class),
            Map.entry("s12", S12WorktreeIsolation.class),
            Map.entry("full", SFullAgent.class)
    );

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0].toLowerCase() : "s01";

        Class<?> cls = SESSIONS.get(name);
        if (cls == null) {
            System.err.println("Unknown session: " + name);
            System.err.println("Available: " + SESSIONS.keySet().stream().sorted().toList());
            System.exit(1);
        }

        try {
            cls.getDeclaredMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            cause.printStackTrace();
            System.exit(1);
        }
    }
}
