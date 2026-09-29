package com.guonl.flow.core.tool;

import com.guonl.flow.ai.AiInvoker;
import com.jayway.jsonpath.JsonPath;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 内置工具集：当前时间 / 数学计算 / 网页抓取 / JSON提取。
 * <p>作为 TOOL 节点的工具能力供给模型自主调用；工具执行轨迹通过
 * {@link ToolContext} 携带的列表回传（调用方在 .toolContext() 中传入），
 * 避免依赖线程上下文（响应式工具执行不在调用线程上）。</p>
 */
@Component
public class FlowTools {

    /** 轨迹列表在 toolContext 中的键名 */
    public static final String TRACE_KEY = "toolTrace";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    private final RestClient http = RestClient.builder()
        .requestFactory(simpleFactory())
        .build();

    private static SimpleClientHttpRequestFactory simpleFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        return factory;
    }

    @Tool(description = "获取当前日期时间（格式 yyyy-MM-dd HH:mm:ss），任何需要知道现在时间的场景都应调用")
    public String now(ToolContext toolContext) {
        return record("now", "{}", LocalDateTime.now().format(TS), toolContext);
    }

    @Tool(description = "计算数学表达式并返回结果，支持加减轻乘除取余与括号，例如 (3+5)*2")
    public String calc(@ToolParam(description = "数学表达式，例如 (3+5)*2") String expression,
                       ToolContext toolContext) {
        try {
            Expression expr = spelParser.parseRaw(expression.trim());
            Object value = expr.getValue(SimpleEvaluationContext.forReadOnlyDataBinding().build());
            String result = value == null ? "0" : String.valueOf(value);
            return record("calc", expression.trim(), result, toolContext);
        } catch (Exception e) {
            return record("calc", expression.trim(), "计算失败: " + e.getMessage(), toolContext);
        }
    }

    @Tool(description = "抓取指定URL网页内容并返回纯文本正文（自动去标签、截断到4000字符），需要查询网页信息时使用")
    public String httpGet(@ToolParam(description = "完整的 http/https 网址") String url,
                          ToolContext toolContext) {
        try {
            String body = http.get().uri(URI.create(url.trim())).retrieve().body(String.class);
            String text = body == null ? "" : body
                .replaceAll("(?s)<script[\\s\\S]*?</script>", " ")
                .replaceAll("(?s)<style[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
            if (text.length() > 4000) {
                text = text.substring(0, 4000) + "…(已截断)";
            }
            return record("httpGet", url.trim(), text.isEmpty() ? "（空页面）" : text, toolContext);
        } catch (Exception e) {
            return record("httpGet", url.trim(), "抓取失败: " + e.getMessage(), toolContext);
        }
    }

    @Tool(description = "从JSON文本中按JsonPath提取数据，例如 $.items[0].name 提取数组第一项的name字段")
    public String jsonPath(@ToolParam(description = "JSON文本") String json,
                           @ToolParam(description = "JsonPath表达式，例如 $.items[0].name") String path,
                           ToolContext toolContext) {
        try {
            Object value = JsonPath.read(json, path.trim());
            String result = value == null ? "null" : (value instanceof String s ? s : String.valueOf(value));
            return record("jsonPath", path.trim(), result, toolContext);
        } catch (Exception e) {
            return record("jsonPath", path.trim(), "提取失败: " + e.getMessage(), toolContext);
        }
    }

    /** 记录工具执行轨迹并返回结果文本（static 供 MCP 工具装饰器复用同一轨迹格式） */
    public static String record(String name, String arguments, String result, ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(TRACE_KEY) instanceof List<?> trace) {
            @SuppressWarnings("unchecked")
            List<AiInvoker.ToolCallTrace> list = (List<AiInvoker.ToolCallTrace>) trace;
            list.add(new AiInvoker.ToolCallTrace(name, arguments, result));
        }
        return result;
    }
}
