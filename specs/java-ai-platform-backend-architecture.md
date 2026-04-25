# 企业级 Java + AI 平台后端架构草图

> 目标：
> 给一个已经理解 `agent runtime`、但还没有做过企业级 `Java + AI` 产品的人，一份可以直接拿来做新仓库设计起点的后端架构草图。
>
> 这份文档不是“唯一正确答案”，但它刻意遵循前面几份调研文档里反复出现的共性：
>
> - 先有平台壳，再有 AI 模块
> - 先有控制面，再有执行面
> - 先把知识、工作流、治理做好，再谈多 agent 和低代码
> - 先做模块化单体，再决定是否拆微服务

相关参考：

- [java-ai-enterprise-architecture-matrix.md](./java-ai-enterprise-architecture-matrix.md)
- [java-ai-project-module-reading-checklist.md](./java-ai-project-module-reading-checklist.md)
- [java-ai-next-learning-plan.md](./java-ai-next-learning-plan.md)

## 1. 目标产品边界

这份架构草图默认你要做的，不是单个聊天机器人，而是一个“**企业级 AI 平台后端**”。

更具体地说，它至少支持下面四类能力：

1. **AI 应用管理**
   - 聊天助手
   - 知识库问答
   - 数据分析助手
   - 工作流型 AI 应用

2. **平台控制能力**
   - 模型配置
   - Prompt 模板管理
   - 应用配置
   - MCP / 外部工具注册
   - API Key / Provider 管理

3. **AI 执行能力**
   - Chat + Tool Calling
   - RAG
   - Workflow / Agent Execution
   - 异步任务
   - Human-in-the-loop

4. **平台治理能力**
   - Trace / Prompt Log / Tool Log
   - Evaluation
   - 审计
   - 成本与配额
   - 权限控制

如果你的目标只是“做一个可演示的 AI 助手”，这份架构会偏重。

如果你的目标是：

> 我想做一个能承载多个 AI 应用、多个知识库、多个模型、多个工作流、可观测、可治理、可扩展的后端平台

那么这份草图是合适的。

## 2. 先给结论：推荐的总体形态

### 推荐形态

**模块化单体 + 独立 Worker + 对象存储 + 向量检索 + 统一观测**

这是当前阶段最稳的答案。

### 不推荐形态

一开始就做：

- 微服务大爆炸
- 多 agent 平台 + 低代码编排器 + 插件市场一起上
- 自研所有基础设施
- “先做 AI runtime，后台管理以后补”

这些路径很容易把项目做散。

### 为什么是“模块化单体 + Worker”

因为你现在最需要的是：

1. 保证模块边界清楚
2. 保证后端能快速迭代
3. 保证 AI 执行链和平台控制面能先跑通
4. 让你有空间观察哪些边界以后真的值得拆成独立服务

所以推荐先做：

- 一个主后端服务：`platform-server`
- 一个异步执行进程：`platform-worker`

而不是一开始拆成 8 个微服务。

## 3. 一张总图

```text
                         ┌─────────────────────────────┐
                         │        Admin Console         │
                         │ app/model/prompt/kb/workflow│
                         └──────────────┬──────────────┘
                                        │
                         ┌──────────────▼──────────────┐
                         │        Platform Server       │
                         │                              │
                         │  Access Layer                │
                         │  IAM / RBAC                  │
                         │  Control Plane               │
                         │  Runtime API                 │
                         │  Knowledge API               │
                         │  MCP Registry                │
                         │  Observability API           │
                         └───────┬───────────┬─────────┘
                                 │           │
                 ┌───────────────▼───┐   ┌──▼────────────────┐
                 │   Async Worker     │   │ External Systems   │
                 │ workflow/job/rag   │   │ LLM/MCP/HTTP/DB    │
                 │ ingest/eval/report │   │ Python Sandbox     │
                 └───────┬────────────┘   └───────────────────┘
                         │
      ┌──────────────────▼───────────────────────────────────────┐
      │                       Data Plane                         │
      │ PostgreSQL | Redis | Object Storage | Vector Store      │
      │ Logs/Traces | Optional ES/OpenSearch                    │
      └──────────────────────────────────────────────────────────┘
```

## 4. 核心设计原则

### 4.1 控制面和执行面分开

控制面负责：

- 配置
- 管理
- 发布
- 权限
- 模型 / Prompt / 应用 / MCP 注册

执行面负责：

- 聊天
- 检索
- 工具调用
- workflow 运行
- 任务调度
- 结果生成

这是企业级 AI 后端最重要的一条边界。

### 4.2 AI 应用不是“聊天会话”的别名

一个真正的 AI 应用后端，主对象不应该只有 `conversation`。

它至少还有：

- `application`
- `workflow`
- `knowledge_base`
- `execution_run`
- `task`
- `approval_request`
- `mcp_server`
- `evaluation_case`

### 4.3 同步快路径，异步长路径

同步适合：

- 简单问答
- 简单 RAG
- 短工具调用

异步适合：

- 文档导入
- embedding
- 报告生成
- 多步 workflow
- 数据分析
- 深度研究
- evaluation

### 4.4 知识层独立于聊天层

不要把知识库实现塞进 chat service 里。

知识层应该独立管理：

- 文档
- 解析
- chunk
- embedding
- metadata
- index
- retrieval

聊天层只是消费检索结果。

### 4.5 治理能力从第一天就要留口

从 v1 开始就要在设计里预留：

- request id
- trace id
- run id
- tool call log
- prompt snapshot
- model response snapshot
- token / cost statistics

否则后面补会非常痛苦。

## 5. 推荐模块拆分

## 5.1 Maven 模块级拆分

建议仓库结构：

```text
platform/
├─ apps/
│  ├─ platform-server
│  └─ platform-worker
├─ modules/
│  ├─ module-iam
│  ├─ module-control-plane
│  ├─ module-runtime
│  ├─ module-workflow
│  ├─ module-knowledge
│  ├─ module-mcp
│  ├─ module-observability
│  ├─ module-evaluation
│  └─ module-common
├─ integrations/
│  ├─ integration-llm
│  ├─ integration-vectorstore
│  ├─ integration-object-storage
│  ├─ integration-python-sandbox
│  └─ integration-http-tools
├─ db/
├─ docs/
└─ frontend/
```

### 为什么这样拆

- `apps`：承载启动、装配、对外 API
- `modules`：承载业务边界
- `integrations`：承载外部依赖接线

这个结构的核心目的是：

> 业务边界和第三方接线边界不能混在一起。

## 5.2 每个模块干什么

### `module-iam`

职责：

- 用户
- 角色
- 权限
- 组织 / 租户
- 登录态
- 审计主体

你以后所有 AI 审计和配额都依赖它。

### `module-control-plane`

职责：

- AI 应用管理
- 模型配置管理
- Provider 管理
- Prompt 模板管理
- 系统配置
- MCP 服务器注册
- 工作流定义管理
- 发布 / 版本

这个模块是平台大脑，不直接跑 AI 执行。

### `module-runtime`

职责：

- 会话管理
- message 记录
- chat request 入口
- tool orchestration
- 流式输出
- run 状态管理
- approval 中断与恢复

这是“AI 执行入口”。

### `module-workflow`

职责：

- workflow 定义
- DAG / graph / step
- step state
- retry / timeout
- manual approval
- background run
- scheduled run

注意：

它不一定一开始就上 Camunda / Temporal，但语义层必须先有。

### `module-knowledge`

职责：

- 知识库
- 文档
- 文档解析
- chunk
- embedding
- metadata
- retrieval
- re-rank

### `module-mcp`

职责：

- MCP server registry
- MCP client lifecycle
- tool namespace
- permission policy
- tool execution log

### `module-observability`

职责：

- trace
- request log
- tool log
- prompt snapshot
- token / latency / cost metrics
- execution timeline

### `module-evaluation`

职责：

- eval dataset
- test case
- regression run
- score record
- prompt / workflow comparison

这个模块 v1 可以很薄，但接口边界要先存在。

### `module-common`

职责：

- 公共 domain primitives
- 错误码
- 事件对象
- 分页 / 查询模型
- 统一时间 / id / tenant 上下文

## 6. 控制面与执行面如何交互

### 控制面写什么

控制面写的是：

- 应用定义
- 工作流定义
- Prompt 模板
- 模型路由规则
- MCP 注册信息
- 检索策略

### 执行面读什么

执行面读的是：

- 当前应用的发布版本
- 对应 workflow 版本
- 对应 prompt 版本
- 对应知识库绑定
- 对应模型与工具权限

### 最重要的原则

执行记录不能反向污染控制面配置。

换句话说：

- `execution_run` 是运行态
- `application_version` 是配置态

它们必须分开。

## 7. 推荐数据存储

### 7.1 主数据库

推荐：

- `PostgreSQL`

原因：

- 结构化数据稳
- JSONB 友好
- 容易和 `pgvector` 组合
- 对 workflow / metadata / audit 友好

### 7.2 缓存与短状态

推荐：

- `Redis`

职责：

- session cache
- rate limit
- short-lived state
- SSE / stream 中间态
- distributed lock（如果需要）

### 7.3 对象存储

推荐：

- 开发环境：`MinIO`
- 生产环境：`OSS / S3 / COS`

职责：

- 原始文档
- 报告产物
- 大模型输入 / 输出归档
- 执行附件

### 7.4 向量存储

v1 推荐：

- `PostgreSQL + pgvector`

原因：

- 认知负担低
- 统一运维
- 先让平台能力跑通

v2 如果规模明显上来，再考虑：

- `Milvus`
- `Qdrant`

### 7.5 搜索引擎

不是 v1 必需。

只有在下面场景明显出现后再加：

- 大量文档全文检索
- 日志检索
- 审计检索
- 混合检索复杂化

可选：

- `Elasticsearch`
- `OpenSearch`

## 8. 推荐技术栈

## 8.1 Web 与平台层

推荐：

- `Spring Boot 3.x`
- `Spring Web`
- `Spring Validation`
- `Spring Data`
- `Spring Security`

如果你更熟悉 RuoYi / Jeecg 风格，也可以用：

- `Sa-Token`

但如果是绿地平台，我更建议：

- 认证授权优先靠 `Spring Security` 体系

## 8.2 AI 层

推荐主线：

- `Spring AI`

用途：

- model abstraction
- chat
- tool calling
- RAG integration
- MCP integration

推荐补充：

- `MCP Java SDK`

用途：

- 做你自己的 MCP client / server 集成层

谨慎使用：

- `LangChain4j`

它适合作为对照和补充，但不建议你一开始同时把主架构押在 `Spring AI` 和 `LangChain4j` 两套抽象上。

## 8.3 工作流层

v1 推荐：

- 先做“数据库驱动的 workflow / run / task 模型”
- 由 `platform-worker` 负责异步执行

v2 如果出现以下场景，再引入更重的编排引擎：

- 长流程
- 强一致补偿
- 跨服务编排
- 大量审批节点
- 复杂定时 / 重试 / 恢复

再考虑：

- `Temporal`
- `Camunda 8`

## 8.4 文档处理与对象提取

可以独立到 `integration-document-parser`，但 v1 也可以先并入 `module-knowledge`。

重点不是库名，而是边界：

- parse
- clean
- chunk
- embed
- index

一定不要和 chat runtime 混在一起。

## 9. 推荐核心表

下面不是完整 ER 图，而是你最该先有的表。

### 平台与身份

- `tenant`
- `user`
- `role`
- `user_role`
- `permission`

### 控制面

- `ai_provider`
- `ai_model`
- `ai_application`
- `ai_application_version`
- `prompt_template`
- `workflow_definition`
- `workflow_definition_version`
- `mcp_server`
- `tool_registry`

### 知识层

- `knowledge_base`
- `knowledge_document`
- `knowledge_chunk`
- `embedding_job`
- `retrieval_profile`

### 执行层

- `conversation`
- `message`
- `execution_run`
- `execution_step`
- `tool_call_record`
- `workflow_run`
- `task_record`
- `approval_request`

### 治理层

- `trace_record`
- `prompt_snapshot`
- `response_snapshot`
- `evaluation_case`
- `evaluation_run`
- `cost_record`
- `audit_log`

## 10. 三条关键执行链

## 10.1 Chat / RAG 应用请求

```text
API Request
  -> Auth / Tenant Context
  -> Load Application Version
  -> Load Prompt / Model / Knowledge Binding
  -> Retrieve Context
  -> Build Request
  -> Model Call
  -> Tool Calls (optional)
  -> Persist Message / Run / Tool Log
  -> Stream Response
```

这一条链适合：

- 企业知识库问答
- 内部 Copilot
- 轻量助手

## 10.2 Workflow / Agent 应用请求

```text
API Request
  -> Create Execution Run
  -> Resolve Workflow Definition Version
  -> Enqueue Worker Task
  -> Worker Pulls Run
  -> Execute Step by Step
  -> Persist Step State / Artifacts
  -> Optional Approval / HITL Pause
  -> Resume / Finish
```

这一条链适合：

- 数据分析助手
- 报告生成
- 多步业务处理
- 深度研究

## 10.3 文档导入链

```text
Upload Document
  -> Store Original File
  -> Parse
  -> Clean
  -> Chunk
  -> Embed
  -> Write Vector / Metadata
  -> Mark Knowledge Document Ready
```

这一条链必须异步。

## 11. 推荐 API 分组

### 管理端 API

- `/api/admin/auth/*`
- `/api/admin/models/*`
- `/api/admin/apps/*`
- `/api/admin/prompts/*`
- `/api/admin/workflows/*`
- `/api/admin/kb/*`
- `/api/admin/mcp/*`
- `/api/admin/eval/*`
- `/api/admin/observability/*`

### 运行端 API

- `/api/runtime/chat`
- `/api/runtime/chat/stream`
- `/api/runtime/run/start`
- `/api/runtime/run/{id}`
- `/api/runtime/run/{id}/approve`
- `/api/runtime/run/{id}/resume`
- `/api/runtime/conversations/*`

### Worker / Internal API

- `/internal/workflow/*`
- `/internal/knowledge/*`
- `/internal/eval/*`

注意：

`internal` API 不一定要开放成 HTTP，对内也可以走模块调用或消息驱动。

## 12. 推荐部署形态

### v1

- `platform-server` 一份
- `platform-worker` 一份
- `postgresql`
- `redis`
- `minio`
- `pgvector`

这是最适合起步的形态。

### v2

当下面问题明显出现时再拆：

- Worker 压力远大于 API
- 知识导入非常重
- evaluation 非常耗资源
- MCP / sandbox 风险隔离要求高

可以逐步拆出：

- `knowledge-worker`
- `evaluation-worker`
- `sandbox-runner`
- `mcp-gateway`

## 13. 第一阶段不要做什么

### 13.1 不要一开始就做多 agent 平台

你现在最需要的是：

- 单 Agent / workflow 稳定
- 平台控制面稳定
- 知识层稳定

多 agent 要放到后面。

### 13.2 不要一开始就做低代码 Studio

低代码 Studio 需要：

- 稳定 workflow model
- 稳定 tool model
- 稳定 versioning
- 稳定 governance

这些没稳定前，Studio 只会放大混乱。

### 13.3 不要一开始就微服务化

先做清楚模块边界，再决定部署边界。

### 13.4 不要先做 provider 大杂烩

v1 只保留：

- 1~2 个 LLM provider
- 1 个 embedding provider
- 1 个 vector store

先跑通产品形态。

## 14. 你最值得借鉴的“来源映射”

如果你要问“这份草图各部分分别像谁”，可以这样看：

### 平台壳

主要借鉴：

- `JeecgBoot`
- `RuoYi AI`

### AI 平台模块拆分

主要借鉴：

- `KMatrix-service`
- `spring-ai-alibaba`

### AI-native 业务执行链

主要借鉴：

- `DataAgent`
- `deepresearch`

### MCP / 扩展边界

主要借鉴：

- `spring-ai-alibaba`
- `Lynxe`
- `MCP Java SDK`

### 平台内核与编排

主要借鉴：

- `spring-ai-alibaba`
- `fit-framework`

## 15. 一个可执行的 v1 范围

如果你真要起一个新仓库，我建议 v1 就做下面这些：

### v1 必做

1. 用户 / 权限
2. 应用管理
3. 模型配置
4. Prompt 模板管理
5. 知识库导入与检索
6. Chat + RAG 运行链
7. 简单 workflow 运行链
8. 执行记录与日志
9. MCP 注册与调用
10. Admin 管理端 API

### v1 可选

1. 人工审批
2. 报告生成
3. 基础 evaluation
4. 简单成本统计

### v1 不做

1. 多 agent 团队
2. 低代码 Studio
3. 复杂图编排器
4. 多租户计费
5. 复杂市场化插件生态

## 16. 最终建议

如果把这份文档压成一句话，那就是：

> 你下一步应该做的，不是“再写一个 agent”，而是做一个“有平台壳、有控制面、有执行面、有知识层、有治理层”的 Java AI 后端。

最稳的起手式不是：

- 多 agent
- 低代码
- 微服务

而是：

- 模块化单体
- Worker
- PostgreSQL + Redis + 对象存储 + pgvector
- Spring AI 主线
- MCP 作为扩展层
- workflow 作为执行链而不是炫技点

这条路线更贴近你当前的能力积累，也更贴近前面调研到的生产级 Java + AI 项目的共同结构。

