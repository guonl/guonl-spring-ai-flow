# Spring AI Flow 架构说明

> 业务 × 大模型编排引擎：将业务场景抽象为可视化 DAG 流程，节点与大模型（或工具/知识库/子流程）交互，支持串行、并行、条件分支、汇流聚合等任意编排形态。

## 1. 系统定位

Spring AI Flow 是一个轻量级的 LLM 应用编排平台，核心能力：

- **流程编排**：节点 + 连线构成 DAG，画布拖拽编辑，支持并行分支、条件路由与汇流聚合
- **13 种节点类型**：文本理解、图片识别、JSON 处理、Excel 处理、组合处理（多模态）、条件路由、工具调用、知识检索（RAG）、图片生成、内容审核、输出评估、子流程、聚合
- **变量协议**：节点提示词可通过 `{{变量}}` 引用流程输入与上游节点产出
- **输出契约**：每个节点可声明 JSON 字段契约（字段名 / 说明 / 是否必填），模型按契约结构化返回
- **内置工具与 MCP**：工具调用节点挂载内置工具（时间/计算/网页抓取/JSONPath）；同时支持 MCP Server（把流程暴露为 MCP 工具）与 MCP Client（发现并调用外部 MCP 工具）
- **会话记忆**：节点可携带同一会话最近 N 轮对话历史（Spring AI JDBC Chat Memory，按会话ID隔离）
- **RAG 知识库**：文档上传、切片向量化、检索调试；知识检索节点自动召回参考片段增强问答
- **多模态生成与安全**：图片生成（文生图）、音频转录、内容审核（ModerationModel SPI）、输出评估（LLM-as-judge）
- **双运行模式**：`mock`（本地模拟，无需模型密钥）/ `llm`（OpenAI 兼容协议直连大模型）
- **实时可观测**：提交运行即返回 runId，SSE 流式推送节点增量输出（轮询兜底），节点状态、耗时、Token 消耗实时可见

## 2. 技术栈

| 层次 | 选型 | 说明 |
|---|---|---|
| 基础框架 | Spring Boot 4.1.1 / JDK 17 | 单体 Web 应用，内嵌 Tomcat（端口 8080），actuator 健康检查 |
| 大模型接入 | Spring AI 2.0.1（spring-ai-starter-model-openai） | 基于 openai-java SDK，走 OpenAI 兼容协议（`base-url` 须以 `/v1` 结尾） |
| 会话记忆 | spring-ai-starter-chat-memory-repository-jdbc | Chat Memory 落 MySQL `SPRING_AI_CHAT_MEMORY` 表（启动自动建表） |
| MCP | Spring AI MCP（server + client starter） | 流程暴露为 MCP 工具（server）+ 发现调用外部 MCP 工具（client，可开关） |
| 页面渲染 | Thymeleaf + 原生 JS/CSS | 服务端渲染 7 个页面，前端无框架依赖，每页一个独立 JS |
| Excel 解析 | Apache POI 5.3.0 | xlsx/xls 解析为行 JSON 送入模型 |
| 持久化 | MySQL 8 + MyBatis（mybatis-spring-boot-starter 4.0.0） | 流程定义 / 运行历史 / 节点执行明细 / 知识库全部入库（guonl_ai_flow），写穿式落库 |
| 接口文档 | springdoc-openapi 3.1.1（Swagger UI） | `/swagger-ui.html` 在线查看与调试全部 REST 接口 |
| 构建 | Maven | spring-boot-maven-plugin 打可执行 jar |

## 3. 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                        浏览器（7 页面）                        │
│ 工作台/流程库/知识库/流程编辑器/运行页/场景体验/运行历史          │
│      (Thymeleaf 渲染 + 各页独立 JS，SSE 流式 + 轮询兜底)       │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTP（页面 + REST + SSE）
┌──────────────────────────▼───────────────────────────────────┐
│  web 层  com.guonl.flow.web                                  │
│  ├─ PageController          页面路由（/、/flows、/knowledge…） │
│  ├─ FlowApiController       流程 CRUD / 分页检索 / 校验 / 运行 │
│  ├─ RunApiController        运行分页检索 / 快照 / SSE 流式 / 统计│
│  ├─ KnowledgeApiController  知识库与文档管理 / 检索测试        │
│  ├─ PlaygroundApiController 即席体验 / SSE 流式                │
│  ├─ ConfigApiController     provider 与默认模型下发             │
│  ├─ InputAssembler          multipart 输入装配（文本/图片/Excel/音频）│
│  └─ ApiExceptionHandler     统一异常 → {error}                 │
├──────────────────────────────────────────────────────────────┤
│  core 层  com.guonl.flow.core                                │
│  ├─ engine  FlowEngine（DAG 调度/条件路由/并行/汇流）           │
│  │          FlowRunService（异步门面） / RunStreamHub（SSE 推送）│
│  │          VarResolver（变量协议） / FlowInput / 执行快照模型  │
│  ├─ node    NodeHandler SPI + 13 类节点处理器 + 注册表         │
│  ├─ model   FlowDefinition / NodeDefinition / EdgeDefinition  │
│  │          NodeType / OutputSpec / PageResult（通用分页）      │
│  ├─ tool    FlowTools（内置工具：now/calc/httpGet/jsonPath）   │
│  └─ store   FlowStore（定义 ↔ DB） / RunStore（运行 ↔ DB）     │
│              FlowSeeder（预置示例流程）                        │
├──────────────────────────────────────────────────────────────┤
│  knowledge 层  com.guonl.flow.knowledge                       │
│  ├─ KnowledgeService       知识库/文档管理 + 向量检索          │
│  ├─ VectorStoreManager     SimpleVectorStore 管理（JSON 落盘）│
│  ├─ MockEmbeddingModel     mock 嵌入模型（hash 向量）          │
│  └─ KnowledgeProperties / KnowledgeConfiguration / RetrievedChunk│
├──────────────────────────────────────────────────────────────┤
│  mcp 层  com.guonl.flow.mcp                                   │
│  ├─ FlowMcpToolCallbackProvider  把已暴露流程注册为 MCP 工具    │
│  ├─ FlowMcpToolCallback         流程 → MCP 工具适配（同步执行） │
│  ├─ TracedMcpToolCallback       外部 MCP 工具调用轨迹记录       │
│  └─ McpToolController           GET /api/mcp/tools 工具清单    │
├──────────────────────────────────────────────────────────────┤
│  db 层  com.guonl.flow.db                                    │
│  ├─ entity  FlowDefinitionDO / FlowRunDO /                    │
│  │          FlowNodeExecutionDO / RunStatsDO /                │
│  │          KnowledgeBaseDO / KnowledgeDocDO                  │
│  └─ mapper  FlowDefinitionMapper / FlowRunMapper /            │
│             FlowNodeExecutionMapper / KnowledgeBaseMapper /    │
│             KnowledgeDocMapper（接口 + XML）                   │
├──────────────────────────────────────────────────────────────┤
│  ai 层  com.guonl.flow.ai                                    │
│  ├─ AiInvoker 接口（invoke 阻塞 / stream 流式 / MediaItem）    │
│  ├─ SpringAiInvoker   Spring AI ChatClient 实现（llm 模式）    │
│  ├─ MockAiInvoker     本地模拟实现（mock 模式）                │
│  ├─ MockImageModel / MockModerationModel / MockTranscriptionModel│
│  └─ JsonExtractor     模型输出中的 JSON 提取                   │
├──────────────────────────────────────────────────────────────┤
│  基础设施：AiConfiguration（provider 切换）/ MultimodalConfiguration│
│           AiProperties / FlowProperties / KnowledgeProperties │
│           ExcelParser（POI）/ application.yml                 │
│           MySQL 数据源 / MyBatis / springdoc / actuator        │
└──────────────────────────────────────────────────────────────┘
```

依赖方向严格自上而下：`web → core → ai`，`ai` 层不感知流程概念，`core` 层通过 `AiInvoker` 接口与模型解耦；`core.node` 的 RAG 节点经 `knowledge.KnowledgeService` 检索，MCP 层经 `core.store` 执行流程；`core.store` 经 db 层 Mapper 访问 MySQL，引擎与控制器不直接接触 SQL。

## 4. 目录结构

```
guonl-spring-ai-flow
├── pom.xml
├── sqls/                                # 数据库初始化脚本（手工或新会话由 MCP 执行）
│   ├── 01_ddl.sql                       #   建库 guonl_ai_flow + 五张业务表 DDL
│   └── 02_dml.sql                       #   3 个示例流程种子数据（幂等 INSERT IGNORE）
├── docs/                                # 本文档目录
└── src/main/
    ├── java/com/guonl/
    │   ├── GuonlSpringAiFlowApplication.java  # 主类（@MapperScan 扫描 db.mapper）
    │   └── flow/
    │   ├── ai/                          # 模型调用抽象
    │   │   ├── AiInvoker.java           #   接口：InvokeRequest/InvokeResult/MediaItem
    │   │   ├── SpringAiInvoker.java     #   llm 实现（含错误描述增强、图片下载、音频转录）
    │   │   ├── MockAiInvoker.java       #   mock 实现（文本/JSON/Excel/图片理解模拟）
    │   │   ├── MockImageModel.java      #   mock 文生图模型（SVG 占位图）
    │   │   ├── MockModerationModel.java #   mock 审核模型
    │   │   ├── MockTranscriptionModel.java # mock 音频转录模型
    │   │   └── JsonExtractor.java       #   从模型文本输出中提取 JSON
    │   ├── config/
    │   │   ├── AiProperties.java        #   flow.ai.* 配置绑定（含重试/降级/MCP暴露）
    │   │   ├── FlowProperties.java      #   flow.excel.* 配置绑定
    │   │   ├── AiConfiguration.java     #   provider=mock/llm 装配 AiInvoker
    │   │   └── MultimodalConfiguration.java # 图片生成/审核/转录模型装配（llm/mock）
    │   ├── core/
    │   │   ├── engine/
    │   │   │   ├── FlowEngine.java      #   DAG 拓扑调度器（校验/条件路由/并行/汇流/取消/超时）
    │   │   │   ├── FlowRunService.java  #   提交即返回 runId + 后台异步执行
    │   │   │   ├── FlowExecution.java   #   运行快照（Status: PENDING/RUNNING/SUCCESS/FAILED）
    │   │   │   ├── NodeExecution.java   #   节点快照（含 output/parsedJson/model/tokens/cost）
    │   │   │   ├── FlowInput.java       #   流程输入（text/images/excel/audio/conversationId）
    │   │   │   ├── NodeOutput.java      #   节点产出（text + JsonNode）
    │   │   │   ├── VarResolver.java     #   {{变量}} 解析器
    │   │   │   └── RunStreamHub.java    #   SSE 流式推送（节点增量输出 → 前端 EventSource）
    │   │   ├── model/
    │   │   │   ├── FlowDefinition.java  #   流程定义（nodes + edges）
    │   │   │   ├── NodeDefinition.java  #   节点定义（prompt/输出契约/模型覆盖/路由分支/记忆/知识库/子流程/聚合策略/画布坐标）
    │   │   │   ├── EdgeDefinition.java  #   连线（from → to + 分支条件）
    │   │   │   ├── NodeType.java        #   13 种节点类型枚举（label/color/icon）
    │   │   │   ├── OutputSpec.java      #   输出契约（type=json/text + fields）
    │   │   │   └── PageResult.java      #   通用分页结构（items/total/page/size）
    │   │   ├── node/
    │   │   │   ├── NodeHandler.java             # 处理器 SPI（supports + execute）
    │   │   │   ├── AbstractNodeHandler.java     # 模板方法：固定执行流水线（记忆/重试在此层）
    │   │   │   ├── TextNodeHandler.java         # 文本理解
    │   │   │   ├── ImageNodeHandler.java        # 图片识别（强校验图片素材）
    │   │   │   ├── JsonNodeHandler.java         # JSON 处理（强校验输入为合法 JSON）
    │   │   │   ├── ExcelNodeHandler.java        # Excel 处理（强校验表格数据来源）
    │   │   │   ├── CombinedNodeHandler.java     # 组合处理（自动携带全部素材）
    │   │   │   ├── RouterNodeHandler.java       # 条件路由（候选分支 → {"route": "分支名"}）
    │   │   │   ├── ToolNodeHandler.java         # 工具调用（挂载内置工具集）
    │   │   │   ├── KnowledgeNodeHandler.java    # 知识检索（RAG 参考片段注入）
    │   │   │   ├── ImageGenNodeHandler.java     # 图片生成（文生图，产出可下载 URL）
    │   │   │   ├── ModerationNodeHandler.java   # 内容审核（ModerationModel SPI，不走会话模型）
    │   │   │   ├── EvaluateNodeHandler.java     # 输出评估（LLM-as-judge：score/passed/reason）
    │   │   │   ├── SubflowNodeHandler.java      # 子流程（引用其他流程作为宏）
    │   │   │   ├── AggregateNodeHandler.java    # 聚合（concat/jsonMerge/template，不经模型）
    │   │   │   ├── NodeHandlerRegistry.java     # NodeType → Handler 注册表
    │   │   │   ├── NodeContext.java             # 节点执行上下文（输入 + 全部祖先产出）
    │   │   │   └── PromptKit.java               # 输出要求段落组装（处理器/Playground 复用）
    │   │   ├── tool/
    │   │   │   └── FlowTools.java       #   内置工具：now/calc/httpGet/jsonPath
    │   │   └── store/
    │   │       ├── FlowStore.java       #   流程定义存储（MySQL flow_definition 表）
    │   │       ├── RunStore.java        #   运行历史存储（MySQL flow_run + flow_node_execution）
    │   │       └── FlowSeeder.java      #   首次启动预置示例流程（写入 DB）
    │   ├── knowledge/                   # RAG 知识库
    │   │   ├── KnowledgeService.java    #   知识库/文档管理 + 向量检索
    │   │   ├── VectorStoreManager.java  #   SimpleVectorStore 管理（{kbId}.json 落盘）
    │   │   ├── MockEmbeddingModel.java  #   mock 嵌入模型（hash 向量，无需密钥）
    │   │   ├── KnowledgeProperties.java #   flow.knowledge.* 配置绑定
    │   │   ├── KnowledgeConfiguration.java # 嵌入模型/VectorStore 装配
    │   │   └── RetrievedChunk.java      #   命中片段（文本/得分/文档名）
    │   ├── mcp/                         # MCP 协议集成
    │   │   ├── FlowMcpToolCallbackProvider.java # 已暴露流程 → MCP 工具注册
    │   │   ├── FlowMcpToolCallback.java #   流程 → MCP 工具适配（同步执行等终态）
    │   │   ├── TracedMcpToolCallback.java # 外部 MCP 工具调用轨迹记录
    │   │   └── McpToolController.java   #   GET /api/mcp/tools 工具清单
    │   ├── db/                          # 数据访问层（MyBatis）
    │   │   ├── entity/
    │   │   │   ├── FlowDefinitionDO.java       # flow_definition 表映射
    │   │   │   ├── FlowRunDO.java              # flow_run 表映射
    │   │   │   ├── FlowNodeExecutionDO.java    # flow_node_execution 表映射
    │   │   │   ├── RunStatsDO.java             # 统计聚合查询结果
    │   │   │   ├── KnowledgeBaseDO.java        # flow_knowledge 表映射
    │   │   │   └── KnowledgeDocDO.java         # flow_knowledge_doc 表映射
    │   │   └── mapper/
    │   │       ├── FlowDefinitionMapper.java   # 定义 CRUD + 分页检索
    │   │       ├── FlowRunMapper.java          # 运行插入/完结/统计/分页检索
    │   │       ├── FlowNodeExecutionMapper.java# 节点批量插入/状态更新/查询
    │   │       ├── KnowledgeBaseMapper.java    # 知识库 CRUD
    │   │       └── KnowledgeDocMapper.java     # 知识库文档 CRUD
    │   ├── excel/
    │   │   └── ExcelParser.java         #   POI 解析（表头 + 数据行 → JSON）
    │   └── web/                         # 见第 3 节
    └── resources/
        ├── application.yml
        ├── mapper/                      # MyBatis XML（流程三表 + 知识库两表）
        ├── templates/                   # dashboard/flows/knowledge/flow-editor/run/runs/playground + layout
        └── static/
            ├── css/flow.css
            └── js/                      # common.js + 每页一个 JS（editor/run/knowledge/playground…）
```

## 5. 核心机制

### 5.1 DAG 执行引擎（FlowEngine）

流程执行采用**入度驱动的拓扑调度**：

1. **校验**（`validate`）：节点非空、ID 唯一、prompt 必填、连线端点存在、无自环，Kahn 算法检测环
2. **预备**（`prepare`）：静态校验后构建运行快照（runId = `r_` + 12 位随机串），全部节点置 `PENDING`
3. **调度**（`run`）：
   - 入度为 0 的根节点立即提交线程池（`CompletableFuture.supplyAsync`），**多个就绪节点天然并行**
   - 节点成功后递减下游入度，减至 0 的下游（汇流点）被调度执行
   - **条件路由激活**：ROUTER 节点的出边按路由结果筛选——模型输出 `{"route":"分支名"}` 后，仅分支条件匹配的连线（未命中则走「默认分支」）继续递减下游入度，其余分支下游不再调度
   - 任一节点失败：递归取消全部 `PENDING` 状态下游（置 `CANCELLED`），流程整体 `FAILED`
   - 节点超时 `orTimeout(flow.ai.node-timeout-seconds)`，默认 180s
   - `CountDownLatch` 等待全部节点终态，汇总流程状态与耗时
4. **上下文传递**：节点执行前 BFS 收集**全部传递祖先**的成功产出（`NodeContext`），保证汇流节点可引用任意上游

线程池说明：节点执行池（`flow.ai.executor-pool-size`，默认 8）与运行线程池（固定 4，`FlowRunService` 自持）相互独立，避免运行等待与节点调度争抢。

### 5.2 异步运行门面（FlowRunService）

「提交即返回 runId + SSE 流式（轮询兜底）」模式：

```
POST /api/flows/{id}/run（multipart 输入）
  → InputAssembler.assemble（文本 / 图片URL / 上传图片 / Excel / 音频 / 会话ID → FlowInput）
  → FlowRunService.start
      ├─ FlowEngine.prepare：校验 + 构建快照（RUNNING）
      ├─ RunStore.save：注册快照（获得 runId）
      └─ runExecutor.submit：后台执行 FlowEngine.run 至终态
  ← 立即返回快照（含 runId）
前端 EventSource 订阅 GET /api/runs/{runId}/stream：RunStreamHub 实时推送节点增量输出
  data:{"nodeId","delta"} … data:{"done":true}
SSE 异常时自动降级为 setInterval 轮询 GET /api/runs/{runId} 渲染 DAG 实时状态
```

### 5.3 变量协议（VarResolver）

节点提示词支持占位符引用上下文数据（解析时预展开 JSON 全部叶子路径）：

| 变量 | 含义 |
|---|---|
| `{{input}}` / `{{input.json}}` | 流程输入文本（语义别名） |
| `{{input.rows}}` | Excel 解析结果（JSON 文本） |
| `{{nodeId}}` / `{{nodeId.output}}` | 上游节点原始输出 |
| `{{nodeId.json}}` | 上游节点 JSON 输出（整体） |
| `{{nodeId.json.字段.子字段}}` | 上游 JSON 字段值（支持多级下钻、数组用 1 起始下标） |

**零模板成本**：提示词中未使用任何变量时，处理器自动把流程输入、Excel 数据、上游产出追加为【上下文数据】段，单节点场景无需学习变量语法。

### 5.4 节点处理器流水线（AbstractNodeHandler）

所有节点类型复用同一条模板方法流水线：

```
① 构建变量表（流程输入 + 全部祖先产出）
② 解析 {{变量}}；未用变量则自动追加上下文段
③ 拼接输出要求（PromptKit：JSON 字段契约 / 纯文本要求）
④ 收集多模态素材（输入图片 + 节点绑定 imageUrl）
⑤ 调用 AiInvoker（节点 model/temperature 覆盖）
⑥ 解析输出：json 契约 → JsonExtractor 提取，失败即节点失败
```

各类型差异化点：

| 处理器 | 节点类型 | 说明（默认角色 / 特殊行为） |
|---|---|---|
| TextNodeHandler | TEXT | 文本理解与信息提取专家 |
| ImageNodeHandler | IMAGE | 图像内容识别助手；必须有图片素材（运行时上传或节点绑定 URL） |
| JsonNodeHandler | JSON | JSON 数据处理器；无上游时流程输入必须是合法 JSON |
| ExcelNodeHandler | EXCEL | 表格数据分析引擎；必须有表格数据来源（上传/上游） |
| CombinedNodeHandler | COMBINED | 多源数据综合处理引擎；自动携带全部素材与上下文 |
| RouterNodeHandler | ROUTER | 条件路由器：从候选分支（`routes`）选一个，输出 `{"route":"分支名"}`；引擎按路由结果激活下游连线 |
| ToolNodeHandler | TOOL | 工具调用：挂载内置工具集（FlowTools），模型按需自主调用后作答 |
| KnowledgeNodeHandler | KNOWLEDGE | RAG 知识检索：向量检索绑定知识库（`knowledgeBaseId`/`topK`），命中片段注入提示词并持久化为轨迹 |
| ImageGenNodeHandler | IMAGE_GEN | 图片生成：提示词文生图（ImageModel SPI），产出可下载 URL |
| ModerationNodeHandler | MODERATION | 内容审核：ModerationModel SPI，输出 `{flagged, categories}`，不走会话大模型链路（直接实现 NodeHandler） |
| EvaluateNodeHandler | EVALUATE | 输出评估：LLM-as-judge，输出 `score/passed/reason/dimensions`，可接条件路由走重试或人工兜底 |
| SubflowNodeHandler | SUBFLOW | 子流程：引用 `subflowId` 指定的其他流程作为宏，同步执行后引用其产出 |
| AggregateNodeHandler | AGGREGATE | 聚合：不经模型合并直接上游输出（concat/jsonMerge/template，`prompt` 语义随策略变化），直接实现 NodeHandler |

> 会话记忆与重试在 `AbstractNodeHandler` 模板层统一实现：节点配置 `memoryTurns` 时注入同一 `conversationId` 最近 N 轮历史；调用/输出解析失败按 `invoke-retry-attempts`/`output-retry-attempts` 重试，仍失败可用 `fallback-model` 降级再试。

### 5.5 输出契约（OutputSpec）

- `type=json`：可声明 `fields[]`（`name` 字段名 / `desc` 说明 / `required` 是否必填），PromptKit 生成「仅输出一个 JSON 对象」的强约束；字段为空则由模型自行设计结构
- `type=text`：要求直接输出结果内容，不附加解释或代码块标记

### 5.6 模型调用层（AiInvoker）

- **SpringAiInvoker**（llm 模式）：基于 Spring AI `ChatClient`，按节点覆盖 model（节点 model > 图片类节点 vision-model > text-model），图片素材下载转 base64（独立超时 `imageDownloadTimeoutSeconds`），支持 `stream()` 流式回调；错误描述增强——响应体为空时提示「常见于网关或安全代理拦截」
- **MockAiInvoker**（mock 模式）：按节点类型返回模拟数据（模拟延迟 `mock-delay-millis=900`），无需任何密钥即可体验完整交互
- **多模态模型装配**（`MultimodalConfiguration`）：图片生成 / 内容审核 / 音频转录按 provider 装配 llm（Spring AI ImageModel / ModerationModel / TranscriptionModel）或 mock（`MockImageModel` SVG 占位图 / `MockModerationModel` / `MockTranscriptionModel`）
- **JsonExtractor**：容忍模型输出带 ```json 代码块标记或前后缀文本，鲁棒提取 JSON

### 5.7 存储与持久化（MySQL + MyBatis）

数据库 `guonl_ai_flow`（utf8mb4），五张业务表 + 会话记忆表承载全部业务数据，初始化脚本见 `sqls/`：

| 表 | 内容 | 关键列 | 写入时机 |
|---|---|---|---|
| `flow_definition` | 流程定义 | `nodes`/`edges`（JSON 文本）、`uk` 主键 id | 编辑器保存 / 复制 / Seeder 播种 |
| `flow_run` | 运行主记录 | `status`、`cost_millis`、`total_tokens`、`start_at`（BIGINT epoch 毫秒，索引） | 提交运行 insert；终态 updateFinish |
| `flow_node_execution` | 节点执行明细 | `run_id + node_id`（唯一键）、`seq`、`output`（TEXT）、`parsed_json`（JSON）、`error`（2048） | 提交时批量 insert（PENDING）；状态变更写穿 update |
| `flow_knowledge` | 知识库元数据 | `name`、`description` | 知识库页创建 |
| `flow_knowledge_doc` | 知识库文档 | `kb_id`、`chunk_count`、`chunk_ids`（向量切片ID列表）、`status` | 文档上传切片入库 |
| `SPRING_AI_CHAT_MEMORY` | 会话记忆（Spring AI 自动建表） | `conversation_id`、内容片段 | 配置 `memoryTurns` 的节点按 `conversationId` 读写 |

设计约定：

- **全列 NOT NULL**：除 DATE/DATETIME/JSON/TEXT 类型外，其余列均带默认值（`DEFAULT ''` / `DEFAULT 0` 等）
- **写穿式落库**：`FlowEngine` 在节点进入 RUNNING、到达终态、被级联取消三个时点立即 `RunStore.updateNode`；流程结束时 `finish()` 汇总耗时与 tokens，无需轮询回填
- **空值往返**：写库时 null → `''` / `0`（满足 NOT NULL），读出时 `''` 还原为 null，保证领域模型语义不变
- **无本地文件**：流程定义不再落盘 `data/flows/`，运行历史不再内存淘汰 50 条，全部持久化且重启不丢
- **向量例外**：知识库向量切片以 `SimpleVectorStore` JSON 落盘 `flow.knowledge.data-dir`（默认 `data/kb`）下 `{kbId}.json`，元数据仍在 MySQL
- **首次启动播种**：`FlowSeeder` 检测 `flow_definition` 为空时插入 3 个示例流程（与 `sqls/02_dml.sql` 等价，幂等不冲突）

## 6. 关键时序：一次流程运行

```
浏览器            FlowApiController      FlowRunService       FlowEngine          AiInvoker
   │ POST /api/flows/{id}/run (multipart)│                     │                   │
   ├──────────▶ InputAssembler 装配输入  │                     │                   │
   │                  │ start(flow,input)│                     │                   │
   │                  ├─────▶ prepare → 校验+快照(RUNNING)    │                   │
   │                  ├─────▶ RunStore.save（runId 可查）     │                   │
   │                  ├─────▶ 异步 run ──▶ 拓扑调度            │                   │
   │◀── 200 {runId,…} │                  │   并行提交就绪节点 ──▶ invoke（llm/mock）  │
   │                  │                  │   成功→递减下游入度  │                   │
   │ GET /api/runs/{runId}（轮询）        │   汇流→调度执行      │                   │
   ├──────────▶ RunApiController ──▶ RunStore 完整快照         │   失败→级联取消    │
   │◀── 节点实时状态                       │                     │                   │
```

## 7. 配置体系

`application.yml`（均可被环境变量覆盖，详见《使用手册》（usage.md））：

```yaml
server.port: 8080
flow.ai:
  provider: llm|mock          # 模式切换
  text-model / vision-model   # 文本/视觉默认模型
  temperature: 0.3
  node-timeout-seconds: 180   # 单节点超时
  executor-pool-size: 8       # 节点并行度
  mock-delay-millis: 900      # mock 模式模拟延迟
  output-retry-attempts: 2    # JSON 输出解析失败重试次数
  invoke-retry-attempts: 2    # 模型调用失败重试次数
  retry-backoff-millis: 500   # 重试退避间隔
  fallback-model: ""          # 重试仍失败时的降级模型（空则不降级）
flow.excel.max-rows: 1000     # 送入模型的最大行数
flow.mcp.expose:
  enabled: true               # 是否把已暴露流程注册为 MCP 工具（FLOW_MCP_EXPOSE）
  wait-timeout-seconds: 180   # MCP 同步执行流程的最长等待
spring.ai.mcp.client:
  enabled: false              # MCP 客户端开关（MCP_CLIENT_ENABLED），开启后可调用外部 MCP 工具
flow.knowledge:
  data-dir: data/kb           # SimpleVectorStore JSON 落盘目录
  chunk-size: 800             # 文档切片大小
  default-top-k: 4            # 知识检索默认召回条数
spring.datasource:            # MySQL 连接（环境变量 MYSQL_HOST/PORT/DB/USERNAME/PASSWORD）
  url: jdbc:mysql://127.0.0.1:3306/guonl_ai_flow?…
mybatis:
  mapper-locations: classpath:mapper/*.xml
  map-underscore-to-camel-case: true
springdoc:
  swagger-ui.path: /swagger-ui.html
  api-docs.path: /v3/api-docs
```

`spring.ai.openai.*`：`api-key` 与 `base-url`（OpenAI 兼容端点，必须以 `/v1` 结尾）。`spring.ai.chat.memory.repository.jdbc.initialize-schema=always`：启动自动建会话记忆表。`spring.ai.model.image/moderation/audio.transcription`：多模态模型开关（默认注释，llm 模式按需开启）。

## 8. 扩展指南

**新增节点类型**（四步）：

1. `NodeType` 枚举增加类型（中文名 / 主题色 / 图标），按需实现 `supportsMedia()`
2. 新建 `XxxNodeHandler extends AbstractNodeHandler`，`@Component` 注册，覆盖 `supports()`；按需覆盖 `resolveSystemPrompt` / `collectMedias` / `buildAutoContext`
3. 编辑器左侧 `flow-editor.html` 的节点面板增加 palette-item（拖拽入口）；`editor.js` 属性面板按需适配
4. 运行页 `run.js` / Playground `playground.js` 的输入分组按需适配

**接入其他模型协议**：实现 `AiInvoker` 接口（`invoke` / `stream`），在 `AiConfiguration` 中按 provider 装配即可，core 层零改动。

## 9. 非功能说明

- **并发模型**：运行间互相独立；单运行内节点按 DAG 并行度受 `executor-pool-size` 约束
- **容错**：节点异常根因提取（`rootMessage`）、超时兜底、失败级联取消；API 层统一异常处理
- **安全边界**：当前为单机内部工具形态，无认证鉴权；生产使用建议置于网关之后
- **网络注意**：llm 模式依赖 `OPENAI_BASE_URL` 连通性；若终端代理/零信任客户端按进程劫持 Java 流量，会出现 400 空响应体（`SpringAiInvoker.describeError` 已针对性提示），应将模型网关域名加入代理直连例外
