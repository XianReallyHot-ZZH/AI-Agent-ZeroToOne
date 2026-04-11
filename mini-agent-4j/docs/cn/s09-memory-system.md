# s09：记忆系统

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > [ s09 ] s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"记忆只存储：跨会话仍有召回价值，且不易从仓库重新推导的信息。"* —— 不是所有东西都值得记忆。

## 课程目标

理解如何为 Agent 构建持久化的跨会话记忆系统。记忆系统让 Agent 能够记住用户偏好、项目约定和外部资源指针，而不需要用户每次重复说明。

## 问题

每次会话 Agent 都从零开始。用户说"我喜欢用 tabs"，Agent 在下次会话就忘了。反复纠正 Agent 同样的错误，既浪费时间又令人沮丧。需要一种机制让关键信息跨越会话边界存活。

## 方案

记忆系统使用文件系统作为持久存储，Markdown + frontmatter 作为格式：

```
.memory/
  MEMORY.md           ← 索引文件（200 行上限）
  prefer_tabs.md      ← 单条记忆（frontmatter + 正文）
  review_style.md
  incident_board.md
```

记忆分为四种类型：

| 类型       | 用途                           | 示例                               |
|-----------|-------------------------------|------------------------------------|
| user      | 用户偏好                       | "我喜欢 tabs"、"始终用 pytest"      |
| feedback  | 用户纠正                       | "别做 X"、"那样做是错的因为..."     |
| project   | 不易推断的项目事实              | "这个模块因合规原因不能改"          |
| reference | 外部资源指针                    | 看板地址、文档 URL                  |

## 核心概念

### MemoryManager —— 记忆管理器

```java
static class MemoryManager {
    // 扫描 .memory/*.md，解析 frontmatter，加载到内存
    void loadAll();

    // 构建 Markdown 片段注入系统提示词
    String loadMemoryPrompt();

    // 写入 frontmatter 文件，重建索引
    String saveMemory(String name, String description, String memType, String content);
}
```

### Frontmatter 存储格式

每条记忆是一个独立的 Markdown 文件，使用 frontmatter 存储元数据：

```markdown
---
name: prefer_tabs
description: 用户偏好使用 tabs 而非 spaces
type: user
---
项目代码统一使用 tabs 缩进，tab 宽度设为 4。
所有新文件必须遵循此约定。
```

### 记忆注入系统提示词

记忆在每次 LLM 调用前动态注入系统提示词：

```java
private static String buildSystemPrompt() {
    List<String> parts = new ArrayList<>();
    parts.add("You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.");

    // 注入记忆内容
    String memorySection = memoryMgr.loadMemoryPrompt();
    if (!memorySection.isEmpty()) {
        parts.add(memorySection);
    }

    // 注入记忆使用指南
    parts.add(MEMORY_GUIDANCE);
    return String.join("\n\n", parts);
}
```

### save_memory 工具

模型可以主动保存记忆（第 5 个工具）：

```java
defineTool("save_memory", "Save a persistent memory that survives across sessions.",
    Map.of(
        "name", Map.of("type", "string", "description", "Short identifier"),
        "description", Map.of("type", "string", "description", "One-line summary"),
        "type", Map.of("type", "string", "enum", List.of("user", "feedback", "project", "reference")),
        "content", Map.of("type", "string", "description", "Full memory content")),
    List.of("name", "description", "type", "content"));
```

### DreamConsolidator —— 记忆整理器（可选）

七道门检查防止不必要的整理，四阶段处理流程：

```
门 1: enabled 标志
门 2: 记忆目录存在且有文件
门 3: 非 plan 模式
门 4: 24 小时冷却期
门 5: 10 分钟扫描节流
门 6: 最少 5 次会话
门 7: PID 锁（防止并发）
→ 全部通过 → Orient → Gather → Consolidate → Prune
```

## 关键代码片段

记忆感知的 Agent 循环，每轮动态重建系统提示词：

```java
while (true) {
    // 动态重建系统提示词（记忆感知）
    String systemPrompt = buildSystemPrompt();

    var loopParamsBuilder = MessageCreateParams.builder()
        .model(model)
        .maxTokens(8000L)
        .system(systemPrompt)   // 注入最新记忆
        .messages(paramsBuilder.build().messages());

    Message response = client.messages().create(loopParamsBuilder.build());
    // ...
}
```

REPL 命令查看当前记忆：

```
/memories     # 列出所有记忆
```

## 变更对比

| 组件          | S08         | S09                                  |
|---------------|-------------|--------------------------------------|
| 记忆管理      | （无）      | MemoryManager + .memory/ 目录        |
| 记忆类型      | （无）      | user / feedback / project / reference |
| 记忆工具      | （无）      | save_memory（第 5 个工具）           |
| 系统提示词    | 静态字符串  | 动态重建（注入记忆）                 |
| 记忆整理      | （无）      | DreamConsolidator（7 门 + 4 阶段）   |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S09MemorySystem"
```

1. 输入 `请记住：我喜欢使用 tabs 而非 spaces 进行缩进`
2. 输入 `/memories` 查看已保存的记忆
3. 检查 `.memory/` 目录下生成的文件
4. 重启 Agent，观察记忆自动加载

## 要点总结

1. 记忆 = 文件系统中的 Markdown + frontmatter，一条记忆一个文件
2. 四种记忆类型覆盖不同场景：偏好、纠正、项目事实、外部资源
3. 系统提示词每轮动态重建，确保新保存的记忆立即可见
4. 索引文件 MEMORY.md 限制 200 行，防止记忆膨胀
5. DreamConsolidator 通过 7 道门检查防止不必要的整理开销
