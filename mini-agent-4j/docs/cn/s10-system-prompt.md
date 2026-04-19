# s10: System Prompt Construction (系统提示词构建)

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > s08 > s09 > [ s10 ] > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *系统提示词不是一整块大字符串，而是一条可维护的组装流水线。*

## 这一章为什么重要

很多初学者一开始会把 system prompt 写成一大段固定文本。

这样在最小 demo 里当然能跑。

但一旦系统开始长功能，你很快会遇到这些问题：

- 工具列表会变
- skills 会变
- memory 会变
- 当前目录、日期、模式会变
- 某些提醒只在这一轮有效，不该永远塞进系统说明

所以到了这个阶段，system prompt 不能再当成一块硬编码文本。

它应该升级成：

**由多个来源共同组装出来的一条流水线。**

## 建议联读

- 如果你还习惯把 prompt 看成"神秘大段文本"，先回 [`s00a-query-control-plane.md`](./s00a-query-control-plane.md)，重新确认模型输入在进模型前经历了哪些控制层。
- 如果你想真正稳住"哪些内容先拼、哪些后拼"，建议把 [`s10a-message-prompt-pipeline.md`](./s10a-message-prompt-pipeline.md) 放在手边，这页就是本章最关键的桥。
- 如果你开始把 system rules、工具说明、memory、runtime state 混成一个大块，先看 [`data-structures.md`](./data-structures.md)，把这些输入片段的来源重新拆开。

## 先解释几个名词

### 什么是 system prompt

system prompt 是给模型的系统级说明。

它通常负责告诉模型：

- 你是谁
- 你能做什么
- 你应该遵守什么规则
- 你现在处在什么环境里

### 什么是"组装流水线"

意思是：

- 不同信息来自不同地方
- 最后按顺序拼接成一份输入

它不是一个死字符串，而是一条构建过程。

### 什么是动态信息

有些信息经常变化，例如：

- 当前日期
- 当前工作目录
- 本轮新增的提醒

这些信息不适合和所有稳定说明混在一起。

## 最小心智模型

最容易理解的方式，是把 system prompt 想成 6 段：

```text
1. 核心身份和行为说明
2. 工具列表
3. skills 元信息
4. memory 内容
5. CLAUDE.md 指令链
6. 动态环境信息
```

然后按顺序拼起来：

```text
core
+ tools
+ skills
+ memory
+ claude_md
+ dynamic_context
= final system prompt
```

## 为什么不能把所有东西都硬塞进一个大字符串

因为这样会有三个问题：

### 1. 不好维护

你很难知道：

- 哪一段来自哪里
- 该修改哪一部分
- 哪一段是固定说明，哪一段是临时上下文

### 2. 不好测试

如果 system prompt 是一大坨文本，你很难分别测试：

- 工具说明生成得对不对
- memory 是否被正确拼进去
- CLAUDE.md 是否被正确读取

### 3. 不好做缓存和动态更新

一些稳定内容其实不需要每轮大变。
一些临时内容又只该活一轮。

这就要求你把"稳定块"和"动态块"分开思考。

## 最小实现结构

### 第一步：做一个 builder

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

        return String.join("\n\n",
            sections.stream().filter(s -> !s.isEmpty()).toList());
    }
}
```

这就是这一章最核心的设计。

### 第二步：每一段只负责一种来源

例如：

- `_buildToolListing()` 只负责把工具说明生成出来
- `_buildMemorySection()` 只负责拿 memory
- `_buildClaudeMd()` 只负责读指令文件

这样每一段的职责就很清楚。

## 关键的结构化边界

### 边界 1：稳定说明 vs 动态提醒

最重要的一组边界是：

- 稳定的系统说明
- 每轮临时变化的提醒

这两类东西不应该混为一谈。

### 边界 2：DYNAMIC_BOUNDARY

在 `S10SystemPrompt.java` 中，用一个常量标记分界：

```java
private static final String DYNAMIC_BOUNDARY = "=== DYNAMIC_BOUNDARY ===";
```

它的作用不是神秘魔法。

它只是提醒你：

**上面更稳定，下面更容易变。**

教学版可以先这样分：

```text
静态部分：
- core
- tools
- skills
- memory
- CLAUDE.md

动态部分：
- date
- cwd
- model
- current mode
```

### 边界 3：system prompt vs system reminder

system prompt 适合放：

- 身份
- 规则
- 工具
- 长期约束

system reminder 适合放：

- 这一轮才临时需要的补充上下文
- 当前变动的状态

所以更清晰的做法是：

- 主 system prompt 保持相对稳定
- 每轮额外变化的内容，用单独的 reminder 方式追加

## CLAUDE.md为什么要单独一段

因为它的角色不是"某一次任务的临时上下文"，而是更稳定的长期说明。

教学仓里，最容易理解的链条是：

1. 用户全局级：`~/.claude/CLAUDE.md`
2. 项目根目录级：`<project>/CLAUDE.md`
3. 当前子目录级：`<subdir>/CLAUDE.md`

然后全部拼进去，而不是互相覆盖。

```java
// 用户全局指令
Path userClaude = Path.of(System.getProperty("user.home"))
    .resolve(".claude").resolve("CLAUDE.md");

// 项目根目录
Path projectClaude = workdir.resolve("CLAUDE.md");

// 子目录（如果 cwd 与 workdir 不同）
Path subdirClaude = cwd.resolve("CLAUDE.md");
```

这样读者更容易理解"规则来源可以分层叠加"这个思想。

## memory为什么要和system prompt有关系

因为 memory 的本质是：

**把跨会话仍然有价值的信息，重新带回模型当前的工作环境。**

如果保存了 memory，却从来不在系统输入中重新呈现，那它就等于没被真正用起来。

所以 memory 最终一定要进入 prompt 组装链条。

在 `S10SystemPrompt.java` 中，段落 4 (`_buildMemorySection()`) 扫描 `.memory/` 目录下的 `.md` 文件，将持久记忆注入系统提示词。

## 初学者最容易混淆的点

### 1. 把 system prompt 讲成一个固定字符串

这会让读者看不到系统是如何长大的。

### 2. 把所有变化信息都塞进 system prompt

这会把稳定说明和临时提醒搅在一起。

### 3. 把 CLAUDE.md、memory、skills 写成同一种东西

它们都可能进入 prompt，但来源和职责不同：

- `skills`：可选能力或知识包
- `memory`：跨会话记住的信息
- `CLAUDE.md`：长期规则说明

## 教学边界

这一章先只建立一个核心心智：

**prompt 不是一整块静态文本，而是一条被逐段组装出来的输入流水线。**

所以这里先不要扩到太多外层细节：

- 不要先讲复杂的 section 注册系统
- 不要先讲缓存与预算
- 不要先讲所有外部能力如何追加 prompt 说明

只要读者已经能把稳定规则、动态提醒、memory、skills 这些来源看成不同输入段，而不是同一种"大 prompt"，这一章就已经讲到位了。

## 这章和后续章节的关系

这一章像一个汇合点：

- `s05` skills 会汇进来
- `s09` memory 会汇进来
- `s07` 的当前模式也可能汇进来
- `s19` MCP 以后也可能给 prompt 增加说明

所以 `s10` 的价值不是"新加一个功能"，
而是"把前面长出来的功能组织成一份清楚的系统输入"。

## 试一试

### 启动

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S10SystemPrompt"
```

启动时观察 dim 输出：`S10 System Prompt — assembled: N chars, ~N sections`（确认段落组装成功）。

### 案例 1：基础段落组装（/sections + /prompt 验证）

> 用 /sections 查看段落结构，用 /prompt 查看完整组装内容。

启动后输入：

```
/sections
```

观察要点：
- 输出包含 `# Available tools`、`=== DYNAMIC_BOUNDARY ===`、`# Dynamic context` 等段落标题
- `# Available tools` 段落列出 4 个工具：bash、read_file、write_file、edit_file
- DYNAMIC_BOUNDARY 之前的段落是稳定的，之后是动态的

再查看完整提示词：

```
/prompt
```

观察要点：
- 输出 `--- System Prompt ---` 和 `--- End ---` 之间是完整组装内容
- 段落 1（core）以 "You are a coding agent operating in..." 开头
- 段落 6（dynamic）包含当前日期、工作目录、模型 ID、操作系统平台

### 案例 2：Skills 段落注入

> 创建 skills 目录和 SKILL.md 文件，重启验证技能元数据被组装进提示词。

先创建测试技能：

```sh
mkdir -p skills/docker
```

创建 `skills/docker/SKILL.md`：

```text
---
name: docker
description: Docker 容器化部署指南
---
## Docker 部署步骤
1. 在项目根目录创建 Dockerfile
2. 使用多阶段构建减小镜像体积
```

重启 Agent 后输入：

```
/sections
```

观察要点：
- 段落列表新增 `# Available skills` —— 段落 3 被自动注入
- 输入 `/prompt` 可看到 `- docker: Docker 容器化部署指南`
- 技能正文不在提示词中（只有 Layer 1 元数据：name + description）

### 案例 3：Memory 段落注入

> 创建 .memory 目录和记忆文件，重启验证记忆内容被组装进提示词。

创建 `.memory/deploy_env.md`：

```text
---
name: deploy_env
description: 生产环境部署地址
type: reference
---
生产环境 API 部署在 api.example.com，前端部署在 web.example.com。
```

重启 Agent 后输入：

```
/sections
```

观察要点：
- 段落列表新增 `# Memories (persistent)` —— 段落 4 被自动注入
- 输入 `/prompt` 可看到 `[reference] deploy_env: 生产环境部署地址`
- 记忆正文也在提示词中（与 skills 不同，memory 的完整内容会被注入）

然后在 REPL 中提问验证记忆生效：

```
生产环境的 API 地址是什么？
```

观察要点：
- 模型从系统提示词的记忆段落中找到答案，直接回答 `api.example.com`
- 无需调用工具读取文件（记忆已在 system prompt 中）

### 案例 4：CLAUDE.md 链加载 + DYNAMIC_BOUNDARY 验证

> 创建项目级 CLAUDE.md，重启验证指令链被组装；同时确认静态/动态分界。

在项目根目录创建 `CLAUDE.md`：

```text
# 项目规则
- 所有 Java 文件使用 4 空格缩进
- 提交信息使用中文
- 不要修改 vendors/ 目录下的文件
```

重启 Agent 后输入：

```
/sections
```

观察要点：
- 段落列表新增 `# CLAUDE.md instructions` 和 `## From project root (CLAUDE.md)` —— 段落 5 被注入
- DYNAMIC_BOUNDARY 位于 CLAUDE.md 和 Dynamic context 之间，标记静态/动态分界
- 如果在 `~/.claude/CLAUDE.md` 也放了文件，会看到 `## From user global` 出现在 `## From project root` 之前

验证规则生效：

```
帮我修改 vendors 目录下的某个文件
```

观察要点：
- 模型遵守 CLAUDE.md 中的规则，拒绝修改 vendors/ 目录
- 这证明 CLAUDE.md 指令通过系统提示词组装影响了模型行为

## 学完这章后你应该能回答

- 为什么 system prompt 不能只是一整块硬编码文本？
- 为什么要把不同来源拆成独立 section？
- system prompt 和 system reminder 的边界是什么？
- memory、skills、CLAUDE.md 为什么都可能进入 prompt，但又不是一回事？

---

**一句话记住：system prompt 的关键不是"写一段很长的话"，而是"把不同来源的信息按清晰边界组装起来"。**
