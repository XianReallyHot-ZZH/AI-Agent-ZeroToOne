# s19: MCP Plugin System

`s01 > ... > s17 > s18 > [ s19 ]`

> *"External tools should flow through the same pipeline, not form a separate world."*
>
> **Harness layer**: The plugin system -- MCP servers that extend the agent with external tools, sharing the same permission gate.

## Problem

Your agent has four built-in tools (bash, read, write, edit), but real projects need more: database queries, API calls, file format converters. You could hardcode each new tool, but that makes the agent a monolith. You need a way for external processes to expose tools that the agent can discover and use -- without bypassing security.

## Solution

```
  1. Start MCP server process (via plugin manifest)
  2. Query server for available tools (JSON-RPC 2.0 over stdio)
  3. Prefix and register tools: mcp__{server}__{tool}
  4. Route matching calls to the correct server
  5. All tools pass through the same permission gate

  Tool pool:
  +------------------------------------------+
  | Native tools (priority on name conflict) |
  |   bash, read_file, write_file, edit_file |
  | MCP tools (prefixed)                     |
  |   mcp__weather__get_forecast             |
  |   mcp__database__query                   |
  +------------------------------------------+
```

## How It Works

### Plugin discovery: PluginLoader

```java
static class PluginLoader {
    // Scans .claude-plugin/plugin.json manifest files
    List<String> scan() {
        for (Path searchDir : searchDirs) {
            Path manifestPath = searchDir.resolve(".claude-plugin/plugin.json");
            if (Files.exists(manifestPath)) {
                Map<String, Object> manifest = JSON_MAPPER.readValue(content, Map.class);
                this.plugins.put(name, manifest);
            }
        }
    }

    // Extract MCP server configs from manifests
    Map<String, Map<String, Object>> getMcpServers() {
        // Returns: {pluginName__serverName} -> {command, args, env}
    }
}
```

A plugin manifest (`plugin.json`) declares MCP servers:

```json
{
  "name": "weather-tools",
  "mcpServers": {
    "weather": {
      "command": "python",
      "args": ["weather_server.py"],
      "env": {"API_KEY": "..."}
    }
  }
}
```

### MCP client: JSON-RPC 2.0 over stdio

```java
static class MCPClient {
    boolean connect() {
        // 1. Start subprocess
        this.process = new ProcessBuilder(commandList).start();

        // 2. Send initialize request
        send("initialize", Map.of(
            "protocolVersion", "2024-11-05",
            "clientInfo", Map.of("name", "teaching-agent", "version", "1.0")
        ));

        // 3. Receive initialize response
        Map<String, Object> response = recv();

        // 4. Send initialized notification (no id, no response expected)
        sendNotification("notifications/initialized");
    }

    List<Map<String, Object>> listTools() {
        send("tools/list", Map.of());
        // Parse response -> list of tool definitions
    }

    String callTool(String toolName, Map<String, Object> arguments) {
        send("tools/call", Map.of("name", toolName, "arguments", arguments));
        // Parse response -> content blocks with text
    }
}
```

### Tool prefixing and routing

```java
static class MCPToolRouter {
    // MCP tools get prefixed: mcp__{server}__{tool}
    // Example: mcp__weather__get_forecast

    boolean isMcpTool(String toolName) {
        return toolName.startsWith("mcp__");
    }

    String call(String toolName, Map<String, Object> arguments) {
        String[] parts = toolName.split("__", 3);  // mcp, weather, get_forecast
        MCPClient client = clients.get(parts[1]);   // find server
        return client.callTool(parts[2], arguments); // call original tool
    }
}
```

### Shared permission gate

All tools -- native and MCP -- pass through the same `CapabilityPermissionGate`:

```java
static class CapabilityPermissionGate {
    Map<String, Object> normalize(String toolName, Map<String, Object> toolInput) {
        // Parse mcp__{server}__{tool} format
        // Classify risk: read / write / high
        //   read  -> read, list, get, show, search, query, inspect
        //   high  -> delete, remove, drop, shutdown + dangerous bash
        //   write -> everything else
    }

    Map<String, Object> check(String toolName, Map<String, Object> toolInput) {
        // read -> allow
        // auto mode + non-high -> allow
        // high -> ask user
        // other -> ask user
    }
}
```

### Building the unified tool pool

```java
private static List<Tool> buildToolPool() {
    List<Tool> allTools = new ArrayList<>(NATIVE_TOOLS);

    // Collect native names for dedup
    Set<String> nativeNames = NATIVE_TOOLS.stream()
        .map(Tool::name).collect(Collectors.toSet());

    // Add MCP tools (skip conflicts -- native wins)
    for (Map<String, Object> mcpTool : mcpRouter.getAllTools()) {
        if (!nativeNames.contains(mcpTool.get("name"))) {
            allTools.add(defineMcpTool(name, description, inputSchema));
        }
    }
    return allTools;
}
```

### Standardized tool results

```java
private static String normalizeToolResult(String toolName, String output, Map<String, Object> intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("source", intent.get("source"));   // "native" or "mcp"
    payload.put("server", intent.get("server"));    // server name or null
    payload.put("tool",   intent.get("tool"));      // actual tool name
    payload.put("risk",   intent.get("risk"));       // read/write/high
    payload.put("status", output.contains("Error:") ? "error" : "ok");
    payload.put("preview", output.substring(0, Math.min(output.length(), 500)));
    return JSON_MAPPER.writeValueAsString(payload);
}
```

## What Changed

| Component     | Before              | After                                        |
|---------------|---------------------|----------------------------------------------|
| Tools         | 4 hardcoded         | 4 native + N MCP tools (from plugin manifests) |
| Tool naming   | Plain names         | MCP tools prefixed `mcp__{server}__{tool}`  |
| Permission    | Built-in only       | Shared gate: native + MCP use same pipeline  |
| Discovery     | (none)              | `.claude-plugin/plugin.json` manifests       |
| Communication | Direct dispatch     | JSON-RPC 2.0 over stdio for MCP              |
| Results       | Raw strings         | Standardized JSON with source/risk/status    |
| Key classes   | (none)              | `MCPClient`, `MCPToolRouter`, `PluginLoader`, `CapabilityPermissionGate` |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S19McpPlugin"
```

1. `/tools` -- list all available tools (native + MCP)
2. `/mcp` -- show connected MCP servers
3. Create a `.claude-plugin/plugin.json` manifest pointing to a simple MCP server
4. Restart and verify the MCP tools appear in `/tools`
5. Call an MCP tool and observe the permission gate prompt
6. Check that native tools still work normally alongside MCP tools
