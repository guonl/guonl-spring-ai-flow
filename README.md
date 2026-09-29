# Spring AI Flow

业务 × 大模型编排引擎：将业务场景抽象为**可视化 DAG 流程**，节点与大模型（或工具 / 知识库 / 子流程）交互，支持串行、并行、条件分支、汇流聚合等任意编排形态。开箱提供 Web 编排画布、运行观测、RAG 知识库与 MCP 协议集成。

## 功能特性

**13 种节点类型**

| 分类 | 节点 |
|---|---|
| 模型理解 | 📝 文本理解、🖼️ 图片识别（多模态）、🧩 JSON 处理、📊 Excel 处理、🧬 组合处理（多源素材） |
| 控制与增强 | 🔀 条件路由（模型选分支）、🔧 工具调用（内置工具）、📚 知识检索（RAG）、📦 子流程、🧲 聚合（不经模型合并多上游，零 Token） |
| 生成与安全 | 🎨 图片生成（文生图）、🛡️ 内容审核（Moderation SPI）、⚖️ 输出评估（LLM-as-judge） |

- **流程引擎**：入度驱动拓扑调度，多节点天然并行；条件路由按 `{"route":"分支名"}` 激活下游连线；失败级联取消、节点超时（默认 180s）
- **变量协议**：提示词 `{{变量}}` 引用输入与任意上游产出（支持 `{{节点.json.字段.子字段}}` 下钻），零模板成本（未用变量自动追加上下文段）
- **输出契约**：节点声明 JSON 字段契约（字段名 / 说明 / 必填），模型按契约结构化返回；解析失败自动重试，可配降级模型
- **内置工具**：`now` 当前时间 / `calc` 数学计算 / `httpGet` 网页抓取 / `jsonPath` JSONPath 提取，模型按需自主调用
- **RAG 知识库**：文档上传即时受理 → 后台异步分批向量化（页面实时进度条）→ SimpleVectorStore 落盘 → 检索调试；知识检索节点自动召回参考片段增强问答
- **会话记忆**：节点可携带同一会话最近 N 轮对话历史（Spring AI JDBC Chat Memory，按会话ID隔离）
- **MCP 集成**：双向——把流程暴露为 MCP 工具（MCP Server）；发现并调用外部 MCP 工具（MCP Client，可开关）
- **多模态输入输出**：图片（URL / 上传多选）、Excel、音频上传自动转录；文生图产出可下载链接
- **可靠性**：模型调用 / 输出解析双重重试 + 退避 + 降级模型；mock 模式全流程零密钥体验
- **可观测**：SSE 流式推送节点增量输出（轮询兜底）、运行统计卡片、全量执行明细入库
- **检索分页**：流程库关键词分页检索、运行历史四条件（运行ID / 流程 / 状态 / 时间范围）分页检索
- **AI 助手**：全站悬浮窗 + `/assistant` 独立会话页；SSE 流式对话 + 多轮记忆；助手工具集（查流程/看运行/诊断/知识检索 + NL 建流程）；会话增强（重新生成 / 导出 Markdown / 消息反馈）；多模态贴图（dataURL → vision-model）；运行页一键诊断预填，设计文档见 [docs/assistant/](docs/assistant/)
- **双运行模式**：`mock`（本地模拟）/ `llm`（OpenAI 兼容协议直连），配置即切换

## 界面预览

**工作台** —— 统计总览 + 场景入口 + 示例模板

![工作台](docs/images/screenshot_01.jpeg)

**流程库** —— 流程卡片管理（关键词检索 + 分页）

![流程库](docs/images/screenshot_02.jpeg)

**知识库** —— 文档上传 / 向量化 / 检索调试

![知识库](docs/images/screenshot_03.jpeg)


**场景体验** —— 单节点即席调试（无需建流程）
![流程编辑器](docs/images/screenshot_04.jpeg)

**运行页** —— 提交运行 + SSE 实时执行视图
![运行页](docs/images/screenshot_05.jpeg)


**流程编辑器** —— 可视化 DAG 编排画布
![运行历史](docs/images/screenshot_07.jpeg)

**AI 助手** —— 独立会话页；全站页面右下角另有悬浮窗入口
![场景体验](docs/images/screenshot_06.jpeg)
![AI 助手](docs/images/screenshot_08.jpeg)

## 技术栈

| 层次 | 选型 |
|---|---|
| 基础框架 | Spring Boot 4.1.1 / JDK 17（内嵌 Tomcat + actuator） |
| 大模型接入 | Spring AI 2.0.1（OpenAI 兼容协议 + Chat Memory JDBC + MCP server/client） |
| 页面 | Thymeleaf + 原生 JS/CSS（无前端框架，每页独立 JS） |
| 持久化 | MySQL 8 + MyBatis |
| 其他 | Apache POI（Excel）、springdoc-openapi（Swagger UI）、Maven |

## 快速开始

### 1. 环境要求

- JDK 17+、Maven 3.8+
- MySQL 5.7+ / 8.x

### 2. 初始化数据库

执行 [sqls/01_ddl.sql](sqls/01_ddl.sql)（建库 + 五张业务表 DDL）与 [sqls/02_dml.sql](sqls/02_dml.sql)（3 个示例流程种子数据，幂等）。示例流程：

- **license-ocr 营业执照智能识别**：图片识别 → JSON 结构化
- **lead-clean 客户线索智能清洗**：文本理解 → JSON 入库 → 跟进话术
- **order-risk 订单风险并行审核**：图片 + Excel 并行审核 → 综合风险裁定

> 也可以不执行 SQL：首次启动时 `FlowSeeder` 会自动建种子流程（建表仍需 DDL 脚本）。

### 3. 配置

数据库与应用配置见 [src/main/resources/application.yml](src/main/resources/application.yml)，均可被环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | 本地 MySQL | 连接目标（默认库名 `guonl_ai_flow`） |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | — | 数据库账号 |
| `FLOW_AI_PROVIDER` | `llm` | `mock` 无需模型密钥，`llm` 直连 OpenAI 兼容端点 |
| `OPENAI_API_KEY` / `OPENAI_BASE_URL` | — | llm 模式的密钥与端点（`base-url` 须以 `/v1` 结尾） |
| `FLOW_MCP_EXPOSE` | `true` | 是否把已暴露流程注册为 MCP 工具 |
| `MCP_CLIENT_ENABLED` | `false` | 是否启用 MCP 客户端调用外部工具 |

### 4. 启动

```bash
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/guonl-spring-ai-flow-*.jar
```

mock 模式（无模型密钥体验全流程）：

```bash
FLOW_AI_PROVIDER=mock java -jar target/guonl-spring-ai-flow-*.jar
```

启动后打开 <http://localhost:8080/>，Swagger 文档在 <http://localhost:8080/swagger-ui.html>。

## 页面入口

| 页面 | 路径 | 用途 |
|---|---|---|
| 工作台 | `/` | 统计总览 + 场景入口 + 示例模板 |
| 流程库 | `/flows` | 流程卡片管理（关键词检索 + 分页） |
| 知识库 | `/knowledge` | 文档上传 / 向量化 / 检索调试 |
| 流程编辑器 | `/flow-editor` | 可视化 DAG 编排画布 |
| 运行页 | `/run/{id}` | 提交运行 + SSE 实时执行视图 |
| 场景体验 | `/playground` | 单节点即席调试（无需建流程） |
| 运行历史 | `/runs` | 运行记录（四条件检索 + 分页） |
| AI 助手 | `/assistant` | 独立会话页；全站页面右下角另有悬浮窗入口 |

## API 概览

| 分组 | 代表端点 |
|---|---|
| 流程定义 | `GET /api/flows`、`GET /api/flows/page`（关键词分页检索）、`POST /api/flows`、`GET/PUT/DELETE /api/flows/{id}`、`POST /api/flows/{id}/run` |
| 运行查询 | `GET /api/runs/page`（运行ID/流程/状态/时间范围分页检索）、`GET /api/runs/{runId}`、`GET /api/runs/{runId}/stream`（SSE）、`GET /api/runs/stats` |
| 知识库 | `POST /api/knowledge`、`POST /api/knowledge/{id}/docs`（上传文档，异步向量化，`GET docs` 轮询进度）、`POST /api/knowledge/{id}/search`（检索测试） |
| Playground | `POST /api/playground`、`POST /api/playground/stream`（SSE） |
| AI 助手 | `POST /api/assistant/chat`（SSE 流式对话，regenerate/images 支持）、`GET /api/assistant/sessions`、`GET /api/assistant/status`、`POST /api/assistant/feedback`、`GET /api/assistant/sessions/{conv}/feedback` |
| 系统与 MCP | `GET /api/config`、`GET /api/mcp/tools` |

## 项目结构

```
guonl-spring-ai-flow
├── sqls/                # 数据库初始化脚本（DDL + 示例流程种子）
├── docs/                # 项目文档
└── src/main/java/com/guonl/flow/
    ├── web/             # 8 个控制器（页面路由 / REST / SSE / 异常统一）
    ├── core/            # 引擎 engine（DAG 调度/SSE Hub）、13 类节点处理器 node、
    │                    # 模型 model、内置工具 tool、存储 store
    ├── knowledge/       # RAG 知识库（切片向量化 + SimpleVectorStore 落盘）
    ├── mcp/             # MCP Server（流程暴露为工具）+ MCP Client 轨迹
    ├── ai/              # AiInvoker 抽象（llm/mock）+ 多模态 Mock 模型
    ├── assistant/       # AI 助手（Service / mock 意图路由 / 工具集 / 会话 / 提示词加载）
    ├── config/          # 配置绑定与装配
    └── db/              # MyBatis 实体与 Mapper（五张业务表 + 助手会话表）
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/features.md](docs/features.md) | 功能清单：页面、节点类型、引擎、API、能力对照 |
| [docs/user-guide.md](docs/user-guide.md) | 面向业务用户：七大页面操作流程 |
| [docs/usage.md](docs/usage.md) | 面向部署运维：构建、配置、API、数据生命周期、FAQ |
| [docs/architecture.md](docs/architecture.md) | 分层架构、目录结构、核心机制、扩展指南 |
| [docs/assistant/](docs/assistant/01-设计文档.md) | AI 助手三件套：设计文档 / 技术方案 / 实施计划 |
