# 生产级 Java + AI 开源后端项目架构提炼表

> 更新时间：2026-04-25
>
> 目标不是列一个“AI 项目清单”，而是回答两个更实际的问题：
>
> 1. 企业级 `Java + AI` 后端产品现在大致长什么样？
> 2. 如果我要自己做一个企业级平台应用，哪些架构模块应该优先借鉴？

## 1. 使用方式

这份文档按“**产品形态**”而不是“框架热度”来整理。

建议阅读顺序：

1. 先看“横向架构提炼表”，建立产品全景
2. 再看“项目分组建议”，决定先读哪几类
3. 最后看“提炼出的通用后端骨架”，把参考项目转成你自己的架构语言

如果你现在最缺的是：

- **产品感觉**：先看 `JeecgBoot`、`RuoYi AI`、`KMatrix-service`
- **AI-native 业务实现**：再看 `DataAgent`、`deepresearch`
- **平台内核与运行时**：最后看 `spring-ai-alibaba`、`Lynxe`、`app-platform`、`fit-framework`

## 2. 项目分组

### A. 企业平台型

- `jeecgboot/JeecgBoot`
- `ageerle/ruoyi-ai`
- `mahoneliu/KMatrix-service`

特点：

- 有完整后台平台壳
- 有账号、权限、配置、知识库、流程、模型管理等典型企业功能
- 更接近“一个可落地的业务平台”而不是“AI demo”

### B. AI-native 业务型

- `spring-ai-alibaba/DataAgent`
- `spring-ai-alibaba/deepresearch`

特点：

- 业务目标非常明确
- agent/workflow 不是装饰，而是核心执行链路
- 更适合学习 AI 业务系统如何拆分执行链、审批链、报告链

### C. 平台内核 / 运行时型

- `alibaba/spring-ai-alibaba`
- `spring-ai-alibaba/Lynxe`
- `ModelEngine-Group/app-platform`
- `ModelEngine-Group/fit-framework`

特点：

- 强调 framework、workflow runtime、graph、plugin、studio、low-code、long-running agents
- 更适合学习“如何做平台”，不只是“如何做一个业务功能”

## 3. 横向架构提炼表

### 3.1 总表

| 项目 | 产品形态 | 后端主形态 | AI 核心能力 | 工作流 / Agent 运行时 | 知识库 / RAG | MCP / 插件 / 扩展 | 平台治理能力 | 你最该借鉴什么 | 风险 / 注意点 |
|---|---|---|---|---|---|---|---|---|---|
| `JeecgBoot` | AI 驱动低代码平台 | 传统企业平台 + AI 模块中心 | AI 聊天助手、AI 模型、知识库、AI 流程编排 | 有流程编排与低代码场景，不是纯 agent runtime 项目 | 有知识库能力 | 有 MCP 与插件体系 | 权限、流程、低代码、企业后台壳完整 | “AI 能力如何嵌进成熟企业平台” | AI 本身不是唯一主角，别把它误读成纯 AI runtime 样板 |
| `RuoYi AI` | 全栈 AI 开发平台 | Spring Boot 后端 + 实时通信 + 向量库集成 | Spring AI + LangChain4j、文档解析、图像分析 | 偏 AI 助手平台，支持 SSE / WebSocket，偏服务编排 | 支持向量库（Milvus / Weaviate / Qdrant） | README 未把 MCP 作为主轴强调 | 有 Sa-Token + JWT、日志、性能监控、健康检查 | “AI 助手平台”应有哪些平台级模块 | 公开 issue 中出现过安全问题，适合学结构，不适合照搬实现 |
| `KMatrix-service` | LLM Workflow + RAG 平台 | 前后端分离 + Maven 多模块 + RuoYi 风格后台 | LangChain4j + LangGraph4j | workflow 是主轴，且显式前后端配套 | 强调知识库系统，PostgreSQL/pgvector 推荐 | 扩展性较强，但 README 中插件体系不是主角 | Sa-Token(JWT)、Redis、数据库迁移、后台模块化清晰 | “Java 版 Dify / MaxKB 风格后端”如何拆模块 | 项目较新，适合学架构，不要预设其成熟度等同老牌平台 |
| `DataAgent` | 企业数据分析 Agent | 管理端 + 核心能力模块 + 公共模块 | 自然语言转 SQL、Python 深度分析、报告生成 | 明显是业务执行链：查询、分析、报告、HITL | 有 RAG 能力 | 有 MCP Server | 有 API Key 管理、管理端、业务流程治理味道 | “AI-native 业务系统”如何围绕目标设计 | 它更像垂直业务系统，不是通用平台 |
| `deepresearch` | 深度研究 / 多步推理系统 | 后端 + 前端 + Docker + tools + middleware | Plan-and-execute、反思、混合检索、记忆 | graph 驱动的多步执行链非常明显 | 支持 ES 向量检索、知识库 | MCP 服务接入在推进中 | 有 Langfuse observability、Docker 化部署 | “复杂研究型 agent 后端”怎么落地 | 更偏研究型系统，某些功能仍在演进 |
| `spring-ai-alibaba` | Agentic AI Framework + 平台 | framework + graph runtime + admin/studio | 多 Agent、上下文工程、语音、多模态 | 这是核心：graph、stateful、long-running、workflow | 通过 Spring AI / extensions 对接 | 明确支持 MCP | admin 支持 observability、evaluation、MCP management | “平台和运行时如何解耦” | 偏框架和平台，不是直接业务产品 |
| `Lynxe` | 高确定性、多 Agent、“Prompt Programming”工作站 | 可独立运行，也可 HTTP 服务集成 | 纯 Java 多 Agent、Func-Agent、高确定性执行 | 多 Agent 与 HTTP 服务结合紧密 | README 没把知识库作为主轴 | 原生支持 MCP | 可作为现成服务嵌入业务项目 | “可集成的 Java Agent Service” 怎么做 | 它更偏 agent service，不是传统后台平台 |
| `app-platform` | 大模型应用开发平台 | 平台化、低代码、声明式配置 | 面向 AI 应用全流程开发 | 强依赖平台型编排与低代码能力 | README 公开信息未突出某一特定向量栈 | 强调插件化与平台可扩展性 | 重点在应用开发平台，而非单个 AI 应用 | “AI 应用开发平台”如何产品化 | 公开 README 更偏平台定位，细节需要深入源码补足 |
| `fit-framework` | 企业级 AI 开发框架 | 框架内核，不是终端产品 | FEL、函数引擎、AI 原语封装 | WaterFlow 是核心编排引擎 | 提供检索、知识库抽象能力 | 插件热插拔很强 | 原生 / Spring 双模、聚散部署 | “平台内核”如何支持业务平台 | 它是内核层，不能直接等价于产品后端 |

### 3.2 维度细看

#### 认证 / 权限 / 平台壳

| 项目 | 提炼 |
|---|---|
| `JeecgBoot` | 这是最强的“平台壳参考”，适合学权限、菜单、流程、配置、低代码、AI 模块如何共存 |
| `RuoYi AI` | 明确采用 `Sa-Token + JWT`，是典型 Java 后台认证方案 |
| `KMatrix-service` | 明确采用 `Sa-Token(JWT)`，且和 workflow / RAG 共存，适合作为 AI 平台后台样板 |
| `DataAgent` | 更偏业务管理与 API Key 治理，不是传统权限平台样板 |
| `deepresearch` | 更像研究系统，治理面比业务平台壳弱一些 |

#### 模型层 / AI 基础抽象

| 项目 | 提炼 |
|---|---|
| `RuoYi AI` | 典型“Spring AI + LangChain4j 混用”路线 |
| `KMatrix-service` | `LangChain4j + LangGraph4j` 明确承担 AI 与 workflow 主角色 |
| `spring-ai-alibaba` | 更偏“框架化抽象”，不只是调模型，而是把模型、工具、MCP、上下文工程都纳入统一控制面 |
| `fit-framework` | 目标是做 Java 生态自己的 AI 原语层与编排层 |

#### 工作流 / Agent Runtime

| 项目 | 提炼 |
|---|---|
| `KMatrix-service` | 很适合学“后台平台 + workflow designer + AI 执行”的组合 |
| `deepresearch` | 很适合学“多步研究型 agent runtime” |
| `spring-ai-alibaba` | 是这一维度里最完整的运行时参考：graph、long-running、stateful、multi-agent |
| `Lynxe` | 更适合学“多 Agent 服务如何暴露成 HTTP 能力供业务系统集成” |
| `fit-framework` | WaterFlow 适合作为你未来评估“是否需要自研编排内核”的参考标杆 |

#### 知识库 / RAG

| 项目 | 提炼 |
|---|---|
| `JeecgBoot` | 有知识库，但不是它唯一主线 |
| `RuoYi AI` | 明确把向量库和文档解析纳入平台 |
| `KMatrix-service` | 是知识库 + workflow 双主线，适合重点看 |
| `DataAgent` | 知识库能力是为数据分析业务服务，不是独立的“知识库产品” |
| `deepresearch` | 混合检索与知识库更偏研究型应用 |

#### MCP / 插件 / 可扩展性

| 项目 | 提炼 |
|---|---|
| `JeecgBoot` | MCP 与插件体系被放到了平台层，说明它把 AI 扩展能力视为产品能力而不是技术附属 |
| `DataAgent` | MCP Server 被纳入业务系统 |
| `deepresearch` | MCP 在演进中，适合观察外部工具如何接入研究型系统 |
| `spring-ai-alibaba` | MCP 是平台主能力之一，且与 admin / runtime 一起治理 |
| `Lynxe` | 原生 MCP 支持，适合看 agent service 的扩展边界 |
| `fit-framework` / `app-platform` | 更偏插件化、平台化扩展，而不是只强调 MCP 协议 |

#### 可观测性 / 运维 / 评测

| 项目 | 提炼 |
|---|---|
| `RuoYi AI` | 明确强调日志、监控、健康检查，具备传统后台产品味道 |
| `deepresearch` | 明确接入 `Langfuse observability`，适合学 AI 系统观测 |
| `spring-ai-alibaba` | admin 平台明确支持 `observability`、`evaluation`、`MCP management` |
| `JeecgBoot` | 平台治理能力强，但 AI 专项观测不一定是最突出的公开主轴 |

## 4. 这些项目共同透露出的“企业级 Java + AI 后端骨架”

如果把这些项目的共性提炼出来，一个比较稳的企业级 `Java + AI` 后端，大概率应拆成下面 6 层。

### 4.1 接入层（Access Layer）

职责：

- 用户认证
- 权限控制
- API Gateway / BFF
- HTTP / SSE / WebSocket
- 多端接入（Web、管理台、工作台）

这一层主要参考：

- `JeecgBoot`
- `RuoYi AI`
- `KMatrix-service`

你需要记住：

> 企业级 AI 后端不是从 Chat API 开始，而是从“谁能访问、通过什么协议访问、带着什么身份访问”开始。

### 4.2 平台控制层（Platform Control Plane）

职责：

- 模型配置
- Prompt 模板管理
- Agent / Workflow 定义管理
- 应用管理
- MCP / 插件注册
- API Key / Provider 管理
- 版本与发布

这一层主要参考：

- `JeecgBoot`
- `spring-ai-alibaba`
- `app-platform`
- `DataAgent`

你需要记住：

> 生产系统里，“模型调用”只是执行面；模型配置、工具注册、应用配置、流程版本，属于控制面。

### 4.3 AI 执行层（AI Runtime Plane）

职责：

- Chat / Tool / Agent / Workflow runtime
- 状态机与长流程执行
- 任务编排与路由
- 多 Agent 协作
- 上下文策略、重试、限流、审批、HITL

这一层主要参考：

- `spring-ai-alibaba`
- `deepresearch`
- `Lynxe`
- `fit-framework`
- `KMatrix-service`

你需要记住：

> 你现在最需要补的，不是“怎么调用模型”，而是“怎么让 AI 执行链在后端长期稳定运行”。

### 4.4 知识与数据层（Knowledge & Data Plane）

职责：

- 文档导入与解析
- Chunk / embedding / metadata
- 向量检索
- 结构化数据库访问
- Text-to-SQL
- 业务数据聚合

这一层主要参考：

- `RuoYi AI`
- `KMatrix-service`
- `DataAgent`
- `deepresearch`

你需要记住：

> 企业 AI 平台很少只有向量库，它通常同时有：结构化数据库、对象存储、向量库、缓存、甚至搜索引擎。

### 4.5 业务编排层（Business Orchestration Layer）

职责：

- 报告生成
- 分析链路
- 审批链
- 人机协同
- 定时任务
- 后台任务
- 业务动作编排

这一层主要参考：

- `DataAgent`
- `deepresearch`
- `JeecgBoot`
- `KMatrix-service`

你需要记住：

> 真正的企业 AI 项目，最终不是“聊天产品”，而是“带有 AI 能力的业务流程系统”。

### 4.6 观测与治理层（Observability & Governance）

职责：

- Trace / Span / Prompt Log
- Tool Call Log
- Evaluation
- 安全审计
- 成本统计
- 失败恢复
- 健康检查与告警

这一层主要参考：

- `spring-ai-alibaba`
- `deepresearch`
- `RuoYi AI`

你需要记住：

> 如果没有观测、评测、治理，AI 后端最多是 demo，不是企业系统。

## 5. 如果你只想快速建立“产品感”，先读哪 3 个

如果时间有限，只看下面 3 个即可：

1. `JeecgBoot`
2. `KMatrix-service`
3. `DataAgent`

理由：

- `JeecgBoot`：给你平台壳与企业后台感觉
- `KMatrix-service`：给你知识库 + workflow + AI 平台感觉
- `DataAgent`：给你 AI-native 业务系统感觉

这三个看完，你对“企业级 Java + AI 后端产品长什么样”会有比较完整的第一印象。

## 6. 如果你要自己设计一个企业级 Java + AI 平台，建议先抄的模块顺序

不要一上来就做多 Agent 平台，也不要一上来就做低代码编排器。

更稳的顺序是：

1. **平台壳**
   - 账号
   - 权限
   - 应用管理
   - 模型配置
2. **知识层**
   - 文档上传
   - 解析
   - embedding
   - 检索
3. **执行层**
   - chat
   - tools
   - workflow
   - 审批
4. **治理层**
   - trace
   - logging
   - evaluation
   - cost
5. **扩展层**
   - MCP
   - 插件
   - 第三方服务接入
6. **高级层**
   - 多 Agent
   - 图编排
   - 低代码 Studio

这也是这些开源项目总体体现出来的演进路径。

## 7. 你应该重点提炼的“架构问题”

阅读这些项目时，不要只记功能列表，应该重点回答下面这些问题：

1. 这个项目的“平台壳”在哪里？
2. 控制面和执行面有没有分开？
3. knowledge / workflow / agent runtime 是同层还是分层？
4. MCP / plugin 是接在执行层还是平台层？
5. observability 是附属能力还是核心能力？
6. AI 能力是围绕聊天设计，还是围绕业务流程设计？
7. 这个系统能不能扩成多应用平台，而不是只服务一个机器人？

## 8. 推荐源码阅读顺序

### 第一阶段：先建立产品感觉

1. `JeecgBoot`
2. `RuoYi AI`
3. `KMatrix-service`

### 第二阶段：再看 AI-native 执行链

4. `DataAgent`
5. `deepresearch`

### 第三阶段：最后补平台内核

6. `spring-ai-alibaba`
7. `Lynxe`
8. `app-platform`
9. `fit-framework`

## 9. 当前最值得你抄的，不是代码，而是结构

对你现在这个阶段来说，最大的收获不应该是：

- 学会某个 `Spring AI` API
- 学会某个 `LangChain4j` 注解

而应该是：

- 看到企业平台壳怎么和 AI 模块共存
- 看到知识库、workflow、agent runtime、MCP、observability 是怎么分层的
- 看到一个 AI 后端为什么必须有控制面

如果你能从这些项目里提炼出自己的一版架构骨架，你就已经比“只会写 AI demo 的 Java 程序员”往前走了一大步。

## 10. 信息来源

以下内容基于 2026-04-25 公开仓库 / 官方文档核对整理：

- `jeecgboot/JeecgBoot`
  - <https://github.com/jeecgboot/JeecgBoot>
- `ageerle/ruoyi-ai`
  - <https://github.com/ageerle/ruoyi-ai>
  - 安全 issue：<https://github.com/ageerle/ruoyi-ai/issues/9>
- `mahoneliu/KMatrix-service`
  - <https://github.com/mahoneliu/KMatrix-service>
- `spring-ai-alibaba/DataAgent`
  - <https://github.com/spring-ai-alibaba/DataAgent>
- `spring-ai-alibaba/deepresearch`
  - <https://github.com/spring-ai-alibaba/deepresearch>
- `alibaba/spring-ai-alibaba`
  - <https://github.com/alibaba/spring-ai-alibaba>
- `spring-ai-alibaba/Lynxe`
  - <https://github.com/spring-ai-alibaba/Lynxe>
- `ModelEngine-Group/app-platform`
  - <https://github.com/ModelEngine-Group/app-platform>
- `ModelEngine-Group/fit-framework`
  - <https://github.com/ModelEngine-Group/fit-framework>

