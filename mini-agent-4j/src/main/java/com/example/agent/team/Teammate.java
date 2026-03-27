package com.example.agent.team;

/**
 * Teammate 封装：持久化命名 Agent 的标识信息。
 * <p>
 * 在 Java 中用 record 表示，对应 Python config.json 中的 member 条目。
 *
 * @param name   Agent 名称（如 "alice"、"bob"）
 * @param role   角色描述（如 "coder"、"reviewer"）
 * @param status 状态：working / idle / shutdown
 */
public record Teammate(String name, String role, String status) {}
