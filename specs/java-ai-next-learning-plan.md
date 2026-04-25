# Java + AI 后续学习计划（基于生产级开源项目）

> 适用前提：
> 你已经完整学完并亲自实现过 `mini-agent-4j` 这一套从 loop、tool use、todo、subagent、skill、memory、prompt、task、team、worktree、MCP 到 full agent 的渐进式课程。
>
> 下一阶段目标不再是“理解 agent 基本原理”，而是：
>
> 1. 进入 `Java + AI` 的工程化框架层
> 2. 阅读真正有生产味道的开源实现
> 3. 形成自己下一代 Java agent/runtime 的架构判断

## 1. 这一阶段到底要补什么

学完当前项目之后，最不值得做的事情，是继续停留在“再写一个小型 agent demo”。

下一阶段应该补的是下面四类能力：

1. **框架级抽象**
   `ChatClient`、`Advisors`、`Tool abstraction`、`Graph runtime`、`workflow`、`stateful execution`
2. **平台级能力**
   `observability`、`evaluation`、`admin console`、`prompt ops`、`MCP management`
3. **生产级业务场景**
   `Text-to-SQL`、`report generation`、`HITL`、`multi-agent orchestration`、`code execution sandbox`
4. **协议与基础设施**
   `MCP SDK`、client/server transport、reactive core、sync facade、安全边界

换句话说，你现在的学习重点应该从：

`Agent 怎么工作`

转移到：

`Java 里如何把 Agent 做成长期可维护、可观测、可扩展、可集成的工程系统`

## 2. 项目筛选标准

本计划只选满足以下条件的项目：

1. `Java` 是一等公民，不是 Python 项目的边缘实现
2. 有清晰的模块分层，不是纯 demo 仓库
3. 有明显的工程能力：workflow、runtime、MCP、RAG、evaluation、observability、admin 等至少占两项
4. 代码量和模块复杂度足够支撑“源码学习”，而不是只够看 README
5. 与你当前已经掌握的 agent/harness 知识能形成直接映射

## 3. 推荐项目清单

以下项目按“与你当前学习诉求的贴合度”排序。

### A. 第一优先级：必须读

#### 1. `alibaba/spring-ai-alibaba`

- 仓库：<https://github.com/alibaba/spring-ai-alibaba>
- 定位：Java Agentic AI 主线框架
- 为什么最值得读：
  - README 明确把自己定义为“production-ready framework for building Agentic, Workflow, and Multi-agent applications”
  - 它不是单点 API 封装，而是完整的 `Agent Framework + Graph runtime + Admin platform + Studio`
  - 内置 `Context Engineering`、`Human-in-the-loop`、`planning`、`tool retry`、`context compaction`
  - 有 `SequentialAgent`、`ParallelAgent`、`RoutingAgent`、`LoopAgent`
  - 有 graph-based workflow、A2A、MCP、可视化平台
- 对你最有价值的阅读点：
  - 你已经自己写过 session 级 runtime，这个项目能让你看到“如何把 session 思维升级成 framework/runtime/platform”
  - 重点不是学 API，而是学它的模块边界

#### 2. `spring-projects/spring-ai`

- 仓库：<https://github.com/spring-projects/spring-ai>
- 文档：<https://docs.spring.io/spring-ai/reference/>
- 定位：Spring 生态的 AI 工程基础框架
- 为什么必须读：
  - 它是 Java 企业 AI 开发最核心的基础层之一
  - 仓库 README 明确强调目标是把 Spring 的 portability、modularity、POJO 风格带入 AI 领域
  - 现在已经覆盖 `structured outputs`、`Advisors API`、`conversation memory`、`RAG`、`MCP`
- 对你最有价值的阅读点：
  - 看它如何抽象 `model provider`、`tool`、`memory`、`RAG`、`MCP`
  - 看“企业 Java 框架作者”如何定义稳定 API 边界
  - 这是你以后自己判断“该不该自己造框架”的基准线

#### 3. `modelcontextprotocol/java-sdk`

- 仓库：<https://github.com/modelcontextprotocol/java-sdk>
- 文档：<https://modelcontextprotocol.github.io/java-sdk/>
- 定位：官方 Java MCP SDK
- 为什么必须读：
  - 你已经学过并实现过 MCP 概念，这个仓库是最适合做“自实现 vs 官方 SDK”对照的标杆
  - 仓库 README 明确强调：
    - 它是官方 Java SDK
    - 维护上与 Spring AI 协作
    - 内部采用 reactive core，同时提供 synchronous facade
- 对你最有价值的阅读点：
  - 传输层怎么抽象
  - client/server API 怎么设计
  - reactive core 和同步 facade 怎么共存
  - SDK 如何在“规范”与“Java 工程现实”之间取平衡

### B. 第二优先级：看真实产品化实现

#### 4. `spring-ai-alibaba/AssistantAgent`

- 仓库：<https://github.com/spring-ai-alibaba/AssistantAgent>
- 定位：企业级 assistant agent 框架
- 为什么非常契合你：
  - 它不是普通 chat assistant，而是显式往“enterprise-grade intelligent assistant framework”走
  - 有明显的生产化特征：
    - `Code-as-Action`
    - `GraalVM polyglot sandbox`
    - `Evaluation Graph`
    - `Dynamic Prompt Builder`
    - `Unified Experience System`
    - experience management / skill conversion / multi-channel reply
- 对你最有价值的阅读点：
  - 你自己做过 coding-agent 思维模型，这个项目能让你继续往“代码执行型 agent”方向深挖
  - 它把 prompt、evaluation、experience、knowledge、tool orchestration 明显拆成了多个模块，这是你后续非常值得借鉴的结构

#### 5. `spring-ai-alibaba/DataAgent`

- 仓库：<https://github.com/spring-ai-alibaba/DataAgent>
- 定位：企业级智能数据分析 Agent
- 为什么非常值得读：
  - 这是一个非常典型的“AI 进入真实业务域”的案例
  - README 直接把核心能力写得很清楚：
    - `Text-to-SQL`
    - `Python 深度分析`
    - `HTML/Markdown 报告`
    - `Human-in-the-loop`
    - `RAG`
    - `MCP Server`
    - `API Key 管理`
  - 它比“通用聊天助手”更像真正的业务生产系统
- 对你最有价值的阅读点：
  - agent 如何落到垂直业务域
  - workflow 如何服务业务目标，而不是只服务通用对话
  - 如何把 `planning + execution + reporting + approval + API governance` 串起来

#### 6. `spring-ai-alibaba/deepresearch`

- 仓库：<https://github.com/spring-ai-alibaba/deepresearch>
- 定位：基于 Spring AI Alibaba Graph 的 Deep Research 系统
- 为什么值得读：
  - 它比 DataAgent 更偏“研究型 agent 产品”
  - 有完整架构、主流程、Docker 运行、Langfuse observability
  - 适合看“复杂多步研究流程”在 Java 中如何落地
- 对你最有价值的阅读点：
  - plan-and-execute / reflection / hybrid retrieval 的代码组织方式
  - 如何把 UI、后端、工具、配置、中间件一起组织成完整系统

### C. 第三优先级：做生态对照，而不是做主线

#### 7. `langchain4j/langchain4j`

- 仓库：<https://github.com/langchain4j/langchain4j>
- 文档：<https://docs.langchain4j.dev/>
- 定位：Java 世界另一个主流 LLM 应用开发库
- 为什么推荐，但不作为第一主线：
  - 它的基础抽象做得很强：
    - unified model APIs
    - tools
    - chat memory
    - RAG
    - MCP
    - agents
  - 但官方文档也明确写了：`langchain4j-agentic` 整体仍然是 experimental
- 正确用法：
  - 用它做“抽象对照”
  - 不要急着把它当成你下一阶段唯一的架构答案

#### 8. `quarkiverse/quarkus-langchain4j`

- 仓库：<https://github.com/quarkiverse/quarkus-langchain4j>
- 定位：Quarkus 阵营的 LangChain4j 企业集成层
- 为什么只作为补充：
  - 很有工程味，有 `tool`、`embedding`、`document store`、`native compilation`、`observability`
  - 但它更适合做“另一种 Java AI 工程化路线”的比较样本
  - 如果你当前主战场还是 Spring Boot，这个项目不应优先于前面几项

## 4. 推荐阅读顺序

不要按“哪个 repo 最火”读，也不要先看最炫的多 agent 产品。

最稳的顺序是：

1. `spring-projects/spring-ai`
2. `alibaba/spring-ai-alibaba`
3. `modelcontextprotocol/java-sdk`
4. `spring-ai-alibaba/AssistantAgent`
5. `spring-ai-alibaba/DataAgent`
6. `spring-ai-alibaba/deepresearch`
7. `langchain4j/langchain4j`
8. `quarkiverse/quarkus-langchain4j`（可选）

理由：

- 先读基础框架，建立抽象层认知
- 再读 Agent runtime/framework，建立 orchestration 认知
- 再读协议基础设施，补 MCP 工程能力
- 最后读产品型项目，理解这些能力在业务里怎么落地

## 5. 6 周学习路线

这个路线默认你已经不是初学者，因此每周都必须有“源码阅读 + 设计总结 + 小实现”三个动作。

### Week 1：Spring AI 抽象层

目标：

- 弄清 Spring AI 的核心抽象，而不是停留在 starter 使用层

重点仓库：

- `spring-projects/spring-ai`

重点问题：

1. `ChatClient` 和 model abstraction 的边界是什么
2. `Advisors` 是怎样插入请求/响应链路的
3. `Tool calling`、`memory`、`RAG`、`MCP` 在框架里分别落在哪一层
4. 哪些部分是 Spring 特有的，哪些部分是通用 AI runtime 抽象

本周产出：

- 一篇你自己的架构笔记：
  `Spring AI 的核心抽象地图`
- 一张模块图：
  `model / chat / advisor / tool / rag / mcp`

### Week 2：Spring AI Alibaba 框架层

目标：

- 弄清“Agent Framework + Graph runtime + Admin platform”是如何拆分的

重点仓库：

- `alibaba/spring-ai-alibaba`

重点问题：

1. Agent Framework 和 Graph 的职责边界是什么
2. `SequentialAgent`、`ParallelAgent`、`RoutingAgent`、`LoopAgent` 的抽象是否合理
3. `Context Engineering` 是 runtime policy 还是 prompt policy
4. admin/studio 为什么被做成独立模块

本周产出：

- 一篇对照笔记：
  `mini-agent-4j -> Spring AI Alibaba 的映射关系`
- 一张 runtime 图：
  `session model -> framework model -> graph model`

### Week 3：MCP 基础设施

目标：

- 从“懂 MCP 概念”升级到“能评价一个 MCP Java SDK 的设计”

重点仓库：

- `modelcontextprotocol/java-sdk`

重点问题：

1. reactive core 的必要性在哪里
2. 为什么要提供 synchronous facade
3. client/server 的抽象有没有过度设计
4. 与 Spring AI 的集成边界怎样划分

本周产出：

- 一篇设计对照：
  `我自己的 MCP 实现 vs 官方 Java SDK`
- 一个小实验：
  用官方 SDK 做一个最小 MCP client 或 server

### Week 4：AssistantAgent

目标：

- 学习“企业级 assistant agent”怎样组织 prompt、experience、evaluation、sandbox

重点仓库：

- `spring-ai-alibaba/AssistantAgent`

重点问题：

1. `Code-as-Action` 为什么比“纯预定义工具调用”更强，也更危险
2. `Evaluation Graph` 是分类器、路由器还是策略系统
3. `Experience System` 和你已经学过的 `skills/memory` 有什么本质差别
4. `GraalVM sandbox` 如何参与 agent architecture，而不是只是执行器

本周产出：

- 一篇模块解剖：
  `AssistantAgent 的 5 个核心模块`
- 一个仿写任务：
  从中挑一个能力，在自己的实验仓库里做最小复刻

### Week 5：DataAgent

目标：

- 学习 agent 如何进入垂直业务域，并承担完整业务流程

重点仓库：

- `spring-ai-alibaba/DataAgent`

重点问题：

1. `Text-to-SQL` 如何与 `RAG`、metadata、plan、approval 结合
2. Python 执行器为什么要单独存在
3. 报告生成为什么是 agent 产品里很重要的一环
4. MCP Server 在这个项目里承担的是“扩展能力”还是“产品边界”

本周产出：

- 一篇业务架构笔记：
  `企业数据分析 Agent 的最小正确架构`
- 一个设计草图：
  假设你自己做一个 Java AI 业务项目，哪些能力要直接照搬，哪些不要

### Week 6：DeepResearch + LangChain4j 对照

目标：

- 做一轮横向架构判断，而不是只会沿一条技术栈往前走

重点仓库：

- `spring-ai-alibaba/deepresearch`
- `langchain4j/langchain4j`
- 可选：`quarkiverse/quarkus-langchain4j`

重点问题：

1. DeepResearch 这种“多步研究系统”在 Java 里应该走 graph runtime，还是 agentic library
2. LangChain4j 哪些抽象很优雅，哪些在你的语境里不够稳
3. Spring AI 体系和 LangChain4j 体系，哪个更适合作为你以后长期积累的主线

本周产出：

- 一份 ADR：
  `为什么我后续主线选择 Spring AI / Spring AI Alibaba / LangChain4j`
- 一份下一阶段仓库规划：
  `我自己的 Java AI 实验仓库 2.0`

## 6. 这个阶段最好的“实战项目”

只读源码不够，建议你并行做一个新仓库。

建议项目名：

- `java-ai-runtime-lab`
- 或 `agent-platform-lab`

建议目标：

> 用 Spring Boot 做一个“生产味道”的 Java AI 系统，不再只是课堂式 session。

建议最小范围：

1. 一个统一的 `chat / tool / workflow` 接口层
2. MCP client + MCP server 至少各一个
3. 一套最小 `RAG + metadata + evaluator`
4. 一套 `task / workflow / approval` 机制
5. 一套 `trace / prompt log / evaluation result` 观测能力
6. 一个垂直场景
   例如：
   - code assistant
   - data analyst
   - knowledge ops assistant
   - internal support copilot

这个项目的目标不是“上线产品”，而是帮你把本计划读到的框架和产品设计重新吸收一遍。

## 7. 不建议现在优先做的事

### 1. 不要继续刷通用 agent 理论

你已经有足够的 agent runtime 基础。

接下来更值钱的是工程设计，不是继续看新的 prompt 技巧或 agent 名词。

### 2. 不要把重心放在纯 Python 框架

可以关注，但不建议作为主线。

你当前最强的壁垒是：

`Java 视角下，真正理解 AI runtime 与工程系统的结合`

### 3. 不要一开始就追求“大而全平台”

现在更重要的是建立“判断力”：

- 哪些模块必须存在
- 哪些模块可以延后
- 哪些模块适合框架化
- 哪些模块应该保持业务内聚

## 8. 最终建议：主线与副线

### 主线

- `Spring AI`
- `Spring AI Alibaba`
- `MCP Java SDK`

这是你最该重点投入的主线。

### 副线

- `AssistantAgent`
- `DataAgent`
- `deepresearch`

这是“产品级实现参考线”。

### 对照线

- `LangChain4j`
- `Quarkus LangChain4j`

这是用来帮你做架构判断的，不应喧宾夺主。

## 9. 如果只允许选 3 个项目

如果时间非常有限，只看下面 3 个：

1. `alibaba/spring-ai-alibaba`
2. `modelcontextprotocol/java-sdk`
3. `spring-ai-alibaba/DataAgent`

原因：

- 第一个给你框架级 agent/runtime/platform 视角
- 第二个给你协议基础设施视角
- 第三个给你业务生产实现视角

这三者组合起来，足够把你从“写过教学 agent”推进到“能设计 Java AI 工程系统”。

## 10. 信息校验时间与来源

本计划基于 2026-04-25 对以下官方仓库 / 官方文档的交叉查阅：

- Spring AI
  - <https://github.com/spring-projects/spring-ai>
  - <https://docs.spring.io/spring-ai/reference/>
- Spring AI Alibaba
  - <https://github.com/alibaba/spring-ai-alibaba>
- AssistantAgent
  - <https://github.com/spring-ai-alibaba/AssistantAgent>
- DataAgent
  - <https://github.com/spring-ai-alibaba/DataAgent>
- DeepResearch
  - <https://github.com/spring-ai-alibaba/deepresearch>
- MCP Java SDK
  - <https://github.com/modelcontextprotocol/java-sdk>
  - <https://modelcontextprotocol.github.io/java-sdk/>
- LangChain4j
  - <https://github.com/langchain4j/langchain4j>
  - <https://docs.langchain4j.dev/tutorials/agents>
- Quarkus LangChain4j
  - <https://github.com/quarkiverse/quarkus-langchain4j>

---

如果要继续执行，本计划的下一步不是再加项目，而是从第 1 周开始真正做：

1. 读 `spring-projects/spring-ai`
2. 画自己的抽象图
3. 开新实验仓库
4. 每周沉淀一篇架构笔记
