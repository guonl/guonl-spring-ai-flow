-- ============================================================================
-- guonl_ai_flow 初始化数据（DML）
-- ----------------------------------------------------------------------------
-- 内容：三个内置示例流程（license-ocr / lead-clean / order-risk），
--       与应用内 FlowSeeder 播种内容完全一致。
-- 说明：
--   1. 使用 INSERT IGNORE 保证幂等（重复执行不会产生重复数据）；
--   2. 应用启动时若流程库为空也会自动播种（FlowSeeder），因此本文件可选；
--   3. SQL 字符串中的 \\n 对应 JSON 转义换行、\\\" 对应 JSON 转义双引号。
-- 执行：mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < sqls/02_dml.sql
-- ============================================================================

USE guonl_ai_flow;

-- ----------------------------------------------------------------------------
-- 1. license-ocr：营业执照智能识别（单节点图片识别）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO flow_definition (id, name, description, nodes, edges, created_at, updated_at) VALUES
('license-ocr', '营业执照智能识别',
 '上传营业执照图片，AI自动提取关键字段并按JSON契约返回。对应单一场景示例1。',
 '[{"id":"ocr","name":"营业执照识别","type":"IMAGE","prompt":"识别图片中的营业执照，提取登记信息。若图片不是有效营业执照，无法确认的字段返回null。","outputSpec":{"type":"json","fields":[{"name":"company_name","desc":"企业全称","required":true},{"name":"tax_no","desc":"统一社会信用代码（18位）","required":true},{"name":"address","desc":"注册地址","required":false},{"name":"legal_rep","desc":"法定代表人姓名","required":false},{"name":"business_scope","desc":"经营范围摘要（50字内）","required":false}]},"x":380,"y":160}]',
 '[]',
 NOW(), NOW());

-- ----------------------------------------------------------------------------
-- 2. lead-clean：客户线索智能清洗（串行三节点，TEXT -> JSON -> TEXT）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO flow_definition (id, name, description, nodes, edges, created_at, updated_at) VALUES
('lead-clean', '客户线索智能清洗',
 '原始线索文本 → 需求提取 → 结构化入库 → 跟进话术。示范串行编排与 {{节点ID.json}} 变量传递。',
 '[{"id":"extract","name":"需求要点提取","type":"TEXT","prompt":"分析以下客户线索，理解客户的核心诉求与紧急程度。\\n\\n线索内容：\\n{{input}}","outputSpec":{"type":"json","fields":[{"name":"demand","desc":"客户核心需求摘要（60字内）","required":true},{"name":"urgency","desc":"紧急程度：high/medium/low","required":true},{"name":"budget_hint","desc":"预算线索，无则为null","required":false},{"name":"intent_level","desc":"意向强度：A/B/C","required":true}]},"x":80,"y":160},{"id":"structure","name":"线索结构化","type":"JSON","prompt":"将需求要点清洗为标准线索记录：紧急程度转换为标准枚举，缺失信息明确标注。\\n\\n需求要点：\\n{{extract.json}}","systemPrompt":"你是CRM系统的数据清洗引擎，只输出标准JSON。","outputSpec":{"type":"json","fields":[{"name":"customer_name","desc":"客户名称，从线索中提取，无则为\\\"未知客户\\\"","required":true},{"name":"demand","desc":"需求描述","required":true},{"name":"priority","desc":"优先级：P0(最急)-P3","required":true},{"name":"next_action","desc":"建议的下一步跟进动作","required":true}]},"x":400,"y":160},{"id":"script","name":"跟进话术生成","type":"TEXT","prompt":"根据以下线索记录，为销售生成一段简短自然的跟进话术（120字内，口语化，带明确行动建议）：\\n\\n{{structure.json}}","systemPrompt":"你是资深ToB销售教练，话术真诚不油腻。","outputSpec":{"type":"text","fields":[]},"x":720,"y":160}]',
 '[{"from":"extract","to":"structure"},{"from":"structure","to":"script"}]',
 NOW(), NOW());

-- ----------------------------------------------------------------------------
-- 3. order-risk：订单风险并行审核（IMAGE ∥ EXCEL -> COMBINED 并行汇流）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO flow_definition (id, name, description, nodes, edges, created_at, updated_at) VALUES
('order-risk', '订单风险并行审核',
 '发货单据识别与订单数据分析并行执行，结果汇流至综合裁定节点。示范并行编排 + COMBINED 汇流。',
 '[{"id":"doc","name":"发货单据识别","type":"IMAGE","prompt":"识别图片中的物流/发货单据信息，无法识别的字段返回null。","outputSpec":{"type":"json","fields":[{"name":"ship_no","desc":"运单号","required":true},{"name":"sender","desc":"发货人/发货方","required":false},{"name":"receiver","desc":"收货人/收货方","required":false},{"name":"goods","desc":"货物名称","required":false}]},"x":80,"y":60},{"id":"excel","name":"订单数据分析","type":"EXCEL","prompt":"分析上传的订单明细表格：统计总金额与订单数，识别金额显著异常（高于平均值3倍）的记录。\\n\\n表格数据：\\n{{input.rows}}","outputSpec":{"type":"json","fields":[{"name":"total_amount","desc":"订单总金额（数字）","required":true},{"name":"order_count","desc":"订单笔数（数字）","required":true},{"name":"max_amount","desc":"单笔最大金额","required":false},{"name":"abnormal_index","desc":"异常记录的行号，多个用逗号分隔，无则空串","required":false}]},"x":80,"y":320},{"id":"verdict","name":"综合风险裁定","type":"COMBINED","prompt":"你是风控专家。综合单据识别结果与订单数据分析，出具风险裁定。\\n\\n单据识别：\\n{{doc.json}}\\n\\n订单分析：\\n{{excel.json}}","outputSpec":{"type":"json","fields":[{"name":"risk_level","desc":"风险等级：low/medium/high","required":true},{"name":"risk_points","desc":"风险要点列表","required":true},{"name":"consistency","desc":"单据与订单的一致性判断：一致/部分一致/不一致","required":true},{"name":"suggestion","desc":"处置建议（40字内）","required":true}]},"x":460,"y":190}]',
 '[{"from":"doc","to":"verdict"},{"from":"excel","to":"verdict"}]',
 NOW(), NOW());
