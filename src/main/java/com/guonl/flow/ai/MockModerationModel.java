package com.guonl.flow.ai;

import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.CategoryScores;
import org.springframework.ai.moderation.Generation;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地模拟内容审核模型（mock模式）。
 * <p>关键词命中规则：文本命中任一违规关键词时 flagged=true 并标记对应类别（得分0.95），
 * 未命中时全部类别得分0.01；命中结果确定性（同文本同结论），
 * 在无外部审核API的情况下可验证「审核 → json输出 → 条件路由分流」完整链路。</p>
 */
public class MockModerationModel implements ModerationModel {

    /** 演示用违规关键词表：类别名 → 关键词（任一命中即标记该类别） */
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>(Map.of(
        "violence", List.of("暴力", "斗殴", "打架", "枪"),
        "hate", List.of("仇恨", "歧视", "辱骂"),
        "sexual", List.of("色情", "裸露", "情色"),
        "selfHarm", List.of("自杀", "自残"),
        "criminal", List.of("诈骗", "贩毒", "赌博")
    ));

    @Override
    public ModerationResponse call(ModerationPrompt request) {
        String text = request.getInstructions() == null || request.getInstructions().getText() == null
            ? ""
            : request.getInstructions().getText();
        // 关键词命中检测
        List<String> matched = KEYWORDS.entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(text::contains))
            .map(Map.Entry::getKey)
            .toList();
        // 构建类别标记与得分
        Categories.Builder catBuilder = Categories.builder();
        CategoryScores.Builder scoreBuilder = CategoryScores.builder();
        for (String category : KEYWORDS.keySet()) {
            boolean hit = matched.contains(category);
            applyCategory(catBuilder, scoreBuilder, category, hit ? 0.95 : 0.01, hit);
        }
        ModerationResult result = ModerationResult.builder()
            .flagged(!matched.isEmpty())
            .categories(catBuilder.build())
            .categoryScores(scoreBuilder.build())
            .build();
        Moderation moderation = Moderation.builder()
            .id("mock-moderation")
            .model("mock-moderation")
            .results(List.of(result))
            .build();
        return new ModerationResponse(new Generation(moderation));
    }

    /** 按类别名调用对应builder方法（反射开销可忽略，代码保持简洁） */
    private void applyCategory(Categories.Builder catBuilder, CategoryScores.Builder scoreBuilder,
                               String category, double score, boolean hit) {
        switch (category) {
            case "violence" -> { catBuilder.violence(hit); scoreBuilder.violence(score); }
            case "hate" -> { catBuilder.hate(hit); scoreBuilder.hate(score); }
            case "sexual" -> { catBuilder.sexual(hit); scoreBuilder.sexual(score); }
            case "selfHarm" -> { catBuilder.selfHarm(hit); scoreBuilder.selfHarm(score); }
            case "criminal" -> { catBuilder.criminal(hit); scoreBuilder.criminal(score); }
            default -> { }
        }
    }
}
