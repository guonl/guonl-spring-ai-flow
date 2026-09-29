# guonl-spring-ai-flow 功能清单

> 更新日期：2026-09-29
> 对应计划书：[docs/plans/2026-09-26-capability-roadmap.md](plans/2026-09-26-capability-roadmap.md)
> 技术栈：Spring Boot 4.1.1 + Spring AI 2.0.1 + Java 17 + MySQL + MyBatis + 原生 JS/CSS 前端

本文档盘点产品当前全部功能，并在文末给出与计划书的逐项对照结论。

---

## 一、页面与入口

| 页面 | 路径 | 功能 |
|---|---|---|
| 首页/统计 | `/`（dashboard.html） | 运行统计面板（次数/成功率/耗时/Token/模型调用次数） |
| 流程库 | `/flows`（flows.html） | 流程卡片列表（关键词检索 + 分页）、新建、编辑入口、示例流程种子数据 |
| 流程编排画布 | `/editor`（flow-editor.html） | 拖拽添加节点、连线、节点/边属性面板、保存、试运行 |
| 运行详情 | `/run/{runId}`（run.html） | 节点级运行状态、输出、工具调用轨迹、Token 用量、SSE 实时流式输出 |
| 运行列表 | `/runs`（runs.html） | 历史运行记录（运行ID/流程/状态/时间范围四条件检索 + 分页）、统计汇总卡片 |
| 知识库管理 | `/knowledge`（knowledge.html） | 知识库创建、文档上传、文档列表/删除 |
| 单节点试玩 | `/playground`（playground.html） | 单次模型调用调试 |
| AI 助手 | `/assistant`（assistant.html） | 独立会话页（三栏：会话列表 + 消息区 + 输入区）；全站页面另有右下角悬浮窗（common.js 统一注入，本页除外） |

## 二、节点类型（13 种）

| 类型 | 枚举 | 说明 | 来源 |
|---|---|---|---|
| 文本理解 | TEXT | 文本指令理解/生成 | 基础能力 |
| 图片识别 | IMAGE | 图片内容识别（多模态输入） | 基础能力 |
| JSON处理 | JSON | 按 JSON 契约输出（字段定义/必填） | 基础能力 |
| Excel处理 | EXCEL | 表格数据理解与处理 | 基础能力 |
| 组合处理 | COMBINED | 多上游汇流综合裁定 | 基础能力 |
| 条件路由 | ROUTER | 模型从候选分支选一个，未命中分支级联取消 | 计划书 A1 |
| 工具调用 | TOOL | 挂载工具集交模型自主调用，输出含调用轨迹 | 计划书 B1 |
| 知识检索 | KNOWLEDGE | 引用知识库检索增强问答（RAG），输出含命中片段 | 计划书 C2 |
| 图片生成 | IMAGE_GEN | 文生图，输出图片 | 计划书 F3 |
| 内容审核 | MODERATION | 敏感内容检测，命中可联动分支降级 | 计划书 F4 |
| 输出评估 | EVALUATE | LLM-as-judge 按维度打分并给出理由 | 计划书 E3 |
| 子流程 | SUBFLOW | 引用另一流程定义作为宏同步执行，支持流程复用 | 计划书 A3 |
| 聚合 | AGGREGATE | 不经模型的多上游输出合并（零 Token）：拼接（concat）/ JSON字段合并（jsonMerge）/ 模板渲染（template） | 计划书 A2 |

## 三、流程引擎

- **DAG 并行调度**：无依赖节点并行执行、CountDownLatch 等待、节点级状态落库
- **条件分支**：ROUTER 输出 `{"route": "分支名"}`，按边 condition 激活命中分支，未命中分支及下游级联取消（含并行上游晚完成的取消竞态防御）
- **聚合汇流**：AGGREGATE 不经模型合并多上游产出（拼接/JSON字段合并/模板渲染），与 COMBINED（经模型综合）互补
- **变量传递**：`{{节点ID.json}}` / `{{节点ID.text}}` 模板占位符引用上游输出
- **失败传播**：节点失败级联取消下游，运行终态与耗时落库
- **流程校验**：保存/运行前校验（端点存在性、路由分支与边条件一致性等）

## 四、模型调用与可靠性（计划书 E/F 方向）

| 能力 | 说明 | 计划书 |
|---|---|---|
| 结构化输出升级 | JSON 骨架示例注入提示词 + 必填字段校验 + 解析失败把错误反馈模型自纠重试 | E1 |
| 调用重试降级 | 瞬时错误（429/5xx/超时/连接抖动）指数退避重试，耗尽后 fallback 模型兜底一次 | E2 |
| 流式输出 | `InvokeRequest.onDelta` 增量回调，SSE 推送运行页，前端 streamBuf 缓冲防闪烁 | F5 |
| 多模态输入 | 图片（URL/上传多图）、音频（转录）、Excel 文件作为流程输入 | F1 |
| mock/llm 双形态 | `flow.ai.provider` 切换；mock 模式全链路可演示（文本/图片/审核/转录/嵌入均为本地模拟实现） | 基础能力 |

## 五、工具调用与 MCP（计划书 B 方向）

| 能力 | 说明 | 计划书 |
|---|---|---|
| 内置工具集 | `@Tool` 注解注册：当前时间 now、数学计算 calc、HTTP 抓取 httpGet、JSONPath 提取 jsonPath | B1 |
| 工具调用轨迹 | 每次调用记录（工具名/参数/结果）随节点输出落库，运行页可视化 | B1 |
| MCP Server | 已保存流程包装为 MCP tools 对外暴露（SSE 传输），外部 AI 客户端（Claude/Cursor 等）可直接调用 | B2 |
| MCP 客户端 | 连接外部 MCP server，发现的工具并入工具调用节点工具来源；`GET /api/mcp/tools` 实时发现；未启用时自动降级为仅内置工具 | B3 |

## 六、会话记忆（计划书 D 方向）

| 能力 | 说明 | 计划书 |
|---|---|---|
| JDBC 会话记忆 | MySQL 持久化对话历史（spring-ai chat-memory-repository-jdbc） | D1 |
| 记忆窗口 | 节点级 `memoryTurns` 配置（0=无记忆） | D1 |
| 会话隔离 | 运行参数 `conversationId` 隔离多轮会话 | D1 |

## 七、知识库 RAG（计划书 C 方向）

| 能力 | 说明 | 计划书 |
|---|---|---|
| 知识库管理 | 知识库 CRUD API + 管理页面（KnowledgeApiController + knowledge.html） | C1 |
| 文档 ETL | 上传同步切分落库（PROCESSING）后即时返回 → 后台异步分批向量化（整批一次 embedding 请求提速；每批刷新 processed_chunks，页面实时进度；重启遗留 PROCESSING 自动置 FAILED） | C1 |
| 向量存储 | SimpleVectorStore JSON 落盘（dataDir/{kbId}.json），元数据存 MySQL `flow_knowledge` 表 | C1 |
| 检索增强节点 | KNOWLEDGE 节点引用 `knowledgeBaseId`，检索注入上下文，输出含命中片段轨迹 | C2 |

## 八、可观测（计划书 F6）

| 能力 | 说明 |
|---|---|
| Token 用量贯通 | 节点级 promptTokens/completionTokens/totalTokens 落库，运行级汇总 |
| 统计面板 | `GET /api/runs/stats`：总运行数/进行中/成功/失败/成功率/平均耗时/总Token/模型调用次数；dashboard 与 runs 页展示 |

## 九、其他基础能力

- **检索分页**：流程库关键词检索 `GET /api/flows/page`（名称/描述模糊，按更新时间倒序）；运行历史四条件检索 `GET /api/runs/page`（运行ID模糊/流程名或ID模糊/状态精确/开始时间范围），统一分页结构 `PageResult{items, total, page, size}`，前端搜索栏/筛选栏 + 紧凑分页条（页码 300-400ms 防抖）
- **多模型路由**：节点级 model 指定 + `flow.ai` 全局配置（text/vision 等分模型）
- **API 文档**：springdoc swagger（`/swagger-ui.html`、`/v3/api-docs`）
- **健康检查**：Spring Boot actuator
- **异常统一处理**：ApiExceptionHandler 全局错误 → 结构化 JSON
- **种子数据**：启动时自动灌入示例流程（线索清洗/订单风险并行审核/营业执照 OCR 等，覆盖串行/并行/路由编排范式）

---

## 十、AI 助手（对话式平台入口）

> 设计详情：[docs/assistant/01-设计文档.md](assistant/01-设计文档.md) · [02-技术方案.md](assistant/02-技术方案.md)

| 能力 | 说明 |
|---|---|
| 全局悬浮窗 + 独立页 | 右下角聊天抽屉全站可用（common.js 注入、懒加载）；`/assistant` 独立三栏会话页；两入口共享会话与记忆 |
| SSE 流式对话 | `POST /api/assistant/chat`，事件序列 `start → delta×n → [tool_trace×n] → done`，15s 心跳防断连 |
| 多轮会话记忆 | JDBC ChatMemory 按 conversationId 隔离（窗口默认 10 轮）；会话列表/重命名/删除（级联清记忆）API 齐全；首条消息自动生成会话标题 |
| 双形态 | llm=ChatClient + 系统提示词 + 工具调用；mock=关键词意图路由 + 真实数据查询（查流程/看运行/诊断/统计/FAQ 速查），零密钥可演示 |
| 助手工具集 | 经 `ToolSetProvider` SPI 挂载（`toolSet=assistant` 与流程引擎内置工具隔离）：`list_flows` / `get_flow` / `get_run` / `recent_failed_runs` / `search_knowledge` / `save_flow`；全部经 FlowTools.record 记轨迹，tool_trace 事件回传前端展示 |
| NL 建流程 | `save_flow`：模型生成 nodes/edges JSON → `FlowEngine.validate` 校验，错误回灌模型自纠重试 → 保存并返回编辑器链接 |
| 问助手诊断 | 运行历史详情弹层与 `/run/{id}` 执行视图「🩺 问助手诊断」按钮：runId/流程/状态/耗时组装成诊断问题预填助手输入（`window.GuonlAssistant.open({text,newChat})` 全局 API） |
| 会话增强 | 「⟳ 重新生成」（regenerate 截断记忆最后一轮重答）；「⇩」导出会话 Markdown；👍/👎 消息反馈（`assistant_feedback` 表，重复点击取消，历史按内容指纹高亮） |
| 助手多模态 | llm 模式输入栏 🖼️ / 直接粘贴截图（≤3 张，前端自动压缩），dataURL → MediaItem 自动路由 vision-model；mock 模式隐藏入口 |
| 配置开关 | `flow.assistant.*`：总开关 / provider / model / 温度 / 记忆轮数 / 工具开关（`tools-enabled` 默认 true）/ 消息长度 / 超时 |

## 十一、与计划书逐项对照

计划书共 **15 项功能**：Phase 1 详细展开 4 项 + Phase 2~4 概要 11 项。对照结果：**14 项已实现，1 项未实现，1 项实现方式与计划书描述有偏差**。

| # | 阶段 | 计划书条目 | 状态 | 提交 | 备注 |
|---|---|---|---|---|---|
| 1 | P1 | E1 结构化输出升级（JSON骨架+必填校验+纠错重试） | ✅ 已实现 | `34b3be0` | 按计划书 Task 1 完成 |
| 2 | P1 | A1 条件分支节点（ROUTER+边条件+引擎调度+前端编辑） | ✅ 已实现 | `6e9d047` | 按计划书 Task 2 完成 |
| 3 | P1 | E2 重试降级（指数退避+fallback模型） | ✅ 已实现 | `eb0e620` | 按计划书 Task 3 完成 |
| 4 | P1 | F5 运行页流式（SSE增量推送+streamBuf） | ✅ 已实现 | `a11a986` | 按计划书 Task 4 完成 |
| 5 | P2 | B1 工具调用节点（内置工具集+轨迹展示） | ✅ 已实现 | `3e8693d` | 4 个内置工具 + TOOL 节点 + 运行页轨迹 |
| 6 | P2 | D1 JDBC 会话记忆（MySQL+memoryTurns+conversationId） | ✅ 已实现 | `0ea77d9` | 按概要完成 |
| 7 | P2 | **A2 聚合节点（AGGREGATE：拼接/JSON合并/模板渲染）** | ✅ 已实现 | `4fefeab` | 不经模型零 token：三策略（concat 分隔符拼接 / jsonMerge 字段名=节点名 / template 复用变量协议含 `{{节点名}}` 别名）；合并单位为直接上游（NodeContext 新增 parentIds） |
| 8 | P3 | C1 知识库管理（上传+ETL+向量存储落盘+CRUD页面） | ✅ 已实现 | `ea63da7` | SimpleVectorStore 落盘，与计划书一致；pgvector/Redis 预留位未接 |
| 9 | P3 | C2 检索增强节点（knowledgeBaseId 引用+命中片段） | ✅ 已实现 | `2f890f2` | KNOWLEDGE 节点 |
| 10 | P3 | B2 MCP Server（流程包装为 MCP tools） | ✅ 已实现 | `02d38f8` | spring-ai-starter-mcp-server-webmvc |
| 11 | P3 | F1 语音输入（audio transcription） | ✅ 已实现 | `c853ca2` | 运行输入支持音频上传，转录节点/转录模型 mock+llm 双形态 |
| 12 | P3 | F3 文生图节点（image model） | ✅ 已实现 | `c853ca2` | IMAGE_GEN 节点，输出图片 |
| 13 | P3 | F4 输出审核节点（敏感内容检测） | ✅ 已实现 | `c853ca2` | MODERATION 节点，命中可联动条件分支降级 |
| 14 | P4 | E3 输出评估（LLM-as-judge 打分） | ✅ 已实现 | `abe0087` | EVALUATE 节点，低分可联动 A1 分支重试 |
| 15 | P4 | F6 可观测（token用量/耗时/调用次数统计面板） | ✅ 已实现 | `61c952b` | ⚠️ 实现方式与计划书描述有偏差：未接入 Spring AI Observability + Micrometer，改为模型用量落库贯通 + MySQL 聚合统计接口 + 前端面板；计划书目标（统计面板）已达成 |
| 16 | P4 | A3 子流程节点（引用流程定义作为宏） | ✅ 已实现 | `289e860` | SUBFLOW 节点，入参映射同步执行 |
| 17 | P4 | B3 MCP 客户端（外部工具来源+动态发现） | ✅ 已实现 | `bd8010e` | spring-ai-starter-mcp-client，默认关闭，`GET /api/mcp/tools` 发现端点，轨迹装饰器统一记录 |

> 注：计划书 Phase 3 概要把 F1/F3/F4 写为一条多模态扩展，实际落为一次提交（`c853ca2`）内三个节点 + 前端配套，对照表按 3 项拆分统计，故表中为 17 行 / 15 个计划条目。

### 结论

1. **完成度 15/15（100%）**。计划书全部条目均已实现；最后补齐的 **A2 聚合节点**（提交 `4fefeab`）：不经模型的多上游合并（拼接 / JSON 字段合并 / 模板渲染），与 COMBINED（经模型综合）互补。
2. **F6 可观测**功能目标已达成（统计面板可用），但未按计划书接入 Spring AI Observability + Micrometer 埋点；如需对接企业监控体系（Prometheus/Grafana），可在此方向补齐。
3. 其余 13 项均按计划书语义完整落地，每项独立提交、经 mock 模式回归验证（含示例流程跑通）。
4. **计划书外新增**：**AI 助手**（见第十章）——对话式平台入口（悬浮窗 + 独立页、SSE 流式、多轮记忆、助手工具集 + NL 建流程），设计与实施文档见 [docs/assistant/](assistant/)。
