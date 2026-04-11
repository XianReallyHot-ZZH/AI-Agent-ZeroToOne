# s10：系统提示词组装

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > [ s10 ] s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"系统提示词的构建是一条有边界的流水线，不是一个大字符串。"* —— 清晰的段落比巨大的文本块更容易推理和演进。

## 课程目标

理解如何将系统提示词拆分为多个独立段落，按需组装。每个段落职责单一，使得提示词更容易推理、测试和迭代。

## 问题

系统提示词如果写成一个巨大的硬编码字符串，会变得难以维护。模型需要知道它的身份、可用工具、项目约定、用户偏好、当前日期等多种信息，这些信息来源不同、更新频率不同。需要一个结构化的组装机制。

## 方案

系统提示词由 6 个段落依次组装，稳定内容与动态内容通过 `DYNAMIC_BOUNDARY` 标记分离：

```
+----------------------------------+
| 段落 1: core instructions        | ─┐
| 段落 2: tool listing             |  │ 稳定内容
| 段落 3: skill metadata           |  │ (可跨轮次缓存)
| 段落 4: memory section           |  │
| 段落 5: CLAUDE.md chain          | ─┘
+==================================+  ← DYNAMIC_BOUNDARY
| 段落 6: dynamic context          | ── 动态内容 (每轮重建)
+----------------------------------+
```

## 核心概念

### SystemPromptBuilder —— 6 段落组装器

```java
static class SystemPromptBuilder {
    public String build() {
        List<String> sections = new ArrayList<>();
        sections.add(_buildCore());           // 1. 身份和基本指令
        sections.add(_buildToolListing());    // 2. 可用工具列表
        sections.add(_buildSkillListing());   // 3. skills/ 下的技能元数据
        sections.add(_buildMemorySection());  // 4. .memory/ 下的持久记忆
        sections.add(_buildClaudeMd());       // 5. CLAUDE.md 指令链
        sections.add(DYNAMIC_BOUNDARY);       // 静态/动态分界
        sections.add(_buildDynamicContext()); // 6. 日期、平台、模型
        return String.join("\n\n", sections);
    }
}
```

### 段落 1：核心指令

```java
private String _buildCore() {
    return "You are a coding agent operating in " + workdir + ".\n"
         + "Use the provided tools to explore, read, write, and edit files.\n"
         + "Always verify before assuming. Prefer reading files over guessing.";
}
```

### 段落 2：工具清单

从工具定义中提取名称、参数和描述：

```java
private String _buildToolListing() {
    for (Tool tool : tools) {
        lines.add("- " + tool.name() + "(" + params + "): " + desc);
    }
}
```

### 段落 3：技能元数据

扫描 `skills/` 目录下的 `SKILL.md` 文件，解析 frontmatter。

### 段落 4：记忆内容

扫描 `.memory/` 目录下的 `.md` 文件，注入持久记忆。

### 段落 5：CLAUDE.md 链

按优先级加载所有 CLAUDE.md 文件（全部包含，不互斥）：

```
~/.claude/CLAUDE.md         → 用户全局指令
<project>/CLAUDE.md         → 项目根目录指令
<subdir>/CLAUDE.md          → 子目录特定指令
```

### 段落 6：动态上下文

```java
private String _buildDynamicContext() {
    lines.add("Current date: " + LocalDate.now());
    lines.add("Working directory: " + workdir);
    lines.add("Model: " + model);
    lines.add("Platform: " + System.getProperty("os.name"));
}
```

### 缓存优化

`DYNAMIC_BOUNDARY` 标记将稳定内容（段落 1-5）与动态内容（段落 6）分离。在生产环境中，稳定前缀可以跨轮次缓存以节省 prompt tokens。

## 关键代码片段

每轮循环重建系统提示词：

```java
while (true) {
    // 每轮重建系统提示词
    String system = promptBuilder.build();
    paramsBuilder.system(system);

    Message response = client.messages().create(paramsBuilder.build());
    // ...
}
```

REPL 命令检查提示词：

```
/prompt      # 显示完整组装的系统提示词
/sections    # 只显示段落标题行
```

## 变更对比

| 组件          | S09             | S10                                  |
|---------------|-----------------|--------------------------------------|
| 系统提示词    | 动态（记忆注入）| 6 段落流水线组装                     |
| 组装器        | buildSystemPrompt() | SystemPromptBuilder 内部类        |
| CLAUDE.md     | （无）          | 用户全局 + 项目 + 子目录链           |
| 工具清单      | （无）          | 自动从 Tool 定义提取                 |
| 技能元数据    | （无）          | 扫描 skills/ 目录                    |
| 缓存标记      | （无）          | DYNAMIC_BOUNDARY                     |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S10SystemPrompt"
```

1. 输入 `/sections` 查看提示词包含哪些段落
2. 输入 `/prompt` 查看完整组装的提示词
3. 创建一个 `CLAUDE.md` 文件，重新输入 `/prompt` 观察变化
4. 在 `.memory/` 目录添加记忆文件，观察段落 4 出现

## 要点总结

1. 系统提示词是 6 个独立段落的有序拼接，不是一个大字符串
2. 每个段落职责单一：身份、工具、技能、记忆、指令链、动态上下文
3. DYNAMIC_BOUNDARY 分离稳定内容和动态内容，为缓存优化提供基础
4. CLAUDE.md 链让用户在不同层级（全局、项目、目录）定制行为
5. 段落不存在时自动跳过（空字符串），不产生多余输出
