package com.guonl.flow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 产品级配置，前缀 {@code flow}。
 */
@Data
@ConfigurationProperties(prefix = "flow")
public class FlowProperties {

    private Excel excel = new Excel();

    /** Excel处理配置 */
    @Data
    public static class Excel {

        /** 送入模型的最大行数（超出截断并提示） */
        private int maxRows = 60;

        /** 每行送入模型的最大字符数 */
        private int maxCellChars = 200;
    }
}
