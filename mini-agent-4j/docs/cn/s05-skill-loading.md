# s05: Skills (按需知识加载)

`s00 > s01 > s02 > s03 > s04 > [ s05 ] > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *不是把所有知识永远塞进 prompt，而是在需要的时候再加载正确那一份。*

## 这一章要解决什么问题

到了 `s04`，你的 agent 已经会：

- 调工具
- 做会话内规划
- 把大任务分给子 agent

接下来很自然会遇到另一个问题：

> 不同任务需要的领域知识不一样。

例如：

- 做代码审查，需要一套审查清单
- 做 Git 操作，需要一套提交约定
- 做 MCP 集成，需要一套专门步骤

如果你把这些知识包全部塞进 system prompt，就会出现两个问题：

1. 大部分 token 都浪费在当前用不到的说明上
2. prompt 越来越臃肿，主线规则越来越不清楚

所以这一章真正要做的是：

**把"长期可选知识"从 system prompt 主体里拆出来，改成按需加载。**

## 先解释几个名词

### 什么是 skill

这里的 `skill` 可以先简单理解成：

> 一份围绕某类任务的可复用说明书。

它通常会告诉 agent：

- 什么时候该用它
- 做这类任务时有哪些步骤
- 有哪些注意事项

### 什么是 discovery

`discovery` 指"发现有哪些 skill 可用"。

这一层只需要很轻量的信息，例如：

- skill 名字
- 一句描述

### 什么是 loading

`loading` 指"把某个 skill 的完整正文真正读进来"。

这一层才是昂贵的，因为它会把完整内容放进当前上下文。

## 最小心智模型

把这一章先理解成两层：

```text
第 1 层：轻量目录
  - skill 名称
  - skill 描述
  - 让模型知道"有哪些可用"

第 2 层：按需正文
  - 只有模型真正需要时才加载
  - 通过工具结果注入当前上下文
```

可以画成这样：

```text
system prompt
  |
  +-- Skills available:
      - code-review: review checklist
      - git-workflow: branch and commit guidance
      - mcp-builder: build an MCP server
```

当模型判断自己需要某份知识时：

```text
load_skill("code-review")
   |
   v
tool_result
   |
   v
<skill name="code-review">
完整审查说明
</skill>
```

这就是这一章最核心的设计。

## 关键数据结构

### 1. SkillManifest

先准备一份很轻的元信息。在 Java 实现中，manifest 信息存储在 `Map<String, String>` 里，包含 `name` 和 `description` 两个键：

```text
name: code-review
description: Checklist for reviewing code changes
```

它的作用只是让模型知道：

> 这份 skill 存在，并且大概是干什么的。

### 2. SkillDocument

真正被加载时，再读取完整内容。Java 实现中每个 skill 对应一个 `Map<String, String>`，包含四项：

```text
name        -> "code-review"
description -> "Checklist for reviewing code changes"
body        -> "... full skill text ..."
path        -> "/path/to/skills/code-review/SKILL.md"
```

### 3. SkillRegistry

你最好不要把 skill 散着读取。

更清楚的方式是做一个统一注册表。Java 实现中它是一个内部类：

```java
static class SkillRegistry {
    // key = 技能名, value = {name, description, body, path}
    private final Map<String, Map<String, String>> documents = new LinkedHashMap<>();

    SkillRegistry(Path skillsDir) { ... }

    String describeAvailable() { ... }   // Layer 1：目录信息
    String loadFullText(String name) { ... } // Layer 2：完整正文
}
```

它至少要能回答两个问题：

1. 有哪些 skill 可用
2. 某个 skill 的完整内容是什么

## 最小实现

### 第一步：把每个 skill 放成一个目录

最小结构可以这样：

```text
skills/
  code-review/
    SKILL.md
  git-workflow/
    SKILL.md
```

每个 `SKILL.md` 包含 YAML frontmatter 加正文：

```text
---
name: code-review
description: "Checklist for reviewing code changes"
---
技能正文内容...
```

### 第二步：从 `SKILL.md` 里读取最小元信息

Java 实现中，`SkillRegistry` 在构造时自动扫描 `skills` 目录：

```java
static class SkillRegistry {

    SkillRegistry(Path skillsDir) {
        if (skillsDir == null || !Files.exists(skillsDir)) {
            return;
        }
        try (var stream = Files.walk(skillsDir)) {
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                  .sorted()
                  .forEach(this::loadSkillFile);
        } catch (IOException e) {
            // 技能目录扫描失败时静默忽略
        }
    }

    private void loadSkillFile(Path file) {
        String text = Files.readString(file);
        var parsed = parseFrontmatter(text);   // 解析 YAML frontmatter
        String name = meta.getOrDefault("name", file.getParent().getFileName().toString());
        String description = meta.getOrDefault("description", "No description");
        // ... 存入 documents map
    }
}
```

这里的 `frontmatter` 你可以先简单理解成：

> 放在正文前面的一小段结构化元数据。

### 第三步：把 skill 目录放进 system prompt

```java
private static final SkillRegistry SKILL_REGISTRY = new SkillRegistry(SKILLS_DIR);

private static final String SYSTEM = "You are a coding agent at " + WORKDIR + ".\n"
        + "Use load_skill when a task needs specialized instructions before you act.\n\n"
        + "Skills available:\n"
        + SKILL_REGISTRY.describeAvailable();
```

注意这里放的是**目录信息**，不是完整正文。

`describeAvailable()` 返回的格式：

```text
- code-review: Checklist for reviewing code changes
- git-workflow: Branch and commit guidance
```

### 第四步：提供一个 `load_skill` 工具

```java
TOOL_HANDLERS.put("load_skill", input ->
        SKILL_REGISTRY.loadFullText((String) input.get("name")));
```

当模型调用它时，`loadFullText` 返回格式：

```text
<skill name="code-review">
完整审查说明
</skill>
```

如果技能不存在，返回错误提示和可用技能列表。

### 第五步：让 skill 正文只在当前需要时进入上下文

这一步的核心思想就是：

> 平时只展示"有哪些知识包"，真正工作时才把那一包展开。

Token 经济学：10 个 skill 的场景下，Layer 1 花费约 1,000 token（始终）。Layer 2 花费约 2,000 token/skill，但只有实际使用的 skill 才加载（通常每次会话 1-2 个）。相比全部预先塞入，总节省 80% 以上。

## skill、memory、CLAUDE.md 的边界

这三个概念很容易混。

### skill

可选知识包。
只有在某类任务需要时才加载。

### memory

跨会话仍然有价值的信息。
它是系统记住的东西，不是任务手册。

### CLAUDE.md

更稳定、更长期的规则说明。
它通常比单个 skill 更"全局"。

一个简单判断法：

- 这是某类任务才需要的做法或知识：`skill`
- 这是需要长期记住的事实或偏好：`memory`
- 这是更稳定的全局规则：`CLAUDE.md`

## 它如何接到主循环里

这一章以后，system prompt 不再只是一段固定身份说明。

它开始长出一个很重要的新段落：

- 可用技能目录

而消息流里则会出现新的按需注入内容：

- 某个 skill 的完整正文

也就是说，系统输入现在开始分成两层：

```text
稳定层：
  身份、规则、工具、skill 目录

按需层：
  当前真的加载进来的 skill 正文
```

这也是 `s10` 会继续系统化展开的东西。

## 初学者最容易犯的错

### 1. 把所有 skill 正文永远塞进 system prompt

这样会让 prompt 很快臃肿到难以维护。

### 2. skill 目录信息写得太弱

如果只有名字，没有描述，模型就不知道什么时候该加载它。

### 3. 把 skill 当成"绝对规则"

skill 更像"可选工作手册"，不是所有轮次都必须用。

### 4. 把 skill 和 memory 混成一类

skill 解决的是"怎么做一类事"，memory 解决的是"记住长期事实"。

### 5. 一上来就讲太多多源加载细节

教学主线真正要先讲清的是：

**轻量发现，重内容按需加载。**

## 教学边界

这章只要先守住两层就够了：

- 轻量发现：先告诉模型有哪些 skill
- 按需深加载：真正需要时再把正文放进输入

所以这里不用提前扩到：

- 多来源收集
- 条件激活
- skill 参数化
- fork 式执行
- 更复杂的 prompt 管道拼装

如果读者已经明白"为什么不能把所有 skill 永远塞进 system prompt，而应该先列目录、再按需加载"，这章就已经讲到位了。

## 试一试

### 准备技能文件

先创建两个测试用的技能：

```sh
cd mini-agent-4j
mkdir -p skills/docker skills/testing
```

创建 `skills/docker/SKILL.md`：

```text
---
name: docker
description: "Docker 容器化部署指南"
---
## Docker 部署步骤

1. 在项目根目录创建 Dockerfile
2. 使用多阶段构建减小镜像体积
3. 设置 EXPOSE 端口
4. 用 docker build -t <name> . 构建镜像
5. 用 docker run -p <port>:<port> <name> 运行容器

注意：不要用 latest 标签发布到生产；健康检查必须配置；环境变量不要硬编码。
```

创建 `skills/testing/SKILL.md`：

```text
---
name: testing
description: "Java 单元测试编写规范"
---
## 测试编写规范

1. 测试类放在 src/test/java 下，包路径与被测类一致
2. 测试方法命名：should_ExpectedBehavior_when_Condition
3. 每个测试只验证一个行为
4. 使用 AAA 模式：Arrange → Act → Assert
5. 跑所有测试：mvn test

注意：测试不能依赖执行顺序；外部依赖用 mock 隔离；边界值和异常路径必须覆盖。
```

### 启动

```sh
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S05SkillLoading"
```

启动时观察 dim 输出：`Loaded skills: docker, testing`（确认 Layer 1 目录已加载）。

### 案例 1：查看可用技能（Layer 1 验证）

> 只问"你有什么技能"，模型应该直接从 system prompt 中的目录回答，不调用 load_skill。

```
你有哪些可用的技能？
```

观察要点：
- 模型是否直接列出技能名和描述（来自 system prompt 的 Layer 1 信息）
- 日志中**不应该**出现 `> load_skill:` —— 说明模型没浪费 token 去加载完整正文
- 这就是 Layer 1 的价值：便宜地让模型知道"有什么"

### 案例 2：加载并使用技能（Layer 2 触发）

> 提一个需要特定领域知识的任务，观察模型主动加载对应技能。

```
帮这个 Java 项目写一个 Dockerfile
```

观察要点：
- 日志中出现 `> load_skill: <skill name="docker">` —— 模型判断需要 docker 知识，触发了 Layer 2
- `load_skill` 返回的完整技能正文通过 `tool_result` 注入上下文，模型随后按照技能中的步骤工作
- 对比案例 1：同样的 `load_skill` 工具，这次真的被调用了

### 案例 3：不同任务加载不同技能（按需选择性）

> 连续提两个不同领域的任务，观察模型每次只加载相关技能。

```
帮我给 S01TheAgentLoop.java 写一个单元测试
```

等它完成后，再问：

```
现在帮我把这个项目容器化
```

观察要点：
- 第一个问题触发 `> load_skill: <skill name="testing">`（加载测试技能）
- 第二个问题触发 `> load_skill: <skill name="docker">`（加载 Docker 技能）
- 模型**不会**一次性加载两个技能 —— 这就是"按需"的含义
- 两次 load_skill 之间，上下文中只包含当前任务需要的技能正文

### 案例 4：请求不存在的技能（错误处理）

> 故意让模型尝试加载一个不存在的技能。

```
加载 code-review 技能帮我审查代码
```

观察要点：
- `load_skill` 返回类似 `Error: Unknown skill 'code-review'. Available skills: docker, testing`
- 模型收到错误后如何恢复：是否告知用户该技能不可用，并建议用已有的技能代替
- 这验证了 `loadFullText` 的错误路径：未知技能名 → 返回可用列表

## 一句话记住

**Skill 系统的核心，不是"多一个工具"，而是"把可选知识从常驻 prompt 里拆出来，改成按需加载"。**
