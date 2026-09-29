package com.guonl.flow.core.store;

import com.guonl.flow.core.model.EdgeDefinition;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import com.guonl.flow.core.model.OutputSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置示例流程种子：首次启动（流程库为空）时写入三个覆盖典型形态的模板，
 * 分别示范「单节点多模态」「串行变量传递」「并行汇流」，供用户一键复制改造。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowSeeder {

    private final FlowStore flowStore;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!flowStore.list().isEmpty()) {
            log.info("[Seeder] flow library not empty, skip seeding");
            return;
        }
        flowStore.save(licenseOcrFlow());
        flowStore.save(leadCleanFlow());
        flowStore.save(orderRiskFlow());
        log.info("[Seeder] seeded {} template flows", 3);
    }

    /** 模板1：营业执照识别 —— 单节点图片识别（单一场景） */
    private FlowDefinition licenseOcrFlow() {
        NodeDefinition ocr = node("ocr", "营业执照识别", NodeType.IMAGE, 380, 160,
            "识别图片中的营业执照，提取登记信息。若图片不是有效营业执照，无法确认的字段返回null。",
            null,
            spec(
                field("company_name", "企业全称", true),
                field("tax_no", "统一社会信用代码（18位）", true),
                field("address", "注册地址", false),
                field("legal_rep", "法定代表人姓名", false),
                field("business_scope", "经营范围摘要（50字内）", false)
            ));
        return flow("license-ocr", "营业执照智能识别",
            "上传营业执照图片，AI自动提取关键字段并按JSON契约返回。对应单一场景示例1。",
            List.of(ocr), List.of());
    }

    /** 模板2：客户线索智能清洗 —— 串行三节点（TEXT -> JSON -> TEXT，变量协议示范） */
    private FlowDefinition leadCleanFlow() {
        NodeDefinition extract = node("extract", "需求要点提取", NodeType.TEXT, 80, 160,
            "分析以下客户线索，理解客户的核心诉求与紧急程度。\n\n线索内容：\n{{input}}",
            null,
            spec(
                field("demand", "客户核心需求摘要（60字内）", true),
                field("urgency", "紧急程度：high/medium/low", true),
                field("budget_hint", "预算线索，无则为null", false),
                field("intent_level", "意向强度：A/B/C", true)
            ));
        NodeDefinition structure = node("structure", "线索结构化", NodeType.JSON, 400, 160,
            "将需求要点清洗为标准线索记录：紧急程度转换为标准枚举，缺失信息明确标注。\n\n需求要点：\n{{extract.json}}",
            "你是CRM系统的数据清洗引擎，只输出标准JSON。",
            spec(
                field("customer_name", "客户名称，从线索中提取，无则为\"未知客户\"", true),
                field("demand", "需求描述", true),
                field("priority", "优先级：P0(最急)-P3", true),
                field("next_action", "建议的下一步跟进动作", true)
            ));
        NodeDefinition script = node("script", "跟进话术生成", NodeType.TEXT, 720, 160,
            "根据以下线索记录，为销售生成一段简短自然的跟进话术（120字内，口语化，带明确行动建议）：\n\n{{structure.json}}",
            "你是资深ToB销售教练，话术真诚不油腻。",
            textSpec());
        return flow("lead-clean", "客户线索智能清洗",
            "原始线索文本 → 需求提取 → 结构化入库 → 跟进话术。示范串行编排与 {{节点ID.json}} 变量传递。",
            List.of(extract, structure, script),
            List.of(new EdgeDefinition("extract", "structure", null), new EdgeDefinition("structure", "script", null)));
    }

    /** 模板3：订单风险并行审核 —— 两路并行汇流（IMAGE ∥ EXCEL -> COMBINED） */
    private FlowDefinition orderRiskFlow() {
        NodeDefinition doc = node("doc", "发货单据识别", NodeType.IMAGE, 80, 60,
            "识别图片中的物流/发货单据信息，无法识别的字段返回null。",
            null,
            spec(
                field("ship_no", "运单号", true),
                field("sender", "发货人/发货方", false),
                field("receiver", "收货人/收货方", false),
                field("goods", "货物名称", false)
            ));
        NodeDefinition excel = node("excel", "订单数据分析", NodeType.EXCEL, 80, 320,
            "分析上传的订单明细表格：统计总金额与订单数，识别金额显著异常（高于平均值3倍）的记录。\n\n表格数据：\n{{input.rows}}",
            null,
            spec(
                field("total_amount", "订单总金额（数字）", true),
                field("order_count", "订单笔数（数字）", true),
                field("max_amount", "单笔最大金额", false),
                field("abnormal_index", "异常记录的行号，多个用逗号分隔，无则空串", false)
            ));
        NodeDefinition verdict = node("verdict", "综合风险裁定", NodeType.COMBINED, 460, 190,
            "你是风控专家。综合单据识别结果与订单数据分析，出具风险裁定。\n\n单据识别：\n{{doc.json}}\n\n订单分析：\n{{excel.json}}",
            null,
            spec(
                field("risk_level", "风险等级：low/medium/high", true),
                field("risk_points", "风险要点列表", true),
                field("consistency", "单据与订单的一致性判断：一致/部分一致/不一致", true),
                field("suggestion", "处置建议（40字内）", true)
            ));
        return flow("order-risk", "订单风险并行审核",
            "发货单据识别与订单数据分析并行执行，结果汇流至综合裁定节点。示范并行编排 + COMBINED 汇流。",
            List.of(doc, excel, verdict),
            List.of(new EdgeDefinition("doc", "verdict", null), new EdgeDefinition("excel", "verdict", null)));
    }

    // ---------- 构建辅助 ----------

    private static FlowDefinition flow(String id, String name, String description,
                                       List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        FlowDefinition flow = new FlowDefinition();
        flow.setId(id);
        flow.setName(name);
        flow.setDescription(description);
        flow.setNodes(nodes);
        flow.setEdges(edges);
        return flow;
    }

    private static NodeDefinition node(String id, String name, NodeType type, double x, double y,
                                       String prompt, String systemPrompt, OutputSpec outputSpec) {
        NodeDefinition node = new NodeDefinition();
        node.setId(id);
        node.setName(name);
        node.setType(type);
        node.setX(x);
        node.setY(y);
        node.setPrompt(prompt);
        node.setSystemPrompt(systemPrompt);
        node.setOutputSpec(outputSpec);
        return node;
    }

    private static OutputSpec spec(OutputSpec.Field... fields) {
        OutputSpec spec = new OutputSpec();
        spec.setType("json");
        spec.setFields(List.of(fields));
        return spec;
    }

    private static OutputSpec textSpec() {
        OutputSpec spec = new OutputSpec();
        spec.setType("text");
        return spec;
    }

    private static OutputSpec.Field field(String name, String desc, boolean required) {
        return new OutputSpec.Field(name, desc, required);
    }
}
