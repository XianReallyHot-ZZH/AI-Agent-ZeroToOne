# s03: TodoWrite (会话内规划)

`s00 > s01 > s02 > [ s03 ] > s04 > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *计划不是替模型思考，而是把"正在做什么"明确写出来。*

## 这一章要解决什么问题

到了 `s02`，agent 已经会读文件、写文件、跑命令。

问题也马上出现了：

- 多步任务容易走一步忘一步
- 明明已经做过的检查，会重复再做
- 一口气列出很多步骤后，很快又回到即兴发挥

这是因为模型虽然"能想"，但它的当前注意力始终受上下文影响。
如果没有一块**显式、稳定、可反复更新**的计划状态，大任务就很容易漂。

所以这一章要补上的，不是"更强的工具"，而是：

**让 agent 把当前会话里的计划外显出来，并且持续更新。**

## 先解释几个名词

### 什么是会话内规划

这里说的规划，不是长期项目管理，也不是磁盘上的任务系统。

它更像：

> 为了完成当前这次请求，先把接下来几步写出来，并在过程中不断更新。

### 什么是 todo

`todo` 在这一章里只是一个载体。

你不要把它理解成"某个特定产品里的某个工具名"，更应该把它理解成：

> 模型用来写入当前计划的一条入口。

### 什么是 active step

`active step` 可以理解成"当前正在做的那一步"。

教学版里我们用 `in_progress` 表示它。
这么做的目的不是形式主义，而是帮助模型维持焦点：

> 同一时间，先把一件事做完，再进入下一件。

### 什么是提醒

提醒不是替模型规划，而是当它连续几轮都忘记更新计划时，轻轻拉它回来。

## 先立清边界：这章不是任务系统

这是这一章最重要的边界。

`s03` 讲的是：

- 当前会话里的轻量计划
- 用来帮助模型聚焦下一步
- 可以随任务推进不断改写

它**不是**：

- 持久化任务板
- 依赖图
- 多 agent 共用的工作图
- 后台运行时任务管理

这些会在 `s12-s14` 再系统展开。

如果你现在就把 `s03` 讲成完整任务平台，初学者会很快混淆：

- "当前这一步要做什么"
- "整个系统长期还有哪些工作项"

## 最小心智模型

把这一章先想成一个很简单的结构：

```text
用户提出大任务
   |
   v
模型先写一份当前计划
   |
   v
计划状态
  - [ ] 还没做
  - [>] 正在做
  - [x] 已完成
   |
   v
每做完一步，就更新计划
```

更具体一点：

```text
1. 先拆几步
2. 选一项作为当前 active step
3. 做完后标记 completed
4. 把下一项改成 in_progress
5. 如果好几轮没更新，系统提醒一下
```

这就是最小版本最该教清楚的部分。

## 关键数据结构

### 1. PlanItem

Java 实现里用私有静态内部类表示最小条目：

```java
private static class PlanItem {
    final String content;    // 这一步要做什么
    final String status;     // pending / in_progress / completed
    final String activeForm; // 可选：正在进行中时的自然语言描述
}
```

这里的字段分别表示：

- `content`：这一步要做什么
- `status`：这一步现在处在什么状态
- `activeForm`：当它正在进行中时，可以用更自然的进行时描述

### 2. PlanningState

除了计划条目本身，还应该有一点最小运行状态：

```java
private static class TodoManager {
    List<PlanItem> items = new ArrayList<>();
    int roundsSinceUpdate = 0;
}
```

`roundsSinceUpdate` 的意思很简单：

> 连续多少轮过去了，模型还没有更新这份计划。

### 3. 状态约束

教学版推荐先立一条简单规则：

```text
同一时间，最多一个 in_progress
最多 12 项（保持计划精简）
```

这不是宇宙真理。
它只是一个非常适合初学者的教学约束：

**强制模型聚焦当前一步。**

## 最小实现

### 第一步：准备一个计划管理器

```java
private static class TodoManager {
    private List<PlanItem> items = new ArrayList<>();
    private int roundsSinceUpdate = 0;
}
```

### 第二步：允许模型整体更新当前计划

```java
public String update(List<?> rawItems) {
    // 最多 12 项，保持计划精简
    if (rawItems.size() > 12) {
        throw new IllegalArgumentException(
            "Keep the session plan short (max 12 items)");
    }

    List<PlanItem> normalized = new ArrayList<>();
    int inProgressCount = 0;

    for (int i = 0; i < rawItems.size(); i++) {
        Map<String, Object> raw = (Map<String, Object>) rawItems.get(i);
        String content = String.valueOf(raw.getOrDefault("content", "")).trim();
        String status  = String.valueOf(raw.getOrDefault("status", "pending")).toLowerCase();
        String activeForm = String.valueOf(raw.getOrDefault("activeForm", "")).trim();

        // 校验：content 不能为空
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Item " + i + ": content required");
        }
        // 校验：status 必须是合法值
        if (!Set.of("pending", "in_progress", "completed").contains(status)) {
            throw new IllegalArgumentException(
                "Item " + i + ": invalid status '" + status + "'");
        }
        if ("in_progress".equals(status)) inProgressCount++;
        normalized.add(new PlanItem(content, status, activeForm));
    }

    // 最多 1 个 in_progress（保持聚焦）
    if (inProgressCount > 1) {
        throw new IllegalArgumentException(
            "Only one plan item can be in_progress");
    }

    this.items = normalized;
    this.roundsSinceUpdate = 0;  // 模型刚刚更新了计划
    return render();
}
```

教学版让模型"整份重写"当前计划，比做一堆局部增删改更容易理解。

### 第三步：把计划渲染成可读文本

```java
public String render() {
    if (items.isEmpty()) return "No session plan yet.";

    List<String> lines = new ArrayList<>();
    for (PlanItem item : items) {
        String marker = switch (item.status) {
            case "pending"     -> "[ ]";
            case "in_progress" -> "[>]";
            case "completed"   -> "[x]";
            default            -> "[?]";
        };
        String line = marker + " " + item.content;
        if ("in_progress".equals(item.status) && !item.activeForm.isEmpty()) {
            line += " (" + item.activeForm + ")";
        }
        lines.add(line);
    }

    long completed = items.stream()
        .filter(item -> "completed".equals(item.status)).count();
    lines.add("\n(" + completed + "/" + items.size() + " completed)");
    return String.join("\n", lines);
}
```

### 第四步：把 `todo` 接成一个工具

```java
// 工具定义
defineTool("todo", "Rewrite the current session plan for multi-step work.",
    Map.of("items", Map.of(
        "type", "array",
        "items", Map.of(
            "type", "object",
            "properties", Map.of(
                "content",   Map.of("type", "string"),
                "status",    Map.of("type", "string",
                    "enum", List.of("pending", "in_progress", "completed")),
                "activeForm", Map.of("type", "string",
                    "description", "Optional present-continuous label.")),
            "required", List.of("content", "status")))),
    List.of("items"))

// 工具处理器
toolHandlers.put("todo", input -> {
    List<?> items = (List<?>) input.get("items");
    if (items == null) return "Error: items is required";
    return todo.update(items);
});
```

### 第五步：如果连续几轮没更新计划，就提醒

```java
// 在 agentLoop 中，每轮工具执行后：
if (usedTodo) {
    todo.resetRoundCounter();
} else {
    todo.noteRoundWithoutUpdate();
    String reminder = todo.reminder();
    if (reminder != null) {
        toolResults.add(0, ContentBlockParam.ofText(
            TextBlockParam.builder()
                .text(reminder).build()));
    }
}
```

提醒的生成逻辑：

```java
public String reminder() {
    if (items.isEmpty()) return null;
    if (roundsSinceUpdate < PLAN_REMINDER_INTERVAL) return null;  // 3 轮
    boolean hasOpen = items.stream()
        .anyMatch(item -> !"completed".equals(item.status));
    if (!hasOpen) return null;
    return "<reminder>Refresh your current plan before continuing.</reminder>";
}
```

这一步的核心意义不是"催促"本身，而是：

> 系统开始把"计划状态是否失活"也看成主循环的一部分。

## 它如何接到主循环里

这一章以后，主循环不再只维护：

- `messages`

还开始维护一份额外的会话状态：

- `PlanningState`

也就是说，agent loop 现在不只是在"对话"。

它还在维持一块当前工作面板：

```text
messages          -> 模型看到的历史
planning state    -> 当前计划的显式外部状态
```

这就是这一章真正想让你学会的升级：

**把"当前要做什么"从模型脑内，移到系统可观察的状态里。**

## 为什么这章故意不讲成任务图

因为这里的重点是：

- 帮模型聚焦下一步
- 让当前进度变得外显
- 给主循环一个"过程性状态"

而不是：

- 任务依赖
- 长期持久化
- 多人协作任务板
- 后台运行槽位

如果你已经开始关心这些问题，说明你快进入：

- `s12-task-system.md`
- `s13a-runtime-task-model.md`

## 初学者最容易犯的错

### 1. 把计划写得过长

计划不是越多越好。

如果一上来列十几步，模型很快就会失去维护意愿。
本实现硬性限制最多 12 项，就是为了防止这种情况。

### 2. 不区分"当前一步"和"未来几步"

如果同时有很多个 `in_progress`，焦点就会散。

### 3. 把会话计划当成长期任务系统

这会让 `s03` 和 `s12` 的边界完全混掉。

### 4. 只在开始时写一次计划，后面从不更新

那这份计划就失去价值了。

### 5. 以为 reminder 是可有可无的小装饰

不是。

提醒机制说明了一件很重要的事：

> 主循环不仅要执行动作，还要维护动作过程中的结构化状态。

## 教学边界

这一章讲的是：

**会话里的外显计划状态。**

它还不是后面那种持久任务系统，所以边界要守住：

- 这里的 `todo` 只服务当前会话，不负责跨阶段持久化
- `{content, status, activeForm}` 这种小结构已经够教会核心模式
- reminder 直接一点没问题，重点是让模型持续更新计划

这一章真正要让读者看见的是：

**当计划进入结构化状态，而不是散在自然语言里时，agent 的漂移会明显减少。**

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S03TodoWrite"
```

### 案例 1：多文件创建（基础规划）

> 让模型用 todo 拆分一个多步文件创建任务，观察计划如何随步骤推进更新。

```
在当前目录创建一个 calc 项目：calc/Add.java 实现加法，calc/Sub.java 实现减法，calc/Main.java 调用前两个类并打印 3+2 和 3-2 的结果，然后编译运行验证结果
```

观察要点：
- 模型是否在开始时创建 todo 列表（3-4 项）
- 每完成一步是否把 pending → completed，下一步 → in_progress
- 日志中 `todo(3 items, in_progress: "...")` 的变化

### 案例 2：代码审查 + 修复（读-分析-改循环）

> 让模型先阅读代码、定位问题、再修复，体验"先规划后执行"的节奏。

先准备一个有 bug 的文件：
```
创建 bug_demo.java，内容为：public class bug_demo { public static void main(String[] args) { int x = 10 / 0; System.out.println(x); } }
```

然后让它修复：
```
阅读 bug_demo.java，找出运行时会出错的地方，修复它，然后编译运行验证修复成功
```

观察要点：
- 模型是否先用 todo 规划"阅读 → 定位 → 修复 → 验证"的步骤
- 计划是否在每步完成后更新

### 案例 3：重构任务（多步编辑 + Nag Reminder 触发）

> 一个足够长的任务，可能触发 Nag Reminder（连续 3 轮未更新 todo 时的催促）。

```
在当前目录创建一个 shapes.txt 文件，里面写 5 个形状的名称和面积公式（圆形、矩形、三角形、梯形、椭圆），每行一个。然后创建一个 Java 程序 ShapeCalculator.java，为每个形状写一个计算面积的方法，在 main 方法中用具体数值测试所有形状，编译运行并展示结果
```

观察要点：
- 这是一个 5+ 步骤的任务，模型是否拆分成合理的计划
- 如果模型中间连续执行工具而忘记更新 todo，是否看到 `<reminder>` 催促
- 催促后模型是否会刷新计划

### 案例 4：自由探索（对比有无 todo 的差异）

> 不给具体指令，让模型自主规划一个复杂任务，验证 todo 的规划能力。

```
帮我搭建一个简单的 Java 单元测试框架，包含：一个 TestRunner 类（运行测试并报告结果），一个 @Test 注解，一个 AssertionError 类，以及一个使用示例 ExampleTest.java。最后运行示例验证一切工作正常
```

观察要点：
- 模型如何将复杂需求拆解为 todo 项
- activeForm 是否在 in_progress 项上显示（如 "Writing TestRunner"）
- 最终 `(N/M completed)` 的进度是否准确

## 一句话记住

**`s03` 的 todo，不是任务平台，而是当前会话里的"外显计划状态"。**
