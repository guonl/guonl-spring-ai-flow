# Spring AI Flow 使用手册

> 面向部署运维与二次开发人员：环境准备、构建启动、配置说明、流程定义规范、REST API 与常见问题。
> 网站页面的图形化操作请阅读《网站操作手册》（user-guide.md）。

## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17+ |
| Maven | 3.8+（或直接使用 IDE 内置构建） |
| MySQL | 8.x（数据库 `guonl_ai_flow`，utf8mb4；首次部署需执行 `sqls/` 初始化脚本，见第 2.3 节） |
| 网络 | llm 模式需能访问 OpenAI 兼容模型网关；mock 模式无需任何外部依赖 |
| 端口 | 默认 8080，可修改 `server.port` |

## 2. 构建与启动

### 2.1 Maven 启动

```bash
# 前台运行
mvn spring-boot:run

# 或打包为可执行 jar
mvn clean package -DskipTests
java -jar target/guonl-spring-ai-flow-0.0.1-SNAPSHOT.jar
```

### 2.2 IDE 启动

直接运行主类 `com.guonl.GuonlSpringAiFlowApplication` 即可。

### 2.3 数据库初始化与验证

**首次部署需先初始化数据库**（顺序执行）：

```bash
mysql -u root -p --default-character-set=utf8mb4 < sqls/01_ddl.sql   # 建库 guonl_ai_flow + 五张业务表 DDL（流程三表 + 知识库两表）
mysql -u root -p --default-character-set=utf8mb4 < sqls/02_dml.sql   # 3 个示例流程种子数据（幂等，可重复执行）
mysql -u root -p --default-character-set=utf8mb4 < sqls/assistant_session.sql   # AI 助手会话表（不执行则助手对话接口报表不存在）
```

> 两个脚本均幂等：DDL 用 `CREATE DATABASE/ TABLE IF NOT EXISTS`，DML 用 `INSERT IGNORE`，重复执行无副作用。
> 跳过 02_dml.sql 也可以——应用首次启动时 `FlowSeeder` 检测到定义表为空会自动播种同样内容。

**启动后验证**：

浏览器访问 `http://localhost:8080/` 进入工作台；或：

```bash
curl http://localhost:8080/api/config
# {"provider":"llm","textModel":"aliyun/qwen3.8-flash","visionModel":"aliyun/qwen3.8-flash"}
```

接口文档（Swagger UI）：`http://localhost:8080/swagger-ui.html`，可在页面直接查看并调试全部 REST 接口。

## 3. 配置说明

全部配置位于 `src/main/resources/application.yml`，均可通过环境变量覆盖。

### 3.1 AI 模式与模型

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `flow.ai.provider` | `FLOW_AI_PROVIDER` | `llm` | `llm`=经 Spring AI 走 OpenAI 兼容协议；`mock`=本地模拟（无需密钥，返回模拟数据，延迟 900ms） |
| `spring.ai.openai.api-key` | `OPENAI_API_KEY` | 内置测试密钥 | 模型网关密钥 |
| `spring.ai.openai.base-url` | `OPENAI_BASE_URL` | 内置 SIT 网关 | **必须以 `/v1` 结尾**（SDK 在其后拼 `/chat/completions`），如 DashScope 兼容端点 `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `flow.ai.text-model` | `FLOW_AI_TEXT_MODEL` | `aliyun/qwen3.8-flash` | 文本类节点默认模型 |
| `flow.ai.vision-model` | `FLOW_AI_VISION_MODEL` | `aliyun/qwen3.8-flash` | 图片/组合类节点默认模型（含图流程自动切换） |

### 3.2 执行与存储

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `server.port` | — | `8080` | HTTP 端口 |
| `flow.ai.temperature` | — | `0.3` | 默认温度（节点可覆盖） |
| `flow.ai.node-timeout-seconds` | — | `180` | 单节点执行超时 |
| `flow.ai.executor-pool-size` | — | `8` | 节点并行执行线程池大小 |
| `flow.ai.mock-delay-millis` | — | `900` | mock 模式模拟延迟 |
| `flow.excel.max-rows` | — | `1000` | Excel 送入模型的最大行数 |
| `flow.ai.output-retry-attempts` | — | `2` | 结构化输出解析/必填校验失败后的纠错重试次数 |
| `flow.ai.invoke-retry-attempts` | — | `2` | 瞬时错误（429/5xx/超时/连接抖动）指数退避重试次数 |
| `flow.ai.retry-backoff-millis` | — | `500` | 重试基础退避毫秒（按 2^(n-1) 递增，上限 8 秒） |
| `flow.ai.fallback-model` | — | 空 | 主模型重试耗尽后的降级模型，留空禁用 |
| `flow.mcp.expose.enabled` | `FLOW_MCP_EXPOSE` | `true` | 是否把已保存流程暴露为 MCP tools |
| `flow.mcp.expose.wait-timeout-seconds` | — | `180` | MCP tool 调用流程后的同步等待超时 |
| `spring.ai.mcp.client.enabled` | `MCP_CLIENT_ENABLED` | `false` | MCP 客户端开关（连接外部 MCP server，发现的工具并入工具调用节点） |
| `spring.servlet.multipart.max-file-size` | — | `20MB` | 单文件上限（请求整体 40MB） |

### 3.3 数据库（MySQL + MyBatis）

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `spring.datasource.url` | `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | `127.0.0.1` / `3306` / `guonl_ai_flow` | JDBC 连接（utf8、Asia/Shanghai 时区） |
| `spring.datasource.username` | `MYSQL_USERNAME` | `root` | 数据库用户 |
| `spring.datasource.password` | `MYSQL_PASSWORD` | 空 | 数据库密码 |
| `mybatis.mapper-locations` | — | `classpath:mapper/*.xml` | Mapper XML 位置 |
| `mybatis.map-underscore-to-camel-case` | — | `true` | 下划线列名 → 驼峰属性 |

### 3.4 AI 助手

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `flow.assistant.enabled` | `FLOW_ASSISTANT_ENABLED` | `true` | 总开关：false 时悬浮窗不注入、助手 API 返回禁用提示 |
| `flow.assistant.provider` | `FLOW_ASSISTANT_PROVIDER` | 空（跟随 `flow.ai.provider`） | 助手模型服务模式：空 / `mock` / `llm` |
| `flow.assistant.model` | `FLOW_ASSISTANT_MODEL` | 空（跟随 `flow.ai.text-model`） | 助手模型覆盖 |
| `flow.assistant.temperature` | — | `0.3` | 对话温度（建议低温） |
| `flow.assistant.memory-turns` | — | `10` | 会话记忆窗口轮数 |
| `flow.assistant.tools-enabled` | `FLOW_ASSISTANT_TOOLS` | `true` | 工具集开关（流程/运行/知识库查询 + NL 建流程）；仅 llm 链路生效，mock 走意图路由不经工具 |
| `flow.assistant.max-message-length` | — | `4000` | 单条用户消息最大长度 |
| `flow.assistant.timeout-seconds` | — | `120` | 单次回答超时（秒） |

### 3.5 接口文档（Swagger）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | Swagger UI 入口 |
| `springdoc.api-docs.path` | `/v3/api-docs` | OpenAPI JSON |

> 提示：Thymeleaf 缓存已关闭（`spring.thymeleaf.cache=false`），开发期改模板刷新即生效；生产建议开启。

## 4. 流程定义规范

### 4.1 流程 JSON 结构

流程定义保存在 MySQL `flow_definition` 表（`nodes`/`edges` 为 JSON 文本列），也可通过 REST API 直接维护：

```json
{
  "id": "order-risk",
  "name": "订单风险并行审核",
  "description": "发货单据识别与订单数据分析并行执行，结果汇流至综合裁定节点。",
  "nodes": [ { "…节点定义见 4.2" } ],
  "edges": [ { "from": "doc", "to": "verdict" } ]
}
```

- `id`：为空时由服务端生成（`f_` + 8 位随机串）
- `nodes` + `edges` 构成 DAG；允许多根并行、多父汇流，禁止自环与循环依赖
- 校验规则：节点 ID 非空且唯一、名称必填、处理指令必填、连线端点必须存在、Kahn 算法检测无环

### 4.2 节点定义

```json
{
  "id": "doc",
  "name": "发货单据识别",
  "type": "IMAGE",
  "prompt": "识别图片中的物流/发货单据信息，无法识别的字段返回null。",
  "systemPrompt": null,
  "outputSpec": {
    "type": "json",
    "fields": [
      { "name": "ship_no", "desc": "运单号", "required": true },
      { "name": "sender",  "desc": "发货人/发货方", "required": false }
    ]
  },
  "x": 80, "y": 60,
  "model": null,
  "temperature": null,
  "imageUrl": null
}
```

| 字段 | 说明 |
|---|---|
| `type` | `TEXT` 文本理解 / `IMAGE` 图片识别 / `JSON` JSON处理 / `EXCEL` Excel处理 / `COMBINED` 组合处理 / `ROUTER` 条件路由 / `TOOL` 工具调用 / `KNOWLEDGE` 知识检索 / `IMAGE_GEN` 图片生成 / `MODERATION` 内容审核 / `EVALUATE` 输出评估 / `SUBFLOW` 子流程 / `AGGREGATE` 聚合 |
| `prompt` | 处理指令，支持 `{{变量}}` 协议（见 4.3） |
| `systemPrompt` | 可选角色设定；缺省时按节点类型使用内置角色 |
| `outputSpec.type` | `json`（按 fields 契约结构化输出）/ `text`（纯文本） |
| `outputSpec.fields` | JSON 契约字段：`name` 字段名、`desc` 说明、`required` 是否必填；为空则由模型自行设计结构 |
| `model` / `temperature` | 节点级覆盖（优先级高于全局默认；含图流程未指定时自动用 vision-model） |
| `imageUrl` | 图片类节点可直接绑定素材 URL（运行时上传的图片优先级更高、两者可叠加） |
| `x` / `y` | 画布坐标（编辑器布局用） |
| `routes` | 条件路由（ROUTER）候选分支：`label` 分支名 / `desc` 说明；连线 `condition` 填分支名 |
| `memoryTurns` | 会话记忆窗口：携带最近 N 轮对话历史（0=无记忆），配合运行参数 `conversationId` 隔离会话 |
| `knowledgeBaseId` / `topK` | 知识检索（KNOWLEDGE）引用的知识库ID与召回条数 |
| `subflowId` | 子流程（SUBFLOW）引用的流程ID（入参经 prompt 模板组装，同步执行） |
| `aggregateStrategy` | 聚合（AGGREGATE）合并策略：`concat` 分隔符拼接 / `jsonMerge` JSON字段合并 / `template` 模板渲染（prompt 即分隔符或模板） |

各类型的输入要求：

- **IMAGE**：必须有图片素材——运行时上传图片、输入图片 URL 或节点绑定 `imageUrl`，否则校验失败
- **JSON**：无上游节点时，流程输入文本必须是合法 JSON
- **EXCEL**：必须有表格来源——运行时上传 Excel（.xlsx/.xls，自动解析表头与数据行，超 `max-rows` 截断）
- **COMBINED**：自动携带全部可用素材（图片）与上下文数据（文本/JSON/Excel/上游产出）
- **TEXT**：任意文本

### 4.3 变量协议

节点提示词中可用 `{{变量}}` 引用流程输入与全部祖先节点的产出：

```
{{input}}                     流程输入文本
{{input.rows}}                Excel 解析数据（JSON 文本）
{{doc}}  {{doc.output}}       上游节点 doc 的原始输出
{{doc.json}}                  上游节点 doc 的 JSON 输出（整体）
{{doc.json.ship_no}}          上游 JSON 指定字段值（支持多级：a.b.c；数组下标从 1 起）
```

**零模板成本**：若提示词中未使用任何变量，引擎会自动将流程输入、Excel 数据、上游产出以【上下文数据】段落追加到提示词，单节点场景无需学习变量语法。

### 4.4 编排形态示例

- **串行链**（线索清洗）：TEXT（提取需求）→ JSON（结构化）→ TEXT（生成话术），下游用 `{{上游.json.字段}}` 精准引用
- **并行 + 汇流**（订单风控）：IMAGE（单据识别）与 EXCEL（订单分析）并行，双双汇入 COMBINED（综合裁定）
- **单节点**：单一 IMAGE 节点即营业执照识别应用

## 5. REST API

### 5.1 流程管理 `/api/flows`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/flows` | 流程列表（按更新时间倒序） |
| GET | `/api/flows/page` | 关键词分页检索（`keyword` 匹配名称/描述，`page`/`size` 默认 12/页，按更新时间倒序） |
| GET | `/api/flows/{id}` | 流程详情 |
| POST | `/api/flows` | 新建/更新（body 为流程 JSON；id 空则生成） |
| DELETE | `/api/flows/{id}` | 删除流程（删除 `flow_definition` 表记录） |
| POST | `/api/flows/{id}/validate` | 校验已保存流程 → `{valid, errors}` |
| POST | `/api/flows/validate` | 校验未保存草稿（body 为流程 JSON） |
| POST | `/api/flows/{id}/duplicate` | 复制一份新流程（名称追加"（副本）"） |

### 5.2 运行

**提交运行**：`POST /api/flows/{id}/run`（`multipart/form-data`，立即返回 RUNNING 快照，前端凭 runId 轮询）

| 参数 | 类型 | 说明 |
|---|---|---|
| `text` | 文本 | 流程输入文本（TEXT/JSON/COMBINED 等场景） |
| `imageUrl` | 文本 | 图片 URL（图片/组合场景） |
| `images` | 文件，可多选 | 上传图片（图片/组合场景） |
| `excel` | 文件 | Excel 文件 .xlsx/.xls（表格场景） |
| `audio` | 文件 | 音频文件（语音输入，自动转录为文本注入流程输入；mock 模式返回模拟转录） |
| `conversationId` | 文本 | 会话ID（多轮会话记忆隔离，配合节点 `memoryTurns` 使用） |

```bash
curl -X POST http://localhost:8080/api/flows/license-ocr/run \
  -F "images=@./license.png"
```

**运行查询**：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/runs` | 运行历史（全量，按开始时间倒序） |
| GET | `/api/runs/page` | 条件分页检索：`runId` 模糊 / `flow` 流程名或ID模糊 / `status` 精确（RUNNING/SUCCESS/FAILED/CANCELLED）/ `startDate`+`endDate` 日期范围（yyyy-MM-dd，结束日含当天）；`page`/`size` 默认 15/页 |
| GET | `/api/runs/{runId}` | 运行快照（含全部节点实时状态/输出/耗时/tokens，前端轮询端点） |
| GET | `/api/runs/{runId}/stream` | SSE 订阅运行实时增量输出（`{"nodeId","delta"}` 逐块推送，结束 `{"done":true}`） |
| GET | `/api/runs/stats` | 统计：总数/运行中/成功/失败/成功率/平均耗时/累计 tokens/模型调用次数 |

### 5.3 其他

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/config` | 下发 provider 与默认模型（前端侧栏胶囊/占位符） |
| GET | `/api/mcp/tools` | MCP 工具清单（已暴露流程 + MCP 客户端发现的外部工具） |
| POST | `/api/playground` | Playground 即席执行（复用节点处理器流水线，返回 output/parsedJson/model/cost/tokens） |
| POST | `/api/playground/stream` | SSE 流式输出，逐块 `data:{"chunk":"…"}`，结束 `data:[DONE]`，异常 `data:{"error":"…"}` |

错误响应统一为 `{"error": "…"}`（`ApiExceptionHandler`）。

### 5.4 知识库 `/api/knowledge`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/knowledge` | 创建知识库（body：`{name, description}`） |
| GET | `/api/knowledge` | 知识库列表 |
| GET | `/api/knowledge/{id}` | 知识库详情 |
| DELETE | `/api/knowledge/{id}` | 删除知识库（连同其全部文档与向量切片） |
| GET | `/api/knowledge/{id}/docs` | 文档列表（含切片数、状态与进度 `totalChunks`/`processedChunks`） |
| POST | `/api/knowledge/{id}/docs` | 上传文档（multipart 文件）：同步切片落库（PROCESSING）后**即时返回**，后台异步分批向量化；状态流转 `PROCESSING → READY/FAILED`，前端 1.5s 轮询进度，PROCESSING 期间禁止删除 |
| DELETE | `/api/knowledge/{id}/docs/{docId}` | 删除文档（连同其向量切片） |
| POST | `/api/knowledge/{id}/search` | 检索测试（body：`{query, topK}`），返回相关切片及得分 |

> **Swagger 在线文档**：全部 REST 接口已接入 springdoc-openapi，启动后访问 `http://localhost:8080/swagger-ui.html` 可按分组（流程定义管理 / 运行记录查询 / Playground 即席体验 / 系统配置查询）浏览接口、查看参数说明并直接发起调试；OpenAPI JSON 位于 `/v3/api-docs`，可导入 Postman/Apifox 等工具。

### 5.5 AI 助手 `/api/assistant`

全站右下角悬浮窗与 `/assistant` 独立页共用的后端（设计详情见 [docs/assistant/](assistant/01-设计文档.md)）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/assistant/chat` | SSE 流式对话（body：`{message, conversationId?, regenerate?, images?}`；`regenerate=true` 截断记忆最后一轮后重新应答；`images` 为 dataURL 图片数组 ≤3 张；事件序列 `start → delta×n → [tool_trace×n] → done`，心跳 `:ping` 15s） |
| GET | `/api/assistant/status` | 助手状态（enabled/provider/model/toolsEnabled，前端据此展示） |
| GET | `/api/assistant/sessions` | 会话列表（最近 50 条，按更新时间倒序） |
| GET | `/api/assistant/sessions/{conversationId}/messages` | 会话消息历史 |
| POST | `/api/assistant/sessions/{conversationId}/rename?title=xx` | 会话重命名 |
| DELETE | `/api/assistant/sessions/{conversationId}` | 删除会话（连带清空 ChatMemory 记忆与反馈记录） |
| POST | `/api/assistant/feedback` | 消息反馈（body：`{conversationId, content, rating}`；rating=`up`/`down` 重复提交为改评，空为取消） |
| GET | `/api/assistant/sessions/{conversationId}/feedback` | 会话反馈汇总（`[{contentHash, rating}]`，前端按指纹高亮） |

**能力与模式差异**：

- **llm 模式**：ChatClient + 系统提示词（`prompts/assistant-system.md`）+ 多轮记忆（JDBC ChatMemory），模型自主调用助手工具集回答数据类问题；支持贴图多模态（自动路由 vision-model）
- **mock 模式**：关键词意图路由 + 真实数据查询模板化回答（查流程/看运行/诊断/统计/答疑速查表），无大模型也可演示全部能力（不支持贴图）
- **工具集**（`tools-enabled=true` 时挂载，经 `ToolSetProvider` SPI 注册）：`list_flows` 流程检索、`get_flow` 流程结构、`get_run` 运行快照、`recent_failed_runs` 近期失败、`search_knowledge` 知识库检索、`save_flow` NL 建流程（先 `FlowEngine.validate` 校验，错误回灌模型自纠后重存）
- **llm 模式验证话术**（需真实模型 key）：「有哪些流程」「帮我诊断 r_xxxx」「变量协议怎么写」「帮我建一个 Excel→JSON→文本总结的流程」

**会话增强（P2）**：

- **问助手诊断**：运行历史详情弹层与 `/run/{id}` 执行视图均有「🩺 问助手诊断」按钮，自动把 runId/流程/状态/耗时组装成诊断问题预填进助手输入（不自动发送，可补充后发送）；开发方页面亦可调用全局 API `window.GuonlAssistant.open({text, newChat})` 预填
- **重新生成**：每条回答完成后 meta 行有「⟳ 重新生成」，点击截断记忆中最后一轮并重新应答（仅最后一条回答可用，重新生成不产生新用户气泡）
- **导出会话**：头部动作区「⇩」导出当前会话为 Markdown 文件（含元信息与分节轮次）
- **消息反馈**：每条回答 meta 行有 👍/👎，重复点击取消；会话历史恢复后仍按内容指纹高亮，反馈存 `assistant_feedback` 表（可作效果分析数据源）

## 6. 数据与生命周期

全部业务数据持久化在 MySQL 数据库 `guonl_ai_flow`（不再写本地文件），**服务重启数据不丢失**：

| 数据 | 存储表 | 生命周期 |
|---|---|---|
| 流程定义 | `flow_definition`（`nodes`/`edges` 为 JSON 列） | 永久，直至通过 API 删除 |
| 运行记录 | `flow_run` | 永久，全量保留（不再有内存 50 条上限） |
| 节点执行明细 | `flow_node_execution`（含输出/解析结果/错误/模型/tokens） | 永久，随运行记录保留 |
| 知识库 | `flow_knowledge` | 永久，直至通过 API 删除 |
| 知识库文档 | `flow_knowledge_doc`（`chunk_ids` 指向向量切片） | 随所属知识库级联删除 |
| 会话记忆 | `SPRING_AI_CHAT_MEMORY`（Spring AI 自动建表，`initialize-schema=always` 幂等；content 列已扩 MEDIUMTEXT） | 永久，按 `conversationId` 隔离 |
| 助手会话元数据 | `assistant_session`（标题/消息数/时间戳；消息正文存 ChatMemory） | 随会话删除级联清理 |
| 助手消息反馈 | `assistant_feedback`（会话+内容指纹唯一，rating=up/down；见 `sqls/p2_assistant.sql`） | 随会话删除级联清理 |

- **写入机制**：写穿式持久化——提交运行即落库（RUNNING），节点状态每次变更（进入 RUNNING / 终态 / 级联取消）实时 UPDATE，流程终态汇总耗时与 tokens；执行中途宕机也能看到已落库的进度
- **示例流程**：`sqls/02_dml.sql` 预置（幂等 `INSERT IGNORE`）；应用启动时 `FlowSeeder` 亦会在定义表为空时自动播种同样内容，二者等价不冲突
- **向量库落盘**：知识库向量切片使用 Spring AI `SimpleVectorStore`，以 JSON 文件形式写入 `flow.knowledge.data-dir`（默认 `data/kb`）下的 `{kbId}.json`，删除知识库/文档时同步删除；元数据在 MySQL、向量在文件，重启不丢
- **异步向量化**：文档上传受理后 ETL 在后台线程池执行，按批（`flow.knowledge.embed-batch-size`，默认 16，OpenAI 兼容服务单请求上限一般 32）整批一次 embedding 请求提速约 2 倍，每批完成即刷新 `processed_chunks`；应用启动时将遗留 PROCESSING 文档统一置为 FAILED（向量未落盘，需重新上传）
- **表结构变更**：手工修改表结构请同步更新 `sqls/01_ddl.sql`，保持脚本与实际 schema 一致

## 7. 二次开发要点

- **新增节点类型**：`NodeType` 枚举 + 新 Handler（继承 `AbstractNodeHandler`，覆盖 `supports()` 与差异化方法）+ 编辑器 palette 项，详见《架构说明》（architecture.md）第 8 节
- **接入其他模型协议**：实现 `AiInvoker` 接口并在 `AiConfiguration` 按 provider 装配，core 层零改动
- **提示词定制**：节点级 `systemPrompt`/`prompt`/输出契约即可覆盖绝大多数需求；全局默认角色见 `PromptKit.DEFAULT_SYSTEM_PROMPT` 与各 Handler 默认值
- **多模态素材**：图片下载走独立超时（`imageDownloadTimeoutSeconds`），下载失败会以明确错误抛出

## 8. 常见问题（FAQ）

**Q1：启动后看不到流程？**
1. 确认数据库 `guonl_ai_flow` 存在且五张业务表已创建（未初始化请先执行 `sqls/01_ddl.sql`）
2. 确认 `spring.datasource` 连接配置正确（默认连 `127.0.0.1:3306`，用户 `root`，可用 `MYSQL_*` 环境变量覆盖）
3. 首次启动 `FlowSeeder` 会自动播种 3 个示例流程；若表非空则不播种，属正常

**Q2：模型返回的 JSON 解析失败，节点失败？**
查看节点错误信息中的模型原始输出（截断 500 字）。通常是指令与契约不匹配：加强 `desc` 描述、降低温度、或收紧输出类型。

**Q3：Excel 节点报「缺少表格数据」？**
在运行页上传 Excel 文件；或让该节点挂在有表格产出的上游之后。

**Q4：图片节点报「缺少图片素材」？**
在运行页上传图片/填图片 URL，或在编辑器为节点绑定 `imageUrl`。

**Q5：想体验但暂时没有模型密钥？**
设置 `FLOW_AI_PROVIDER=mock` 启动，全部交互可用（数据为模拟结果）。

**Q6：运行历史会丢失吗？**
不会。运行记录与节点明细均已持久化到 MySQL（`flow_run` / `flow_node_execution`），服务重启后历史完整可查。

**Q7：AI 助手悬浮窗没出现？**
1. 确认 `flow.assistant.enabled=true`（默认开启）
2. 刷新页面（悬浮窗由 `common.js` 统一注入，`/assistant` 独立页不再重复注入）
3. 浏览器控制台确认 `assistant-chat.js` / `assistant.css` 加载无 404

**Q8：助手回答数据类问题时说不知道/编造？**
确认 `flow.assistant.tools-enabled=true` 且 `provider=llm`（mock 模式走关键词意图路由，不走工具）。llm 模式下可在回答中查看 tool_trace 轨迹确认模型是否真实调用了工具；首次部署需执行 `sqls/assistant_session.sql` 建会话表，否则对话接口报表不存在。

**Q9：llm 模式下知识库上传文档报「404: Model configuration not found」？**
知识库向量化依赖模型网关的 `/v1/embeddings` 接口（mock 模式用本地嵌入不受影响）。若网关未配置 embedding 模型，上传与检索会报此错。两种解决方式：

- 方式一：在模型网关侧为项目开通 embedding 模型后重试。
- 方式二（无需改网关）：为知识库单独接入任意 OpenAI 兼容 embedding 服务，在 `application.yml` 配置（优先级最高，配置后知识库不再复用 `spring.ai.openai` 的网关）：

  ```yaml
  flow:
    knowledge:
      embedding:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1  # OpenAI 兼容服务地址
        api-key: ${DASHSCOPE_API_KEY:}   # 留空则复用 spring.ai.openai 的 api-key
        model: text-embedding-v4         # embedding 模型名
        dimensions: 1024                 # 可选，留空首次调用自动探测
  ```

> **模型选型提示**：embedding 模型越大检索质量未必显著更好、时延差距却极大（实测 SiliconFlow 上 `Qwen3-Embedding-8B` 单请求 30s+，`BAAI/bge-m3` 约 0.2s），交互式上传/检索场景建议选轻量模型；切换模型后向量空间变化，已有文档需删除后重新上传。

  同样适用 SiliconFlow（BAAI/bge-m3）、ollama（`http://localhost:11434/v1`）等服务。注意：切换 embedding 实现后向量空间随之变化，**已上传文档需删除后重新上传**。助手对话中的知识检索工具不受影响（会如实提示未命中）。

**Q10：助手贴图（多模态）有什么限制？**
- 仅 llm 模式可用（mock 模式隐藏图片入口）；最多 3 张，前端自动压缩（长边 ≤1568px 转 JPEG，小 PNG 直传），后端单张 base64 上限约 2MB
- 含图消息自动路由到 `flow.ai.vision-model`（默认跟随 text-model）；若当前 vision 模型不支持视觉输入会报模型侧错误，可在 `application.yml` 或环境变量 `FLOW_AI_VISION_MODEL` 换成支持视觉的模型
- 图片本身不写入会话记忆（记忆只存文本），刷新后历史消息看不到缩略图属正常现象
