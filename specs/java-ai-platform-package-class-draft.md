# 企业级 Java + AI 平台核心模块 package 级目录树 + 类名草案

> 目标：
> 在 Maven 多模块骨架之上，再向下细化一层，给出：
>
> 1. 每个核心模块推荐的 `package` 目录树
> 2. 每个 package 下第一批最值得创建的类名草案
> 3. 一套统一的命名约定，避免仓库一开始就长歪
>
> 这份文档不是代码生成器，而是“**起仓库时怎么命名、怎么分层、先建哪些类**”的设计草案。

相关文档：

- [java-ai-platform-maven-multi-module-skeleton.md](./java-ai-platform-maven-multi-module-skeleton.md)
- [java-ai-platform-backend-architecture.md](./java-ai-platform-backend-architecture.md)

## 1. 统一命名约定

先定规则，再定类名。

### 1.1 根 package

推荐：

```text
com.yourcompany.aiplatform
```

例如：

```text
com.yourcompany.aiplatform.common
com.yourcompany.aiplatform.iam
com.yourcompany.aiplatform.controlplane
com.yourcompany.aiplatform.runtime
com.yourcompany.aiplatform.workflow
com.yourcompany.aiplatform.knowledge
com.yourcompany.aiplatform.mcp
com.yourcompany.aiplatform.observability
com.yourcompany.aiplatform.evaluation
com.yourcompany.aiplatform.integration.llm
com.yourcompany.aiplatform.server
com.yourcompany.aiplatform.worker
```

### 1.2 后缀规则

建议统一使用以下后缀：

| 类别 | 后缀 | 例子 |
|---|---|---|
| 对外门面 | `Facade` | `RuntimeFacade` |
| 应用层处理器 | `Handler` / `Service` | `StartRunHandler` |
| 入参命令 | `Command` | `StartRunCommand` |
| 出参查询 | `Query` / `View` | `ConversationView` |
| 领域对象 | 无后缀或 `Record` | `ExecutionRun` |
| 领域策略 | `Policy` | `ApprovalPolicy` |
| 端口接口 | `Port` | `VectorStorePort` |
| 适配器实现 | `Adapter` | `PgVectorStoreAdapter` |
| 持久化实体 | `Entity` | `ExecutionRunEntity` |
| 映射器 | `Mapper` | `ExecutionRunMapper` |
| 控制器 | `Controller` | `RuntimeChatController` |
| 配置类 | `Config` | `SecurityConfig` |
| 工作者 | `Worker` | `KnowledgeIngestionWorker` |
| 调度器 | `Scheduler` / `Dispatcher` | `WorkerJobDispatcher` |

### 1.3 分层规则

每个核心模块建议遵循这套目录：

```text
<module-root>/
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ event/
│  ├─ policy/
│  ├─ repository/
│  └─ service/
├─ port/
│  ├─ inbound/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   ├─ cache/
   ├─ messaging/
   └─ config/
```

不是每个模块都必须一字不差照搬，但建议尽量统一。

## 2. 总览：第一批模块与 package 根

| Maven 模块 | package 根 |
|---|---|
| `module-common` | `com.yourcompany.aiplatform.common` |
| `module-iam` | `com.yourcompany.aiplatform.iam` |
| `module-control-plane` | `com.yourcompany.aiplatform.controlplane` |
| `module-runtime` | `com.yourcompany.aiplatform.runtime` |
| `module-workflow` | `com.yourcompany.aiplatform.workflow` |
| `module-knowledge` | `com.yourcompany.aiplatform.knowledge` |
| `module-mcp` | `com.yourcompany.aiplatform.mcp` |
| `module-observability` | `com.yourcompany.aiplatform.observability` |
| `module-evaluation` | `com.yourcompany.aiplatform.evaluation` |
| `integration-llm` | `com.yourcompany.aiplatform.integration.llm` |
| `integration-vectorstore` | `com.yourcompany.aiplatform.integration.vectorstore` |
| `integration-object-storage` | `com.yourcompany.aiplatform.integration.storage` |
| `integration-python-sandbox` | `com.yourcompany.aiplatform.integration.sandbox` |
| `integration-document-parser` | `com.yourcompany.aiplatform.integration.parser` |
| `integration-http-tools` | `com.yourcompany.aiplatform.integration.httptool` |
| `platform-server` | `com.yourcompany.aiplatform.server` |
| `platform-worker` | `com.yourcompany.aiplatform.worker` |

## 3. `module-common`

### 3.1 推荐目录树

```text
com.yourcompany.aiplatform.common
├─ id/
├─ error/
├─ result/
├─ page/
├─ context/
├─ time/
├─ event/
└─ util/
```

### 3.2 第一批类名草案

#### `id/`

- `ApplicationId`
- `ConversationId`
- `ExecutionRunId`
- `KnowledgeBaseId`
- `KnowledgeDocumentId`
- `PromptTemplateId`
- `TenantId`
- `UserId`
- `WorkflowDefinitionId`
- `WorkflowRunId`

建议：

- 用 `record` 表达 ID 包装类型
- 不要到处直接传 `Long` / `String`

#### `error/`

- `ErrorCode`
- `BusinessException`
- `ValidationException`
- `ForbiddenOperationException`
- `ResourceNotFoundException`

#### `result/`

- `Result<T>`
- `CommandResult`
- `OperationResult`

#### `page/`

- `PageQuery`
- `PageResult<T>`
- `SortSpec`

#### `context/`

- `CurrentTenant`
- `CurrentUser`
- `RequestContext`
- `TraceContext`

#### `time/`

- `ClockProvider`
- `TimeRange`

#### `event/`

- `DomainEvent`
- `IntegrationEvent`

## 4. `module-iam`

### 4.1 推荐目录树

```text
com.yourcompany.aiplatform.iam
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ policy/
│  └─ repository/
├─ port/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   └─ config/
```

### 4.2 类名草案

#### `api/`

- `IamFacade`
- `AuthorizationFacade`

#### `application/command/`

- `CreateUserCommand`
- `UpdateUserCommand`
- `AssignRoleCommand`
- `CreateRoleCommand`
- `GrantPermissionCommand`

#### `application/query/`

- `GetUserQuery`
- `ListUsersQuery`
- `ListRolesQuery`
- `GetCurrentPrincipalQuery`

#### `application/dto/`

- `UserView`
- `RoleView`
- `PermissionView`
- `PrincipalView`

#### `application/service/`

- `UserCommandHandler`
- `RoleCommandHandler`
- `PermissionCommandHandler`
- `AuthorizationQueryService`

#### `domain/model/`

- `User`
- `Role`
- `Permission`
- `TenantMembership`
- `Principal`

#### `domain/policy/`

- `RoleAssignmentPolicy`
- `PermissionGrantPolicy`

#### `domain/repository/`

- `UserRepository`
- `RoleRepository`
- `PermissionRepository`

#### `port/outbound/`

- `PasswordHasherPort`
- `TokenIssuerPort`
- `PermissionCachePort`

#### `infrastructure/persistence/`

- `UserEntity`
- `RoleEntity`
- `PermissionEntity`
- `UserMapper`
- `RoleMapper`
- `PermissionMapper`

## 5. `module-control-plane`

### 5.1 推荐目录树

```text
com.yourcompany.aiplatform.controlplane
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ policy/
│  ├─ event/
│  └─ repository/
├─ port/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   └─ config/
```

### 5.2 类名草案

#### `api/`

- `ApplicationFacade`
- `ModelRegistryFacade`
- `PromptTemplateFacade`
- `WorkflowDefinitionFacade`
- `McpRegistryFacade`

#### `application/command/`

- `CreateApplicationCommand`
- `PublishApplicationVersionCommand`
- `CreatePromptTemplateCommand`
- `PublishPromptTemplateCommand`
- `RegisterModelProviderCommand`
- `RegisterModelCommand`
- `CreateWorkflowDefinitionCommand`
- `PublishWorkflowDefinitionCommand`
- `RegisterMcpServerCommand`

#### `application/query/`

- `GetApplicationQuery`
- `ListApplicationsQuery`
- `GetPromptTemplateQuery`
- `ListModelsQuery`
- `GetWorkflowDefinitionQuery`
- `ListMcpServersQuery`

#### `application/dto/`

- `ApplicationView`
- `ApplicationVersionView`
- `PromptTemplateView`
- `ModelProviderView`
- `ModelDefinitionView`
- `WorkflowDefinitionView`
- `McpServerView`

#### `application/service/`

- `ApplicationCommandHandler`
- `PromptTemplateCommandHandler`
- `ModelRegistryCommandHandler`
- `WorkflowDefinitionCommandHandler`
- `McpRegistryCommandHandler`

#### `domain/model/`

- `AiApplication`
- `AiApplicationVersion`
- `PromptTemplate`
- `PromptTemplateVersion`
- `ModelProvider`
- `ModelDefinition`
- `WorkflowDefinition`
- `WorkflowDefinitionVersion`
- `McpServerRegistration`
- `ToolBinding`
- `KnowledgeBinding`

#### `domain/policy/`

- `ApplicationPublishPolicy`
- `PromptPublishPolicy`
- `WorkflowPublishPolicy`
- `ModelRoutingPolicy`

#### `domain/event/`

- `ApplicationVersionPublishedEvent`
- `PromptTemplatePublishedEvent`
- `WorkflowDefinitionPublishedEvent`

#### `domain/repository/`

- `AiApplicationRepository`
- `PromptTemplateRepository`
- `ModelProviderRepository`
- `WorkflowDefinitionRepository`
- `McpServerRepository`

#### `port/outbound/`

- `SecretStorePort`
- `ConfigEncryptionPort`

## 6. `module-runtime`

### 6.1 推荐目录树

```text
com.yourcompany.aiplatform.runtime
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ policy/
│  ├─ event/
│  ├─ repository/
│  └─ service/
├─ port/
│  ├─ inbound/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   ├─ cache/
   └─ config/
```

### 6.2 类名草案

#### `api/`

- `RuntimeFacade`
- `ConversationFacade`
- `ExecutionRunFacade`

#### `application/command/`

- `StartChatCommand`
- `AppendUserMessageCommand`
- `StartExecutionRunCommand`
- `ApproveExecutionRunCommand`
- `ResumeExecutionRunCommand`
- `CancelExecutionRunCommand`
- `InvokeToolCommand`

#### `application/query/`

- `GetConversationQuery`
- `ListConversationMessagesQuery`
- `GetExecutionRunQuery`
- `ListExecutionRunsQuery`
- `GetExecutionTimelineQuery`

#### `application/dto/`

- `ConversationView`
- `MessageView`
- `ExecutionRunView`
- `ExecutionStepView`
- `ToolCallView`
- `ApprovalRequestView`

#### `application/service/`

- `ChatApplicationService`
- `StreamingChatApplicationService`
- `ExecutionRunCommandHandler`
- `ApprovalCommandHandler`
- `ConversationQueryService`
- `ExecutionRunQueryService`

#### `domain/model/`

- `Conversation`
- `Message`
- `ExecutionRun`
- `ExecutionStep`
- `ToolCallRecord`
- `ApprovalRequest`
- `RunStatus`
- `StepStatus`
- `MessageRole`

#### `domain/policy/`

- `RunResumePolicy`
- `ApprovalPolicy`
- `ToolPermissionPolicy`
- `StreamingPolicy`

#### `domain/event/`

- `ExecutionRunStartedEvent`
- `ExecutionRunPausedEvent`
- `ExecutionRunCompletedEvent`
- `ToolCalledEvent`
- `ApprovalRequestedEvent`

#### `domain/repository/`

- `ConversationRepository`
- `MessageRepository`
- `ExecutionRunRepository`
- `ExecutionStepRepository`
- `ToolCallRecordRepository`
- `ApprovalRequestRepository`

#### `domain/service/`

- `PromptAssemblyService`
- `ToolRoutingService`
- `RunTransitionService`

#### `port/outbound/`

- `ChatModelPort`
- `StreamingChatPort`
- `PromptTemplateResolverPort`
- `KnowledgeRetrievalPort`
- `ToolExecutionPort`
- `McpToolExecutionPort`
- `RunEventPublisherPort`
- `UsageMeteringPort`

## 7. `module-workflow`

### 7.1 推荐目录树

```text
com.yourcompany.aiplatform.workflow
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ policy/
│  ├─ repository/
│  └─ service/
├─ port/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   └─ scheduling/
```

### 7.2 类名草案

#### `api/`

- `WorkflowFacade`
- `WorkflowRunFacade`

#### `application/command/`

- `StartWorkflowRunCommand`
- `ExecuteWorkflowStepCommand`
- `RetryWorkflowStepCommand`
- `PauseWorkflowRunCommand`
- `ResumeWorkflowRunCommand`
- `ScheduleWorkflowRunCommand`

#### `application/query/`

- `GetWorkflowRunQuery`
- `ListWorkflowRunsQuery`
- `GetWorkflowStepRunQuery`

#### `application/dto/`

- `WorkflowRunView`
- `WorkflowStepRunView`
- `ScheduledWorkflowView`

#### `application/service/`

- `WorkflowRunCommandHandler`
- `WorkflowRunQueryService`
- `WorkflowSchedulerService`

#### `domain/model/`

- `WorkflowRun`
- `WorkflowStepRun`
- `WorkflowNode`
- `WorkflowEdge`
- `WorkflowTask`
- `ScheduledWorkflowRun`
- `WorkflowRunStatus`
- `WorkflowStepType`

#### `domain/policy/`

- `WorkflowTransitionPolicy`
- `WorkflowRetryPolicy`
- `ManualApprovalGatePolicy`

#### `domain/repository/`

- `WorkflowRunRepository`
- `WorkflowStepRunRepository`
- `ScheduledWorkflowRunRepository`

#### `domain/service/`

- `WorkflowExecutionPlanner`
- `WorkflowStateMachine`

#### `port/outbound/`

- `WorkflowDefinitionResolverPort`
- `TaskQueuePort`
- `SchedulerPort`
- `ApprovalGatewayPort`

## 8. `module-knowledge`

### 8.1 推荐目录树

```text
com.yourcompany.aiplatform.knowledge
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ policy/
│  ├─ event/
│  ├─ repository/
│  └─ service/
├─ port/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   └─ cache/
```

### 8.2 类名草案

#### `api/`

- `KnowledgeFacade`
- `KnowledgeIngestionFacade`
- `RetrievalFacade`

#### `application/command/`

- `CreateKnowledgeBaseCommand`
- `UploadKnowledgeDocumentCommand`
- `StartDocumentIngestionCommand`
- `ChunkDocumentCommand`
- `EmbedDocumentCommand`
- `DeleteKnowledgeDocumentCommand`

#### `application/query/`

- `GetKnowledgeBaseQuery`
- `ListKnowledgeBasesQuery`
- `GetKnowledgeDocumentQuery`
- `SearchKnowledgeQuery`

#### `application/dto/`

- `KnowledgeBaseView`
- `KnowledgeDocumentView`
- `KnowledgeChunkView`
- `RetrievalResultView`
- `DocumentIngestionRunView`

#### `application/service/`

- `KnowledgeBaseCommandHandler`
- `DocumentIngestionCommandHandler`
- `RetrievalQueryService`

#### `domain/model/`

- `KnowledgeBase`
- `KnowledgeDocument`
- `KnowledgeChunk`
- `DocumentIngestionRun`
- `EmbeddingTask`
- `RetrievalProfile`
- `ChunkMetadata`

#### `domain/policy/`

- `ChunkingPolicy`
- `EmbeddingPolicy`
- `RetentionPolicy`
- `RetrievalPolicy`

#### `domain/event/`

- `KnowledgeDocumentUploadedEvent`
- `KnowledgeDocumentParsedEvent`
- `KnowledgeDocumentEmbeddedEvent`

#### `domain/repository/`

- `KnowledgeBaseRepository`
- `KnowledgeDocumentRepository`
- `KnowledgeChunkRepository`
- `DocumentIngestionRunRepository`

#### `domain/service/`

- `DocumentIngestionService`
- `ChunkingService`
- `RetrievalService`

#### `port/outbound/`

- `ObjectStoragePort`
- `DocumentParserPort`
- `EmbeddingModelPort`
- `VectorStorePort`
- `RerankerPort`

## 9. `module-mcp`

### 9.1 推荐目录树

```text
com.yourcompany.aiplatform.mcp
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ policy/
│  ├─ repository/
│  └─ service/
├─ port/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   └─ cache/
```

### 9.2 类名草案

#### `api/`

- `McpFacade`
- `McpToolRegistryFacade`

#### `application/command/`

- `RegisterMcpServerCommand`
- `RefreshMcpToolsCommand`
- `ExecuteMcpToolCommand`
- `DisableMcpServerCommand`

#### `application/query/`

- `ListMcpServersQuery`
- `ListMcpToolsQuery`
- `GetMcpServerHealthQuery`

#### `application/dto/`

- `McpServerView`
- `McpToolDescriptorView`
- `McpToolExecutionView`

#### `application/service/`

- `McpServerCommandHandler`
- `McpToolExecutionService`
- `McpRegistryQueryService`

#### `domain/model/`

- `McpServer`
- `McpToolDescriptor`
- `McpToolExecution`
- `ToolNamespace`
- `McpCredentialRef`

#### `domain/policy/`

- `McpExecutionPolicy`
- `McpPermissionPolicy`
- `ToolNamespacePolicy`

#### `domain/repository/`

- `McpServerRepository`
- `McpToolDescriptorRepository`
- `McpToolExecutionRepository`

#### `domain/service/`

- `McpRegistryService`
- `McpToolRoutingService`

#### `port/outbound/`

- `McpClientPort`
- `McpHealthCheckPort`
- `SecretReferencePort`

## 10. `module-observability`

### 10.1 推荐目录树

```text
com.yourcompany.aiplatform.observability
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ repository/
│  └─ service/
├─ port/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   ├─ trace/
   └─ metrics/
```

### 10.2 类名草案

#### `api/`

- `TraceFacade`
- `ExecutionLogFacade`
- `CostFacade`

#### `application/command/`

- `RecordPromptSnapshotCommand`
- `RecordResponseSnapshotCommand`
- `RecordToolExecutionLogCommand`
- `RecordModelUsageCommand`

#### `application/query/`

- `GetTraceQuery`
- `GetExecutionTimelineQuery`
- `ListRunLogsQuery`
- `GetCostSummaryQuery`

#### `application/dto/`

- `TraceView`
- `ExecutionTimelineView`
- `PromptSnapshotView`
- `ToolExecutionLogView`
- `CostSummaryView`

#### `application/service/`

- `TraceCommandHandler`
- `TraceQueryService`
- `CostQueryService`

#### `domain/model/`

- `TraceRecord`
- `PromptSnapshot`
- `ResponseSnapshot`
- `ToolExecutionLog`
- `CostRecord`
- `ExecutionTimeline`

#### `domain/repository/`

- `TraceRecordRepository`
- `PromptSnapshotRepository`
- `ResponseSnapshotRepository`
- `ToolExecutionLogRepository`
- `CostRecordRepository`

#### `domain/service/`

- `ExecutionTimelineAssembler`
- `CostAggregationService`

#### `port/outbound/`

- `TraceSinkPort`
- `MetricsPublisherPort`

## 11. `module-evaluation`

### 11.1 推荐目录树

```text
com.yourcompany.aiplatform.evaluation
├─ api/
├─ application/
│  ├─ command/
│  ├─ query/
│  ├─ dto/
│  └─ service/
├─ domain/
│  ├─ model/
│  ├─ policy/
│  ├─ repository/
│  └─ service/
├─ port/
│  └─ outbound/
└─ infrastructure/
   ├─ persistence/
   └─ config/
```

### 11.2 类名草案

#### `api/`

- `EvaluationFacade`

#### `application/command/`

- `CreateEvaluationDatasetCommand`
- `CreateEvaluationCaseCommand`
- `RunEvaluationCommand`
- `CompareEvaluationRunsCommand`

#### `application/query/`

- `GetEvaluationDatasetQuery`
- `ListEvaluationRunsQuery`
- `GetEvaluationRunReportQuery`

#### `application/dto/`

- `EvaluationDatasetView`
- `EvaluationCaseView`
- `EvaluationRunView`
- `EvaluationReportView`

#### `application/service/`

- `EvaluationCommandHandler`
- `EvaluationQueryService`

#### `domain/model/`

- `EvaluationDataset`
- `EvaluationCase`
- `EvaluationRun`
- `EvaluationScore`
- `RegressionComparison`

#### `domain/policy/`

- `EvaluationPolicy`
- `RegressionGatePolicy`

#### `domain/repository/`

- `EvaluationDatasetRepository`
- `EvaluationCaseRepository`
- `EvaluationRunRepository`

#### `domain/service/`

- `EvaluationRunner`
- `RegressionComparisonService`

#### `port/outbound/`

- `JudgeModelPort`
- `EvaluationExecutionPort`

## 12. `integration-llm`

### 12.1 推荐目录树

```text
com.yourcompany.aiplatform.integration.llm
├─ config/
├─ adapter/
├─ provider/
│  ├─ openai/
│  ├─ anthropic/
│  └─ dashscope/
└─ support/
```

### 12.2 类名草案

#### `config/`

- `LlmAutoConfiguration`
- `LlmProviderProperties`

#### `adapter/`

- `ChatModelPortAdapter`
- `StreamingChatPortAdapter`
- `EmbeddingModelPortAdapter`

#### `provider/openai/`

- `OpenAiChatModelAdapter`
- `OpenAiEmbeddingAdapter`

#### `provider/anthropic/`

- `AnthropicChatModelAdapter`

#### `provider/dashscope/`

- `DashScopeChatModelAdapter`
- `DashScopeEmbeddingAdapter`

#### `support/`

- `ChatRequestMapper`
- `ToolSchemaMapper`
- `UsageStatsMapper`

## 13. 其他 `integrations/*` 模块类名草案

### `integration-vectorstore`

- `PgVectorStoreAdapter`
- `MilvusVectorStoreAdapter`
- `QdrantVectorStoreAdapter`
- `VectorSearchRequestMapper`

### `integration-object-storage`

- `MinioObjectStorageAdapter`
- `S3ObjectStorageAdapter`
- `StorageObjectMapper`

### `integration-python-sandbox`

- `DockerPythonSandboxAdapter`
- `PythonExecutionRequest`
- `PythonExecutionResult`
- `SandboxResourcePolicy`

### `integration-document-parser`

- `TikaDocumentParserAdapter`
- `PdfDocumentParserAdapter`
- `OfficeDocumentParserAdapter`
- `MarkdownDocumentParserAdapter`
- `ParsedDocument`

### `integration-http-tools`

- `GenericHttpToolAdapter`
- `WebhookToolAdapter`
- `HttpToolRequestMapper`
- `HttpToolResponseMapper`

## 14. `platform-server`

### 14.1 推荐目录树

```text
com.yourcompany.aiplatform.server
├─ config/
├─ security/
├─ web/
│  ├─ admin/
│  ├─ runtime/
│  └─ internal/
└─ support/
```

### 14.2 类名草案

#### 根类

- `PlatformServerApplication`

#### `config/`

- `SecurityConfig`
- `JacksonConfig`
- `OpenApiConfig`
- `ModuleWiringConfig`
- `WebMvcConfig`

#### `security/`

- `AuthenticationPrincipalResolver`
- `JwtAuthenticationFilter`
- `AccessDeniedHandlerImpl`

#### `web/admin/`

- `AdminApplicationController`
- `AdminModelController`
- `AdminPromptController`
- `AdminWorkflowController`
- `AdminKnowledgeController`
- `AdminMcpController`
- `AdminEvaluationController`

#### `web/runtime/`

- `RuntimeChatController`
- `RuntimeConversationController`
- `RuntimeExecutionRunController`
- `RuntimeApprovalController`
- `RuntimeSseController`

#### `web/internal/`

- `InternalHealthController`
- `InternalCallbackController`

#### `support/`

- `GlobalExceptionHandler`
- `ApiErrorResponseFactory`
- `RequestContextFilter`

## 15. `platform-worker`

### 15.1 推荐目录树

```text
com.yourcompany.aiplatform.worker
├─ config/
├─ job/
├─ workflow/
├─ knowledge/
├─ evaluation/
└─ support/
```

### 15.2 类名草案

#### 根类

- `PlatformWorkerApplication`

#### `config/`

- `WorkerSchedulerConfig`
- `WorkerWiringConfig`

#### `job/`

- `WorkerJobDispatcher`
- `WorkerJobPoller`
- `WorkerHeartbeatReporter`

#### `workflow/`

- `WorkflowRunWorker`
- `WorkflowStepExecutor`

#### `knowledge/`

- `KnowledgeIngestionWorker`
- `DocumentEmbeddingWorker`

#### `evaluation/`

- `EvaluationRunWorker`

#### `support/`

- `WorkerExceptionHandler`
- `WorkerRunContextFactory`

## 16. 第一批真正建议你建的类

如果你现在就要起仓库，不要一次建 200 个类。

建议第一批只建下面这些。

### 16.1 `module-common`

- `Result`
- `BusinessException`
- `RequestContext`
- `TraceContext`
- `ApplicationId`
- `ExecutionRunId`

### 16.2 `module-control-plane`

- `AiApplication`
- `AiApplicationVersion`
- `PromptTemplate`
- `ModelDefinition`
- `AiApplicationRepository`
- `ApplicationFacade`

### 16.3 `module-runtime`

- `Conversation`
- `Message`
- `ExecutionRun`
- `ToolCallRecord`
- `RuntimeFacade`
- `StartChatCommand`
- `StartExecutionRunCommand`
- `ChatModelPort`
- `KnowledgeRetrievalPort`
- `ToolExecutionPort`

### 16.4 `module-knowledge`

- `KnowledgeBase`
- `KnowledgeDocument`
- `KnowledgeChunk`
- `KnowledgeFacade`
- `DocumentParserPort`
- `EmbeddingModelPort`
- `VectorStorePort`
- `ObjectStoragePort`

### 16.5 `platform-server`

- `PlatformServerApplication`
- `AdminApplicationController`
- `RuntimeChatController`
- `GlobalExceptionHandler`

### 16.6 `platform-worker`

- `PlatformWorkerApplication`
- `WorkerJobDispatcher`
- `KnowledgeIngestionWorker`

## 17. 一条最小调用链对应到哪些类

### 场景：知识库问答

```text
RuntimeChatController
  -> RuntimeFacade
  -> ChatApplicationService
  -> PromptTemplateResolverPort
  -> KnowledgeRetrievalPort
  -> ChatModelPort
  -> ToolExecutionPort (optional)
  -> ExecutionRunRepository / MessageRepository
  -> TraceFacade / CostFacade
```

### 场景：文档导入

```text
AdminKnowledgeController
  -> KnowledgeFacade
  -> DocumentIngestionCommandHandler
  -> ObjectStoragePort
  -> enqueue ingestion job
  -> KnowledgeIngestionWorker
  -> DocumentParserPort
  -> EmbeddingModelPort
  -> VectorStorePort
  -> KnowledgeDocumentRepository / KnowledgeChunkRepository
```

### 场景：工作流执行

```text
RuntimeExecutionRunController
  -> RuntimeFacade
  -> ExecutionRunCommandHandler
  -> enqueue workflow job
  -> WorkflowRunWorker
  -> WorkflowExecutionPlanner
  -> WorkflowDefinitionResolverPort
  -> ChatModelPort / McpClientPort / ToolExecutionPort
  -> ExecutionStepRepository
  -> ApprovalRequestRepository (if needed)
```

## 18. 这份草案最重要的目的

不是把类名一次性定死。

而是帮你避免三种最常见的起仓库错误：

1. 所有业务都堆在 `service/impl`
2. 所有外部依赖直接混进核心模块
3. 类名和边界从第一天起就不一致

如果你能用这份草案把第一批模块和类命名统一起来，后面扩展：

- workflow
- MCP
- evaluation
- multi-agent
- studio

都会轻松很多。

