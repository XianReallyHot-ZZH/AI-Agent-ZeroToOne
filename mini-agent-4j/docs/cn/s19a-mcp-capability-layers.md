# s19a: MCP Capability Layers (MCP 能力层地图)

> `s19` 的主线仍然应该坚持"先做 tools-first"。
> 这篇桥接文档负责补上另一层心智：
>
> **MCP 不只是外部工具接入，它是一组能力层。**

## 建议怎么联读

如果你希望 MCP 这块既不学偏，也不学浅，推荐这样看：

- 先看 [`s19-mcp-plugin.md`](./s19-mcp-plugin.md)（`S19McpPlugin.java`），先把 tools-first 主线走通。
- 再看 [`s02a-tool-control-plane.md`](./s02a-tool-control-plane.md)，确认外部能力最后怎样接回统一工具总线。
- 如果概念边界开始混，再回 `s00` 系列的总览文档。

## 为什么要单独补这一篇

如果你是为了教学，从 0 到 1 手搓一个类似系统，那么 `s19` 主线先只讲外部工具，这是对的。

因为最容易理解的入口就是：

- 连接一个外部 server
- 拿到工具列表
- 调用工具
- 把结果带回 agent

但如果你想把系统做到接近 95%-99% 的还原度，你迟早会遇到这些问题：

- server 是用 stdio、http、sse 还是 ws 连接？
- 为什么有些 server 是 connected，有些是 pending，有些是 needs-auth？
- tools 之外，resources 和 prompts 是什么位置？
- elicitation 为什么会变成一类特殊交互？
- OAuth / XAA 这种认证流程该放在哪一层理解？

这时候如果没有一张"能力层地图"，MCP 就会越学越散。

## 先解释几个名词

### 什么是能力层

能力层，就是把一个复杂系统拆成几层职责清楚的面。

这里的意思是：

> 不要把所有 MCP 细节混成一团，而要知道每一层到底解决什么问题。

### 什么是 transport

`transport` 可以理解成"连接通道"。

比如：

- stdio（标准输入输出）
- http
- sse（Server-Sent Events）
- websocket

在 Java 中，不同的 transport 对应不同的通信实现：

```text
stdio    -> ProcessBuilder + stdin/stdout（教学版首选）
http     -> HttpClient（Java 11+）
sse      -> HttpClient + streaming response
websocket-> WebSocketClient（Java HttpClient 或 Tyrus）
```

### 什么是 elicitation

这个词比较生。

你可以先把它理解成：

> 外部 MCP server 反过来向用户请求额外输入的一种交互。

也就是说，不再只是 agent 主动调工具，而是 server 也能说：

"我还需要你给我一点信息，我才能继续。"

## 最小心智模型

先把 MCP 画成 6 层：

```text
1. Config Layer
   server 配置长什么样

2. Transport Layer
   用什么通道连 server

3. Connection State Layer
   现在是 connected / pending / failed / needs-auth

4. Capability Layer
   tools / resources / prompts / elicitation

5. Auth Layer
   是否需要认证，认证状态如何

6. Router Integration Layer
   如何接回 tool router / permission / notifications
```

最重要的一点是：

**tools 只是其中一层，不是全部。**

## 为什么正文仍然应该坚持 tools-first

这点非常重要。

虽然 MCP 平台本身有多层能力，但正文主线仍然应该这样安排：

### 第一步：先教外部 tools

因为它和前面的主线最自然衔接：

- 本地工具
- 外部工具
- 同一条 router

### 第二步：再告诉读者还有其他能力层

例如：

- resources
- prompts
- elicitation
- auth

### 第三步：再决定是否继续实现

这才符合你的教学目标：

**先做出类似系统，再补平台层高级能力。**

## 关键数据结构

### 1. ScopedMcpServerConfig

最小教学版建议至少让读者看到这个概念。在 Java 中用 record 表达：

```java
/**
 * 带作用域的 MCP 服务器配置 —— 描述一个 MCP server 的连接方式。
 */
public record ScopedMcpServerConfig(
    String name,                     // "postgres"
    String type,                     // "stdio" | "http" | "sse" | "ws"
    String command,                  // 启动命令，如 "npx"
    List<String> args,               // 命令参数
    McpConfigScope scope             // 配置来源的作用域
) {
    public enum McpConfigScope {
        PROJECT,     // 项目级配置（.agent/mcp-servers.json）
        USER,        // 用户级配置（~/.agent/mcp-servers.json）
        PLATFORM     // 平台级配置
    }
}
```

这里的 `scope` 很重要。

因为 server 配置不一定都来自同一个地方。

### 2. MCP Connection State

在 Java 中用 record 表达连接状态：

```java
/**
 * MCP 服务器连接状态 —— 描述一个 server 的当前连接情况。
 */
public record MCPServerConnectionState(
    String name,                            // "postgres"
    McpConnectionStatus status,             // 当前连接状态
    ScopedMcpServerConfig config,           // 对应的配置
    Instant lastConnectedAt,                // nullable，上次成功连接时间
    String errorMessage                     // nullable，最近的错误信息
) {
    public enum McpConnectionStatus {
        CONNECTED,      // 已连接，可正常调用
        PENDING,        // 正在连接中
        FAILED,         // 连接失败
        NEEDS_AUTH,     // 需要认证
        DISABLED        // 已禁用
    }
}
```

### 3. MCPToolSpec

```java
/**
 * MCP 工具规格 —— 外部 server 暴露的工具描述。
 */
public record MCPToolSpec(
    String name,                             // "mcp__postgres__query"
    String description,
    Map<String, Object> inputSchema
) {
    /**
     * 从原始 MCP server 返回的工具描述构造 MCPToolSpec。
     */
    public static MCPToolSpec fromServer(String serverName,
                                          Map<String, Object> rawTool) {
        String toolName = (String) rawTool.get("name");
        return new MCPToolSpec(
            "mcp__" + serverName + "__" + toolName,
            (String) rawTool.get("description"),
            (Map<String, Object>) rawTool.get("inputSchema")
        );
    }
}
```

### 4. ElicitationRequest

```java
/**
 * 询问请求 —— MCP server 反过来向用户请求额外输入。
 */
public record ElicitationRequest(
    String serverName,                       // "some-server"
    String message,                          // "Please provide additional input"
    Map<String, Object> requestedSchema      // 期望的输入格式
) {}
```

这一步不是要求你主线立刻实现它，而是要让读者知道：

**MCP 不一定永远只是"模型调工具"。**

## Java 中 MCP Client 的 Transport 实现

教学版建议从 stdio 开始，因为它最简单：

```java
/**
 * MCP stdio 客户端 —— 通过子进程 stdin/stdout 进行 JSON-RPC 2.0 通信。
 */
public class MCPStdioClient implements MCPClient {

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private int nextId = 1;

    @Override
    public boolean connect(ScopedMcpServerConfig config) {
        ProcessBuilder pb = new ProcessBuilder(config.command());
        pb.command().addAll(config.args());
        pb.redirectErrorStream(false);
        process = pb.start();
        writer = process.writer();
        reader = process.reader();

        // JSON-RPC 2.0: initialize
        Map<String, Object> initRequest = Map.of(
            "jsonrpc", "2.0",
            "id", nextId++,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mini-agent-4j", "version", "1.0")
            )
        );
        send(initRequest);
        Map<String, Object> response = receive();

        // notifications/initialized（无 id）
        send(Map.of(
            "jsonrpc", "2.0",
            "method", "notifications/initialized"
        ));
        return response != null;
    }
}
```

## 一张更完整但仍然清楚的图

```text
MCP Config (.agent/mcp-servers.json)
  |
  v
Transport (stdio / http / sse / ws)
  |
  v
Connection State
  |
  +-- CONNECTED      → 可正常调用
  +-- PENDING        → 正在连接中
  +-- NEEDS_AUTH     → 需要认证
  +-- FAILED         → 连接失败
  +-- DISABLED       → 已禁用
  |
  v
Capabilities
  +-- tools         → s19 主线已实现
  +-- resources     → 外部资源（文件、数据源等）
  +-- prompts       → 外部提示词模板
  +-- elicitation   → server 反向询问用户
  |
  v
Router / Permission / Notification Integration
  |
  +-- ToolRouter（s02a）
  +-- CapabilityPermissionGate（s19）
  +-- NotificationManager（s13）
```

## 配置文件的位置

在 mini-agent-4j 中，MCP 配置从 `.agent/` 目录加载（对应用户系统的 `.claude/`）：

```text
.agent/
  mcp-servers.json        # 项目级 MCP 服务器配置
  AGENT.md                # 项目级 Agent 指令

~/.agent/
  mcp-servers.json        # 用户级 MCP 服务器配置
  AGENT.md                # 用户全局指令
```

配置文件格式：

```json
{
  "mcpServers": {
    "postgres": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-postgres"],
      "env": {
        "DATABASE_URL": "postgresql://localhost:5432/mydb"
      }
    }
  }
}
```

## Auth 为什么不要在主线里讲太多

这也是教学取舍里很重要的一点。

认证是真实系统里确实存在的能力层。
但如果正文一开始就掉进 OAuth/XAA 流程，初学者会立刻丢主线。

所以更好的讲法是：

- 先告诉读者：有 auth layer
- 再告诉读者：`CONNECTED` 和 `NEEDS_AUTH` 是不同连接状态
- 只有做平台层进阶时，再详细展开认证流程

这就既没有幻觉，也没有把人带偏。

在 Java 中，认证状态可以简单表达为：

```java
// 连接状态机简化版
public MCPServerConnectionState checkAuth(MCPServerConnectionState state) {
    return switch (state.status()) {
        case CONNECTED -> state;  // 已经连接，无需认证
        case NEEDS_AUTH -> {
            // 触发认证流程（教学版暂不实现）
            yield state;
        }
        case FAILED -> state;     // 连接失败，认证无法进行
        default -> state;         // PENDING / DISABLED，等待或跳过
    };
}
```

## 它和 `s19`、`s02a` 的关系

- `s19`（`S19McpPlugin.java`）正文继续负责 tools-first 教学
- 这篇负责补清平台层地图
- `s02a` 的 Tool Control Plane 则解释 MCP 最终怎么接回统一工具总线

三者合在一起，读者才会真正知道：

**MCP 是外部能力平台，而 tools 只是它最先进入主线的那个切面。**

## 初学者最容易犯的错

### 1. 把 MCP 只理解成"外部工具目录"

这会让后面遇到 auth / resources / prompts / elicitation 时很困惑。

### 2. 一上来就沉迷 transport 和 OAuth 细节

这样会直接打断主线。

### 3. 让 MCP 工具绕过 permission

这会在系统边上开一个很危险的后门。

在 mini-agent-4j 中，所有 MCP 工具都必须经过 `CapabilityPermissionGate`：

```java
// MCP 工具也要过权限门
Map<String, Object> intent = permissionGate.normalize(toolName, toolInput);
Map<String, Object> decision = permissionGate.check(toolName, toolInput);
if ("deny".equals(decision.get("decision"))) {
    return ToolResultEnvelope.error("Permission denied: " + decision.get("reason"));
}
```

### 4. 不区分 server 配置、连接状态、能力暴露

这三层一混，平台层就会越学越乱。

## 教学边界

这篇最重要的，不是把 MCP 所有外设细节都讲完，而是先守住四层边界：

- server 配置（`ScopedMcpServerConfig`）
- 连接状态（`MCPServerConnectionState`）
- capability 暴露（`MCPToolSpec` / resources / prompts / elicitation）
- permission / routing 接入点（`ToolRouter` + `CapabilityPermissionGate`）

只要这四层不混，你就已经能自己手搓一个接近真实系统主脉络的外部能力入口。
认证状态机、resource/prompt 接入、server 回问和重连策略，都属于后续平台扩展。

## 一句话记住

**`s19` 主线应该先教"外部工具接入"，而平台层还需要额外理解 MCP 的能力层地图。**
