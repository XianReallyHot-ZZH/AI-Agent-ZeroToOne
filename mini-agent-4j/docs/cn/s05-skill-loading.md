# s05：技能加载

`s01 > s02 > s03 > s04 > [ s05 ] s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"别把所有东西塞进系统提示词。按需加载。"* —— 两层注入节省 80% 以上的 token。
>
> **装置层**：`load_skill` 工具 —— 按需加载知识。

## 问题

你想让 Agent 了解专业主题（数据库迁移、Docker 部署、安全最佳实践），但把这些知识全部塞进系统提示词既浪费 token 又分散焦点。一个 10 技能的系统提示词在用户说话之前就会消耗 20,000 个 token。

## 方案

```
Layer 1（廉价，~100 tokens/技能）：        Layer 2（按需，~2000 tokens/技能）：
+---------------------------+              +---------------------------+
| 系统提示词                |              | load_skill("docker")      |
| 可用技能：                |   ------->   |   --> 完整技能正文        |
| - docker: 用 ... 部署    |              |   --> 通过 tool_result    |
| - security: 审计 ...     |              |       返回                |
| - testing: 配置 ...      |              +---------------------------+
+---------------------------+
  （始终加载）                               （仅在调用时加载）
```

技能名称进入系统提示词（廉价）。完整技能正文通过 `load_skill` 工具调用加载（昂贵，但只在需要时才触发）。

## 原理

1. **SkillLoader 扫描 `skills/*/SKILL.md` 文件。** 每个文件包含 YAML frontmatter，含名称、描述和触发条件：

```
skills/
  docker/SKILL.md       -- Docker 部署知识
  security/SKILL.md     -- 安全审计知识
  testing/SKILL.md      -- 测试配置知识
```

2. **Layer 1：描述注入系统提示词。** `SkillLoader.getDescriptions()` 返回所有技能的简短元数据：

```java
SkillLoader skillLoader = new SkillLoader(workDir.resolve("skills"));

String systemPrompt = "You are a coding agent.\n"
    + "Use load_skill to access specialized knowledge.\n\n"
    + "Skills available:\n" + skillLoader.getDescriptions();
```

注入到系统提示词的输出：
```
Skills available:
- docker: Deploy containers with Docker Compose and multi-stage builds
- security: Audit dependencies and fix OWASP Top 10 vulnerabilities
- testing: Configure JUnit 5, Mockito, and integration test suites
```

3. **Layer 2：按需加载完整内容。** 当模型调用 `load_skill("docker")` 时，完整技能正文通过 `tool_result` 返回：

```java
dispatcher.register("load_skill",
    input -> skillLoader.getContent((String) input.get("name")));
```

4. **Token 经济学。** 10 个技能的场景：Layer 1 花费 ~1,000 token（始终）。Layer 2 花费 ~2,000 token/技能，但只有实际使用的技能才加载（通常每次会话 1-2 个）。相比全部预先塞入，总节省 80% 以上。

## 变更对比

| 组件          | s04                 | s05                               |
|---------------|---------------------|-----------------------------------|
| 工具          | 5（父） + 4（子）   | +1：`load_skill`                  |
| 知识来源      | 仅系统提示词        | 两层：描述 + 按需加载             |
| 系统提示词    | 静态文本            | 静态 + `skillLoader.getDescriptions()` |
| 新增类        | （无）              | `SkillLoader`                     |

## 试一试

```sh
cd mini-agent-4j
mkdir -p skills/docker skills/testing
# 先在每个目录下创建 SKILL.md 文件
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S05SkillLoading"
```

1. `加载 docker 技能，告诉我如何部署这个项目`
2. `我需要写测试 —— 先加载 testing 技能`
3. `你有哪些可用的技能？`
