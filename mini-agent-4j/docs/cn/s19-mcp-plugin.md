# s19：MCP 插件系统

`... s17 > s18 > s19 ...`

> *"外部工具应该进入同一个工具管线，而不是形成一个完全独立的世界。"* —— MCP 工具和原生工具共享权限检查和标准化输出。

## 课程目标

理解如何通过 MCP（Model Context Protocol）协议让外部进程暴露工具给 Agent 使用。MCP 工具与原生工具统一管理，经过同一个权限门控。

## 问题

Agent 的原生工具（bash、read、write、edit）覆盖了基本的文件操作，但团队可能有自定义的工具需求：数据库查询、API 测试、部署脚本。如果每种工具都硬编码进 Agent，代码会无限膨胀。需要一个插件机制让外部进程提供工具。

## 方案

最小路径：

```
1. 启动 MCP 服务器进程（子进程，stdio 通信）
2. 向它查询有哪些工具（tools/list）
3. 加上 mcp__ 前缀并注册这些工具
4. 将匹配的调用路由到对应服务器
```

架构：

```
+-------------------+     +------------------+
| 原生工具           |     | MCP 服务器        |
| bash, read_file,  |     | (外部进程)        |
| write_file, etc.  |     |                  |
+--------+----------+     +--------+---------+
         |                         |
         v                         v
+--------+-------------------------+--------+
|           统一工具池（buildToolPool）       |
| 原生工具优先，MCP 工具加 mcp__ 前缀         |
+-------------------+----------------------+
                    |
                    v
+-------------------+----------------------+
|         CapabilityPermissionGate          |
| 原生和 MCP 工具都经过同一个权限门           |
+-------------------+----------------------+
                    |
                    v
+-------------------+----------------------+
|         normalizeToolResult               |
| 用 source/risk/status 元数据包装输出       |
+-------------------------------------------+
```

## 核心概念

### MCPClient —— 最小 stdio 客户端

使用 JSON-RPC 2.0 通过子进程的 stdin/stdout 通信：

```java
static class MCPClient {
    // 通信协议：
    // 1. initialize（protocolVersion "2024-11-05"）
    // 2. notifications/initialized（无 id）
    // 3. tools/list → 获取工具列表
    // 4. tools/call → 执行工具

    boolean connect();              // 启动子进程 + initialize
    List<Map<String, Object>> listTools();   // 获取工具列表
    String callTool(String name, Map args);  // 执行工具
    void disconnect();              // 关闭子进程
}
```

JSON-RPC 消息格式：

```json
// 请求
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
// 响应
{"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}
```

### MCPToolRouter —— 工具路由器

将 `mcp__{server}__{tool}` 前缀的工具调用路由到正确的 MCP 服务器：

```java
static class MCPToolRouter {
    boolean isMcpTool(String toolName) {
        return toolName.startsWith("mcp__");
    }

    String call(String toolName, Map<String, Object> arguments) {
        String[] parts = toolName.split("__", 3);
        String serverName = parts[1];
        String actualTool = parts[2];
        return clients.get(serverName).callTool(actualTool, arguments);
    }
}
```

### CapabilityPermissionGate —— 共享权限门

原生工具和 MCP 工具都经过同一个权限控制面：

```java
static class CapabilityPermissionGate {
    Map<String, Object> normalize(String toolName, Map<String, Object> toolInput) {
        // 解析 mcp__{server}__{tool} 格式
        // 根据工具名判断风险等级：read / write / high
    }

    Map<String, Object> check(String toolName, Map<String, Object> toolInput) {
        // read → 自动允许
        // auto 模式下 write → 自动允许
        // high → 需要确认
        // 其他 write → 需要确认
    }
}
```

风险等级：

| 等级 | 工具前缀                          | 默认行为 |
|------|-----------------------------------|----------|
| read | read, list, get, show, search... | 自动允许 |
| write | write, edit, bash（非危险命令）  | 需确认   |
| high | delete, remove, sudo, rm -rf...  | 需确认   |

### PluginLoader —— 插件发现器

从 `.claude-plugin/plugin.json` 清单文件发现 MCP 服务器配置：

```java
static class PluginLoader {
    List<String> scan();                           // 扫描 .claude-plugin/ 目录
    Map<String, Map<String, Object>> getMcpServers(); // 提取 MCP 服务器配置
}
```

### 工具结果标准化

所有工具的输出都经过标准化，确保一致性：

```java
private static String normalizeToolResult(String toolName, String output, ...) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("source", intent.get("source"));  // "native" 或 "mcp"
    payload.put("server", intent.get("server"));  // MCP 服务器名或 null
    payload.put("tool", intent.get("tool"));
    payload.put("risk", intent.get("risk"));
    payload.put("status", ...);                    // "ok" 或 "error"
    payload.put("preview", ...);                   // 前 500 字符
    return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
}
```

## 关键代码片段

工具池构建 —— 原生工具优先，MCP 工具加前缀：

```java
private static List<Tool> buildToolPool() {
    List<Tool> allTools = new ArrayList<>(NATIVE_TOOLS);
    Set<String> nativeNames = new HashSet<>();
    for (Tool tool : NATIVE_TOOLS) nativeNames.add(tool.name());

    List<Map<String, Object>> mcpTools = mcpRouter.getAllTools();
    for (Map<String, Object> mcpTool : mcpTools) {
        String name = (String) mcpTool.get("name");
        if (!nativeNames.contains(name)) {
            allTools.add(defineMcpTool(name, description, inputSchema));
        }
    }
    return allTools;
}
```

REPL 命令：

```
/tools    # 列出所有工具（原生 + MCP）
/mcp      # 显示已连接的 MCP 服务器
```

## 变更对比

| 组件          | S18           | S19                                  |
|---------------|---------------|--------------------------------------|
| MCP 客户端    | （无）        | MCPClient（JSON-RPC 2.0 over stdio） |
| 工具路由      | 原生分发表    | MCPToolRouter + 原生分发表           |
| 工具前缀      | （无）        | mcp__{server}__{tool}                |
| 权限门控      | （无）        | CapabilityPermissionGate（共享）      |
| 插件发现      | （无）        | PluginLoader（.claude-plugin/）      |
| 结果标准化    | 原始输出      | normalizeToolResult（source/risk/status）|

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S19McpPlugin"
```

1. 输入 `/tools` 查看所有可用工具
2. 在 `.claude-plugin/plugin.json` 中配置一个 MCP 服务器
3. 重启 Agent，输入 `/mcp` 查看已连接的服务器
4. 让 Agent 使用 MCP 工具完成任务，观察权限提示

## 要点总结

1. MCP 是通过 JSON-RPC 2.0 over stdio 与子进程通信的协议
2. MCP 工具以 `mcp__{server}__{tool}` 前缀与原生工具共存
3. 原生工具在名称冲突时优先，确保核心功能可预测
4. 所有工具（原生和 MCP）经过同一个权限门控，不做特殊化
5. 工具结果标准化为统一格式（source/risk/status），便于日志和审计
6. PluginLoader 从清单文件发现 MCP 服务器，实现即插即用
