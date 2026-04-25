# Java + AI 开源项目模块结构阅读清单

> 目标：
> 帮助一个已经理解 `agent runtime` 基础、但还没真正看过企业级 `Java + AI` 产品后端的开发者，快速建立“源码应该先看哪里”的阅读路径。
>
> 这份清单不是按功能列表写，而是按下面三个问题来组织：
>
> 1. 这个项目的 **平台壳** 在哪里？
> 2. 这个项目的 **AI 执行面** 在哪里？
> 3. 这个项目的 **扩展 / 治理 / 插件 / workflow** 在哪里？

## 1. 使用原则

阅读这些项目时，不要一上来就：

- 从最长的模块开始
- 从最炫的多 agent 部分开始
- 从 controller 一路点到 service 再点到 util
- 把平台壳、控制面、执行面混在一起看

更稳的顺序是：

1. 先看仓库根目录下的模块划分
2. 再定位哪个模块是“平台壳”、哪个模块是“AI 核心”
3. 再进入 AI 核心模块内部看 `config / runtime / workflow / rag / tool / api`
4. 最后才看部署、前端、脚本、示例和次级插件

## 2. 一张总表：每个项目先看什么

| 项目 | 先看目录 | 目录在回答什么问题 | 第二轮再看 | 第一轮先别深挖 |
|---|---|---|---|---|
| `JeecgBoot` | `jeecg-boot` | 企业平台壳和 AI 模块怎么共存 | `jeecgboot-vue3`、AI 专题 README、微服务 starter | 代码生成细节、前端页面细节 |
| `RuoYi AI` | `ruoyi-admin`、`ruoyi-modules`、`ruoyi-common` | 后端入口、AI 业务模块、公共能力怎么拆 | `docs`、`ruoyi-extend` | 部署细节、具体前端仓库 |
| `KMatrix-service` | `kmatrix-service/ruoyi-admin`、`kmatrix-service/ruoyi-ai`、`kmatrix-service/ruoyi-common` | 后台壳、AI 核心、公共模块怎么协作 | `docker`、前端 `Kmatrix-ui` | UI 组件和可视化编排前端实现 |
| `DataAgent` | `data-agent-management` | 管理后台 + AI 核心业务后端如何组织 | `docs`、`data-agent-frontend` | 前端交互细节 |
| `deepresearch` | `src` | 研究型多步 agent 的主执行链在哪里 | `tools`、`dockerConfig`、`ui-vue3` | Docker 与部署脚本 |
| `spring-ai-alibaba` | `spring-ai-alibaba-agent-framework`、`spring-ai-alibaba-graph-core`、`spring-ai-alibaba-admin` | framework、runtime、admin 三层边界如何划分 | `spring-boot-starters`、`examples`、`studio` | 具体示例代码细节 |
| `Lynxe` | `src`、`tools`、`knowledge` | 可独立运行的 agent service 如何设计 | `ui-vue3`、`deploy` | UI 细节和 prompt 内容 |
| `app-platform` | `app-builder`、`agent-flow`、`common`、`store` | 低代码 AI 应用平台如何拆后端、编排和资产层 | `frontend`、`examples`、`docs` | 前端图形编辑器细节 |
| `fit-framework` | `framework`、`examples`、`docs` | 平台内核、编排引擎、AI 原语层怎么组织 | `docker`、sandbox 工具 | 各种辅助开发目录 |

## 3. 项目逐个拆

### 3.1 `jeecgboot/JeecgBoot`

仓库：

- <https://github.com/jeecgboot/JeecgBoot>

公开结构信号：

- 根目录明确分成 `jeecg-boot`、`jeecgboot-vue3`
- README 明确说明：
  - `jeecg-boot` 是后端 Java 源码
  - `jeecgboot-vue3` 是前端源码
  - 还有 `jeecg-boot-starter` 对应底层封装 starter

### 第一轮阅读顺序

1. `jeecg-boot`
2. README 中 AI 应用平台相关说明
3. 如果仓库中有独立 AI 模块或 AI 专题 README，再进入该模块

### 你要重点回答的问题

1. 传统企业平台壳和 AI 平台能力是“并列模块”还是“嵌套模块”？
2. 知识库、模型管理、流程编排、MCP 是不是被当成平台能力，而不是某个机器人能力？
3. Jeecg 里已有的权限、流程、报表、大屏能力是如何被 AI 模块复用的？

### 你最该学的不是代码，而是结构

- `平台壳先于 AI 模块`
- `AI 模块不是单独小功能，而是平台级能力集合`
- `知识库 / 模型 / 流程 / MCP / 生成器` 被统一放进企业后台里

### 第一轮先不要深挖

- 页面渲染细节
- 低代码生成器实现细节
- AI prompt 模板细节

第一轮只要建立一个印象：

> “企业平台如何接纳 AI，而不是 AI 如何临时拼进一个管理后台。”

### 适合作为你的哪种参考

- 企业后台平台壳
- AI 中台
- 低代码 + AI 的统一产品外形

---

### 3.2 `ageerle/ruoyi-ai`

仓库：

- <https://github.com/ageerle/ruoyi-ai>

公开结构信号：

- 根目录公开可见：`docs`、`ruoyi-admin`、`ruoyi-common`、`ruoyi-extend`、`ruoyi-modules`
- README 明确写了：
  - 后端架构是 `Spring Boot 3.5.8 + Langchain4j`
  - 数据层是 `MySQL + Redis + 向量数据库`
  - 认证是 `Sa-Token + JWT`
  - 有 `多智能体`、`流程编排`、`MCP`、`RAG`

### 第一轮阅读顺序

1. `ruoyi-admin`
2. `ruoyi-modules`
3. `ruoyi-common`
4. `docs`
5. `ruoyi-extend`

### 为什么这样读

- `ruoyi-admin` 通常是入口和装配层，先看它能知道后端是单体聚合还是网关式聚合
- `ruoyi-modules` 是最可能装业务 AI 能力的地方
- `ruoyi-common` 能帮你判断平台复用边界
- `ruoyi-extend` 大概率是扩展集成层，适合第二轮看

### 你要重点回答的问题

1. AI 能力是作为“一个业务模块”放进若依体系，还是作为“全局能力中心”存在？
2. 向量库、对象存储、实时通信和 AI 服务是如何协同的？
3. 多智能体与可视化编排是后端主轴，还是产品宣传层？
4. 公共认证、权限、日志、监控在 AI 场景下有没有额外适配？

### 第一轮先不要深挖

- Docker 编排
- 前端管理端 / 用户端细节
- 向量库具体 provider 差异

### 额外提醒

- 这个项目适合学产品结构，但不适合把所有实现细节都当最佳实践
- 公开 issue 中有过安全问题，因此它更适合作为“结构参考”，不是“无脑复制模板”

---

### 3.3 `mahoneliu/KMatrix-service`

仓库：

- <https://github.com/mahoneliu/KMatrix-service>

公开结构信号：

- README 明确给出后端结构：
  - `kmatrix-service/ruoyi-admin`
  - `kmatrix-service/ruoyi-ai`
  - `kmatrix-service/ruoyi-common`
- 明确定位：
  - `LLM workflow`
  - `RAG knowledge base system`
  - `LangChain4j + LangGraph4j`
  - `Sa-Token(JWT)`

### 第一轮阅读顺序

1. `kmatrix-service/ruoyi-ai`
2. `kmatrix-service/ruoyi-admin`
3. `kmatrix-service/ruoyi-common`
4. `docker`

### 为什么先看 `ruoyi-ai`

因为这个项目最值钱的部分不是平台壳，而是：

- workflow
- RAG
- AI app orchestration
- embedding chat window

所以它和 `JeecgBoot` / `RuoYi AI` 不同，第一轮应该优先看 AI 核心模块。

### 你要重点回答的问题

1. `ruoyi-ai` 内部是按 `workflow / rag / app / chat / provider / vector` 拆，还是按 controller/service/entity 平铺？
2. `ruoyi-admin` 只是入口壳，还是也承担配置和管理控制面？
3. AI workflow 和知识库是共用一套资产层，还是各有自己的资产存储逻辑？
4. 它怎样把 RuoYi 后台体系和 AI 平台形态结合起来？

### 第一轮先不要深挖

- 前端可视化 workflow 编辑器
- rerank 模型下载与部署脚本
- 具体 UI 细节

### 最适合作为你的什么样本

- Java 版 Dify / MaxKB 风格后端
- 后台壳 + AI 平台 + workflow 的平衡结构

---

### 3.4 `spring-ai-alibaba/DataAgent`

仓库：

- <https://github.com/spring-ai-alibaba/DataAgent>

公开结构信号：

- 根目录可见：
  - `data-agent-management`
  - `data-agent-frontend`
  - `docs`
- README 明确能力：
  - `Text-to-SQL`
  - `Python 深度分析`
  - `报告生成`
  - `HITL`
  - `RAG`
  - `MCP Server`
  - `API Key 管理`

### 第一轮阅读顺序

1. `docs`
2. `data-agent-management`
3. 如果 `data-agent-management` 内部有 `graph / workflow / agent / rag / report / mcp` 之类目录，优先看这些
4. `data-agent-frontend`

### 为什么先看 `docs`

因为这个项目本身就是强业务链路项目。

如果先跳代码，很容易把：

- Text-to-SQL
- Python 执行器
- 报告生成
- HITL
- MCP Server

看成一堆散点功能。

先看架构文档，再看代码，才能知道它们是怎么串成一条业务执行链的。

### 你要重点回答的问题

1. 这个系统的主对象是“聊天会话”还是“数据分析任务”？
2. `Text-to-SQL -> Python 分析 -> 报告输出 -> 人工反馈` 这条链在代码里是怎么组织的？
3. API Key 管理是平台治理模块，还是 AI 业务模块的一部分？
4. MCP Server 为什么会被纳入这种垂直业务系统？

### 第一轮先不要深挖

- 前端大屏和页面交互
- 图表样式细节
- 数据源适配的具体兼容性细节

### 它最适合作为你的哪类样本

- AI-native 业务系统
- 智能分析师产品
- “AI 不只是聊天，而是执行业务分析流程”的后端样板

---

### 3.5 `spring-ai-alibaba/deepresearch`

仓库：

- <https://github.com/spring-ai-alibaba/deepresearch>

公开结构信号：

- 根目录可见：
  - `src`
  - `tools`
  - `dockerConfig`
  - `ui-vue3`
- README 明确有：
  - `Plan and Execute`
  - `Reflection`
  - `Hybrid RAG`
  - `Langfuse observability`
  - `Docker Python executor`

### 第一轮阅读顺序

1. `src`
2. README 中 `Architecture` / `Main Flow`
3. `tools`
4. `dockerConfig`
5. `ui-vue3`

### 你要重点回答的问题

1. 多步研究链条是如何在后端被建模的？
2. `plan / execute / reflect / retrieve / memory / report` 是一条线还是多个并列服务？
3. observability 是 runtime 内生能力，还是外挂监控？
4. Python sandbox 在系统里是工具、节点、还是独立执行子系统？

### 第一轮先不要深挖

- Docker 文件
- 前端页面和交互细节
- 每个工具节点的具体 prompt

### 它最适合作为你的哪类样本

- graph-based research backend
- 多步 agent 系统
- AI 系统的观测与实验性能力如何进入产品后端

---

### 3.6 `alibaba/spring-ai-alibaba`

仓库：

- <https://github.com/alibaba/spring-ai-alibaba>

公开结构信号：

- 根目录公开列出了核心模块：
  - `spring-ai-alibaba-agent-framework`
  - `spring-ai-alibaba-graph-core`
  - `spring-ai-alibaba-admin`
  - `spring-ai-alibaba-studio`
  - `spring-boot-starters`
  - `examples`

README 对这些模块的定义也非常清晰：

- `agent-framework`：多 Agent 开发框架
- `graph-core`：底层 runtime
- `admin`：可视化开发、观测、评测、MCP 管理
- `studio`：内嵌调试 UI

### 第一轮阅读顺序

1. `spring-ai-alibaba-agent-framework`
2. `spring-ai-alibaba-graph-core`
3. `spring-ai-alibaba-admin`
4. `examples`
5. `spring-boot-starters`

### 为什么这样读

因为你现在不是要学“怎么用这个框架”，而是要学：

> 这个团队如何把 framework、runtime、admin platform 三层拆开。

所以第一轮不能从示例开始，应该先看核心模块边界。

### 你要重点回答的问题

1. `agent-framework` 和 `graph-core` 的边界是否清晰？
2. 为什么 `admin` 被独立成平台层，而不是塞进 framework？
3. `context engineering` 在这里是 API、policy、runtime middleware 还是 hook/interceptor？
4. `MCP management` 为什么属于 admin，而不是纯执行层？

### 第一轮先不要深挖

- 单个 example
- starter 自动装配细节
- 前端 studio 细节

### 它最适合作为你的哪类样本

- Java AI framework
- runtime + admin platform 的分层设计
- 控制面 / 执行面的天然拆分

---

### 3.7 `spring-ai-alibaba/Lynxe`

仓库：

- <https://github.com/spring-ai-alibaba/Lynxe>

公开结构信号：

- 根目录可见：
  - `src`
  - `tools`
  - `knowledge`
  - `ui-vue3`
  - `deploy`
- README 明确定位：
  - Java 版 Manus
  - 纯 Java 多 Agent 协作
  - HTTP 服务接口
  - MCP 原生支持

### 第一轮阅读顺序

1. `src`
2. `tools`
3. `knowledge`
4. README 开发者快速入门
5. `deploy`
6. `ui-vue3`

### 你要重点回答的问题

1. Lynxe 的核心是“agent studio”还是“可集成 agent service”？
2. `tools` 和 `knowledge` 在运行时里是核心一层，还是只是资源层？
3. HTTP 服务接口是围绕 chat 暴露，还是围绕 task / function / workflow 暴露？
4. 它怎样在“高确定性执行”和“LLM 自主性”之间取平衡？

### 第一轮先不要深挖

- UI 页面
- prompt 细节
- use case 细节

### 它最适合作为你的哪类样本

- 可嵌入业务系统的 agent backend service
- 以 HTTP 服务形态存在的多 agent 运行系统

---

### 3.8 `ModelEngine-Group/app-platform`

仓库：

- <https://github.com/ModelEngine-Group/app-platform>

公开结构信号：

- 根目录可见：
  - `agent-flow`
  - `app-builder`
  - `common`
  - `store`
  - `frontend`
  - `examples`
  - `docs`
- README 明确写了：
  - 后端基于 `FIT`
  - 采用插件化开发
  - 包含应用管理模块和功能扩展模块
  - 流程运行基于 `Waterflow`

### 第一轮阅读顺序

1. `README` / `docs`
2. `app-builder`
3. `agent-flow`
4. `common`
5. `store`
6. `examples`
7. `frontend`

### 为什么这样读

- `app-builder` 很可能是平台核心管理模块
- `agent-flow` 很可能是编排和流程表达的核心
- `store` 暗示“资产 / 模板 / 应用复用仓”
- 这几个模块正好对应平台的三层：
  - 应用开发
  - 流程编排
  - 资产沉淀

### 你要重点回答的问题

1. “应用平台”在这里是指 agent 平台、workflow 平台，还是 AI 资产平台？
2. `app-builder` 与 `agent-flow` 的边界在哪里？
3. `store` 是资源仓、模板仓，还是工具市场？
4. 低代码能力是主要服务于产品经理，还是也服务于开发者？

### 第一轮先不要深挖

- React 前端图形编辑器
- 具体图节点渲染细节
- Docker 启动脚本

### 它最适合作为你的哪类样本

- AI 应用开发平台
- 低代码 + 插件化 + 流程引擎的产品后端

---

### 3.9 `ModelEngine-Group/fit-framework`

仓库：

- <https://github.com/ModelEngine-Group/fit-framework>

公开结构信号：

- 根目录可见：
  - `framework`
  - `examples`
  - `docs`
  - `docker`
- README 明确三大核心：
  - `FIT Core`
  - `WaterFlow Engine`
  - `FEL`

### 第一轮阅读顺序

1. `docs`
2. `framework`
3. `examples`
4. `docker`

### 为什么这样读

这是典型“框架内核仓库”。

如果你先点 `framework`，很容易在没有概念地图的情况下被抽象层淹没。

所以第一轮应该先用 README / docs 建立三层坐标：

- 函数底座
- 编排引擎
- AI 原语层

然后再回到代码。

### 你要重点回答的问题

1. `FIT Core` 解决的是插件与函数执行，还是微服务 / 计算资源统一问题？
2. `WaterFlow` 是面向业务流程，还是面向 AI 工作流，还是两者统一？
3. `FEL` 与 LangChain4j / Spring AI 这类抽象有什么本质不同？
4. 框架为什么强调“原生 / Spring 双模”和“聚散部署”？

### 第一轮先不要深挖

- Sandbox CLI
- 辅助 AI 开发目录
- 所有 examples 逐个实现

### 它最适合作为你的哪类样本

- Java AI 平台内核
- 插件化计算底座
- workflow engine + AI primitives 的统一框架

## 4. 跨项目阅读顺序

如果你不是为了面面俱到，而是为了尽快建立“企业级 Java + AI 后端产品感”，推荐按下面顺序读。

### 第 1 组：先建立平台壳感觉

1. `JeecgBoot`
2. `RuoYi AI`
3. `KMatrix-service`

你会先看到：

- 企业平台壳
- 权限 / 配置 / 管理后台
- 知识库 / workflow / AI app 管理

### 第 2 组：再建立 AI-native 执行链感觉

4. `DataAgent`
5. `deepresearch`

你会再看到：

- AI 执行链
- graph runtime
- report / HITL / Python executor / observability

### 第 3 组：最后补平台内核与运行时

6. `spring-ai-alibaba`
7. `Lynxe`
8. `app-platform`
9. `fit-framework`

你会最终看到：

- framework 层
- runtime 层
- admin/studio 层
- low-code / plugin / asset platform 层

## 5. 每个项目读完后必须产出的东西

不要只看源码，不做沉淀。

每看完一个项目，至少写这三样：

1. **模块地图**
   - 画出该项目的目录 / 模块边界
2. **控制面 / 执行面划分**
   - 哪些模块负责配置、治理、应用管理
   - 哪些模块负责 chat / workflow / agent / task 执行
3. **你要借鉴的 3 点**
   - 平台壳
   - AI 执行链
   - 扩展能力

## 6. 你最需要盯住的共性问题

阅读时，反复问下面这些问题：

1. 后端入口模块在哪里？
2. 公共模块和业务模块有没有分开？
3. AI 运行时是独立模块还是嵌在业务模块里？
4. 知识库、workflow、agent runtime 是否共用一套资产层？
5. MCP / 插件被放在平台层、控制层，还是执行层？
6. 报告、审批、人工反馈、任务调度这些“非聊天能力”在不在主链上？
7. observability / evaluation 是不是被当成一等能力？

## 7. 信息来源

以下结论基于 2026-04-25 对公开 README / GitHub 仓库结构页的核对：

- `JeecgBoot`
  - <https://github.com/jeecgboot/JeecgBoot>
- `RuoYi AI`
  - <https://github.com/ageerle/ruoyi-ai>
- `KMatrix-service`
  - <https://github.com/mahoneliu/KMatrix-service>
- `DataAgent`
  - <https://github.com/spring-ai-alibaba/DataAgent>
- `deepresearch`
  - <https://github.com/spring-ai-alibaba/deepresearch>
- `spring-ai-alibaba`
  - <https://github.com/alibaba/spring-ai-alibaba>
- `Lynxe`
  - <https://github.com/spring-ai-alibaba/Lynxe>
- `app-platform`
  - <https://github.com/ModelEngine-Group/app-platform>
- `fit-framework`
  - <https://github.com/ModelEngine-Group/fit-framework>

