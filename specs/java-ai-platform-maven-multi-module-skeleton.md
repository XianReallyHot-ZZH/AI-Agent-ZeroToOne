# 企业级 Java + AI 平台 Maven 多模块项目骨架

> 目标：
> 把上一份后端架构草图进一步细化成一个可直接开仓库的 `Maven` 多模块骨架。
>
> 这份文档回答的是：
>
> 1. 仓库根目录应该长什么样
> 2. `pom.xml` 应该怎么分层
> 3. 模块之间允许怎样依赖
> 4. 每个模块内部 package 应该怎么组织
> 5. `platform-server` 和 `platform-worker` 应该如何装配同一套业务模块

相关文档：

- [java-ai-platform-backend-architecture.md](./java-ai-platform-backend-architecture.md)
- [java-ai-enterprise-architecture-matrix.md](./java-ai-enterprise-architecture-matrix.md)

## 1. 设计目标

这套骨架追求的不是“最少文件”，而是“**一年后还没烂掉**”。

因此它优先保证三件事：

1. **模块边界清楚**
   - 业务模块、外部依赖、启动装配分开
2. **依赖方向稳定**
   - 领域模块不反向依赖应用层和第三方接线层
3. **可平滑演进**
   - 先模块化单体
   - 后面如果要拆服务，不需要推翻整个仓库结构

## 2. 推荐仓库结构

```text
java-ai-platform/
├─ pom.xml
├─ README.md
├─ .gitignore
├─ docs/
├─ db/
│  ├─ migration/
│  ├─ seed/
│  └─ er/
├─ deploy/
│  ├─ docker-compose/
│  ├─ k8s/
│  └─ scripts/
├─ apps/
│  ├─ platform-server/
│  └─ platform-worker/
├─ modules/
│  ├─ module-common/
│  ├─ module-iam/
│  ├─ module-control-plane/
│  ├─ module-runtime/
│  ├─ module-workflow/
│  ├─ module-knowledge/
│  ├─ module-mcp/
│  ├─ module-observability/
│  └─ module-evaluation/
├─ integrations/
│  ├─ integration-llm/
│  ├─ integration-vectorstore/
│  ├─ integration-object-storage/
│  ├─ integration-python-sandbox/
│  ├─ integration-document-parser/
│  └─ integration-http-tools/
└─ testing/
   ├─ testing-fixtures/
   ├─ testing-contract/
   └─ testing-e2e/
```

## 3. 顶层模块分工

### `apps/`

只负责：

- Spring Boot 启动
- Bean 装配
- 对外 API
- profile 配置
- 进程边界

不负责：

- 核心业务规则
- 外部系统具体接线逻辑

### `modules/`

只负责：

- 业务边界
- 领域模型
- 应用服务
- 仓储接口
- 领域事件

不负责：

- 第三方 SDK 具体接法
- Spring Boot 启动
- HTTP client / vendor adapter 细节

### `integrations/`

只负责：

- 对接外部系统
- 提供 `modules` 所定义端口的适配器实现
- 隔离第三方 SDK 依赖

不负责：

- 平台主业务规则

### `testing/`

只负责：

- 测试基座
- 假实现
- 契约测试
- 端到端测试

## 4. 父 `pom.xml` 设计

根 `pom.xml` 只做三件事：

1. 统一版本
2. 聚合模块
3. 提供 dependency management / plugin management

### 4.1 顶层 `pom.xml` 建议

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>java-ai-platform</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>apps/platform-server</module>
    <module>apps/platform-worker</module>

    <module>modules/module-common</module>
    <module>modules/module-iam</module>
    <module>modules/module-control-plane</module>
    <module>modules/module-runtime</module>
    <module>modules/module-workflow</module>
    <module>modules/module-knowledge</module>
    <module>modules/module-mcp</module>
    <module>modules/module-observability</module>
    <module>modules/module-evaluation</module>

    <module>integrations/integration-llm</module>
    <module>integrations/integration-vectorstore</module>
    <module>integrations/integration-object-storage</module>
    <module>integrations/integration-python-sandbox</module>
    <module>integrations/integration-document-parser</module>
    <module>integrations/integration-http-tools</module>

    <module>testing/testing-fixtures</module>
    <module>testing/testing-contract</module>
    <module>testing/testing-e2e</module>
  </modules>
</project>
```

### 4.2 顶层 properties 建议

```xml
<properties>
  <java.version>21</java.version>
  <spring-boot.version>3.5.0</spring-boot.version>
  <spring-ai.version>1.1.0</spring-ai.version>
  <mcp-java-sdk.version>...</mcp-java-sdk.version>
  <postgresql.version>...</postgresql.version>
  <flyway.version>...</flyway.version>
  <testcontainers.version>...</testcontainers.version>
</properties>
```

### 4.3 dependency management 建议

统一管理：

- `spring-boot-dependencies`
- `spring-ai` BOM
- 数据库驱动
- 测试依赖
- 日志 / metrics / tracing 依赖

原则：

> 版本统一只放在根 `pom`，业务模块不要各自写版本号。

## 5. 子模块分层规则

## 5.1 依赖方向总规则

```text
apps  -> modules -> module-common
apps  -> integrations -> modules
integrations -> modules -> module-common
testing -> apps/modules/integrations

禁止：
modules -> apps
modules -> integrations
module-common -> 其他业务模块
```

最关键的禁令是：

> `modules` 只能依赖抽象，不直接依赖外部系统实现。

## 5.2 推荐依赖矩阵

| 模块 | 允许依赖 | 不允许依赖 |
|---|---|---|
| `module-common` | JDK、少量通用库 | 任何业务模块、任何 integration |
| `module-iam` | `module-common` | `module-runtime`、`integration-*` |
| `module-control-plane` | `module-common`、`module-iam` | `integration-*` |
| `module-runtime` | `module-common`、`module-iam`、`module-control-plane`、`module-knowledge`、`module-mcp`、`module-observability` | `apps/*`、`integration-*` |
| `module-workflow` | `module-common`、`module-iam`、`module-control-plane`、`module-observability` | `integration-*` |
| `module-knowledge` | `module-common`、`module-iam` | `integration-*` |
| `module-mcp` | `module-common`、`module-iam`、`module-observability` | `integration-*` |
| `module-observability` | `module-common` | `integration-*` |
| `module-evaluation` | `module-common`、`module-runtime`、`module-workflow`、`module-observability` | `integration-*` |
| `integration-*` | 对应 `module-*` + 第三方 SDK | `apps/*` |
| `platform-server` | 所有 `module-*` + 需要的 `integration-*` | 无 |
| `platform-worker` | 所有执行相关 `module-*` + 需要的 `integration-*` | 无 |

## 6. 单模块内部 package 结构

每个 `module-*` 不要按“controller/service/entity/mapper”平铺。

建议按 **边界 + 分层** 组织。

### 6.1 标准 package 模板

```text
com.example.platform.runtime
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ service/
│  └─ dto/
├─ domain/
│  ├─ model/
│  ├─ event/
│  ├─ service/
│  ├─ repository/
│  └─ policy/
├─ port/
│  ├─ inbound/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   ├─ messaging/
   └─ config/
```

### 6.2 各层职责

#### `api/`

只放：

- 当前模块对外暴露的 facade
- 给其他模块调用的稳定接口

不要放：

- HTTP controller

#### `application/`

只放：

- 用例编排
- command / query handler
- DTO
- 事务边界

#### `domain/`

只放：

- 聚合根
- 值对象
- 领域事件
- 领域规则
- 仓储接口

#### `port/`

只放：

- 当前模块需要的外部能力接口
- 例如：
  - `LlmClientPort`
  - `VectorStorePort`
  - `ObjectStoragePort`
  - `McpExecutionPort`

#### `infrastructure/`

只放：

- 当前模块内部的技术实现细节
- 例如 JPA/MyBatis repository adapter、事件发布适配器等

注意：

真正第三方系统的大型适配器，优先放到 `integrations/`，不要塞回 `modules/`。

## 7. `integrations/` 该怎么写

`integrations/` 的职责是把“外部世界”隔离在业务模块之外。

### 7.1 `integration-llm`

职责：

- Spring AI / provider 适配
- chat / embedding / moderation / image 等 provider 封装
- 提供 `module-runtime`、`module-knowledge` 需要的端口实现

建议 package：

```text
com.example.platform.integration.llm
├─ config/
├─ provider/
│  ├─ openai/
│  ├─ anthropic/
│  └─ dashscope/
├─ adapter/
└─ support/
```

### 7.2 `integration-vectorstore`

职责：

- `pgvector`、`Milvus`、`Qdrant` 等适配
- 向 `module-knowledge` 暴露统一检索接口实现

### 7.3 `integration-object-storage`

职责：

- MinIO / S3 / OSS
- 文档原件、报告产物、附件归档

### 7.4 `integration-python-sandbox`

职责：

- 执行 Python 分析任务
- 封装容器调用、超时、资源限制、日志采集

### 7.5 `integration-document-parser`

职责：

- PDF / DOCX / XLSX / HTML / Markdown 文档提取
- 清洗、分段、元数据提取

### 7.6 `integration-http-tools`

职责：

- 外部 HTTP 工具
- 第三方 SaaS / 内部服务工具接线

## 8. 两个启动应用怎么装

## 8.1 `apps/platform-server`

职责：

- 管理端 API
- 运行端 API
- SSE / WebSocket
- 鉴权
- 应用配置加载
- 同步快路径请求处理

推荐依赖：

- 所有 `module-*`
- `integration-llm`
- `integration-vectorstore`
- `integration-object-storage`
- `integration-document-parser`
- `integration-http-tools`

### 推荐 package

```text
com.example.platform.server
├─ PlatformServerApplication.java
├─ config/
├─ web/
│  ├─ admin/
│  ├─ runtime/
│  └─ internal/
├─ security/
└─ support/
```

### `platform-server` 不应该承载什么

- 大量长任务轮询
- embedding 批处理
- 大规模 report 生成
- evaluation 批跑

这些属于 worker。

## 8.2 `apps/platform-worker`

职责：

- 执行异步任务
- workflow run
- knowledge ingest
- report generation
- evaluation run
- scheduled jobs

推荐依赖：

- 所有执行相关 `module-*`
- 所有必要的 `integration-*`

### 推荐 package

```text
com.example.platform.worker
├─ PlatformWorkerApplication.java
├─ config/
├─ job/
├─ workflow/
└─ support/
```

### `platform-worker` 启动后至少要有的能力

- 拉取待执行任务
- 更新执行状态
- 记录 step log
- 捕获失败并可重试
- 支持暂停 / 恢复

## 9. 模块骨架示例

下面以 `module-runtime` 为例。

### 9.1 目录骨架

```text
modules/module-runtime/
├─ pom.xml
└─ src/
   ├─ main/java/com/example/platform/runtime/
   │  ├─ api/
   │  │  └─ RuntimeFacade.java
   │  ├─ application/
   │  │  ├─ command/
   │  │  ├─ query/
   │  │  ├─ dto/
   │  │  └─ service/
   │  ├─ domain/
   │  │  ├─ model/
   │  │  ├─ event/
   │  │  ├─ repository/
   │  │  └─ policy/
   │  ├─ port/
   │  │  ├─ inbound/
   │  │  └─ outbound/
   │  └─ infrastructure/
   │     ├─ persistence/
   │     ├─ messaging/
   │     └─ config/
   └─ test/java/...
```

### 9.2 `pom.xml` 示意

```xml
<project>
  <parent>
    <groupId>com.example</groupId>
    <artifactId>java-ai-platform</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>module-runtime</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>module-common</artifactId>
    </dependency>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>module-iam</artifactId>
    </dependency>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>module-control-plane</artifactId>
    </dependency>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>module-knowledge</artifactId>
    </dependency>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>module-mcp</artifactId>
    </dependency>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>module-observability</artifactId>
    </dependency>
  </dependencies>
</project>
```

## 10. 推荐根 package 命名

推荐统一根 package：

```text
com.yourcompany.aiplatform
```

例如：

```text
com.yourcompany.aiplatform.common
com.yourcompany.aiplatform.iam
com.yourcompany.aiplatform.runtime
com.yourcompany.aiplatform.workflow
com.yourcompany.aiplatform.knowledge
com.yourcompany.aiplatform.mcp
com.yourcompany.aiplatform.server
com.yourcompany.aiplatform.worker
com.yourcompany.aiplatform.integration.llm
```

不要把所有模块都放在：

```text
com.yourcompany.platform.service.*
```

这种名字下，后面边界会越来越糊。

## 11. 推荐依赖策略

## 11.1 哪些依赖只能在 `apps/`

- `spring-boot-starter-web`
- `spring-boot-starter-security`
- `spring-boot-starter-actuator`
- `springdoc-openapi`
- `websocket`

原因：

这些是进程边界和对外接口能力，不应该污染业务模块。

## 11.2 哪些依赖可以在 `modules/`

- `spring-context`
- `spring-tx`
- `jakarta.validation`
- `jackson-annotations`
- ORM 抽象层（谨慎）

原则：

业务模块可以用 Spring 的基础注解和事务能力，但不要直接绑定过多 Web / Provider 细节。

## 11.3 哪些依赖只能在 `integrations/`

- `spring-ai-*`
- MCP SDK
- 各类向量库 SDK
- MinIO / S3 SDK
- PDF / 文档解析 SDK
- Python / Docker 执行相关依赖

原因：

你未来最可能变化的就是这些 vendor 适配层。

## 12. 配置文件策略

### 12.1 顶层配置原则

- `apps/platform-server/src/main/resources/application.yml`
- `apps/platform-worker/src/main/resources/application.yml`

只放：

- 当前进程需要的配置
- profile include

不要把所有模块配置都硬塞到一个超长 `application.yml`。

### 12.2 模块配置装配

推荐每个模块提供自己的配置前缀，例如：

- `platform.iam.*`
- `platform.runtime.*`
- `platform.workflow.*`
- `platform.knowledge.*`
- `platform.mcp.*`

### 12.3 Integration 配置前缀

例如：

- `platform.llm.*`
- `platform.vectorstore.*`
- `platform.storage.*`
- `platform.parser.*`
- `platform.sandbox.*`

## 13. 数据访问策略

这套骨架不强制你一定用 `JPA` 还是 `MyBatis`。

但强制建议：

1. 领域仓储接口放在 `modules/*/domain/repository`
2. 实现放在 `modules/*/infrastructure/persistence` 或 `integrations/*`
3. SQL / mapper / entity 不要外溢到 controller 和 application service

### 推荐实践

- 控制面、管理后台表：`MyBatis` 或 `Spring Data JDBC`
- 复杂查询、分页后台：`MyBatis`
- 简单聚合持久化：`Spring Data`

关键不是框架，而是：

> 仓储接口和具体持久化技术要隔离。

## 14. 事件与任务总线

v1 不必一上来引入 Kafka。

推荐先做：

- 数据库表驱动任务队列
- 应用内事件发布
- Redis 只做短状态和缓存

### 为什么

因为你现在最需要先稳定：

- run
- task
- approval
- knowledge ingest

而不是先把异步系统搞复杂。

### 未来可拆点

当下面场景明显出现时，再上真正消息总线：

- 高吞吐异步任务
- 多 worker 水平扩展
- integration 事件很多
- trace / audit 需要独立消费

## 15. 测试模块建议

## 15.1 `testing-fixtures`

职责：

- 测试数据
- fake ports
- builder
- stub provider

## 15.2 `testing-contract`

职责：

- integration adapter 契约测试
- MCP client / server 契约
- vector store adapter 契约

## 15.3 `testing-e2e`

职责：

- 平台端到端测试
- 文档导入测试
- chat / workflow / approval 全链路测试

## 16. 建议的第一批模块创建顺序

不要一次把全部模块都建完。

推荐顺序：

1. `module-common`
2. `module-iam`
3. `module-control-plane`
4. `module-runtime`
5. `module-knowledge`
6. `integration-llm`
7. `integration-vectorstore`
8. `integration-object-storage`
9. `apps/platform-server`
10. `apps/platform-worker`
11. `module-workflow`
12. `module-mcp`
13. `module-observability`
14. `module-evaluation`

### 为什么这样排

- 先把基础骨架搭起来
- 再打通 `chat + rag` 最短主链
- 然后再补 workflow、MCP、观测、评测

## 17. v1 最小可运行骨架

如果你要最短时间起一个能跑的仓库，建议保留下面这些模块：

```text
apps/
  platform-server
  platform-worker

modules/
  module-common
  module-iam
  module-control-plane
  module-runtime
  module-knowledge
  module-workflow

integrations/
  integration-llm
  integration-vectorstore
  integration-object-storage
  integration-document-parser
```

暂时不做：

- `module-evaluation`
- `module-mcp`
- `integration-python-sandbox`
- `integration-http-tools`

这样你可以先把：

- 用户 / 应用 / 模型 / prompt 管理
- 知识库导入
- chat + rag
- 简单 workflow
- 后台 worker

完整跑起来。

## 18. 这一骨架最重要的判断标准

不是“模块数量是否刚好”，而是：

1. 三个月后你还能清楚说出每个模块的职责
2. `modules` 没被第三方 SDK 污染
3. `platform-server` 和 `platform-worker` 可以共用大多数业务模块
4. 想拆服务时，只需要调整 `apps/` 和少量 adapter，不需要把业务模块重写一遍

如果这四点成立，这套 Maven 多模块骨架就是成功的。

