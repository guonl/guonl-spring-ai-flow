package com.guonl.flow.core.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点输出契约。
 * <p>json 模式下通过 fields 定义期望的输出字段（字段名 -> 说明），
 * 由提示词组装时注入「输出要求」，并作为结果解析与展示的依据；
 * text 模式直接输出模型原始文本。</p>
 */
@Data
public class OutputSpec {

    /** 输出类型：json | text */
    private String type = "json";

    /** JSON输出字段契约（type=json 时生效） */
    private List<Field> fields = new ArrayList<>();

    @Data
    public static class Field {

        /** 字段名 */
        private String name;

        /** 字段说明（业务含义、格式约束） */
        private String desc;

        /** 是否必填 */
        private boolean required = false;

        public Field() {
        }

        public Field(String name, String desc, boolean required) {
            this.name = name;
            this.desc = desc;
            this.required = required;
        }
    }

    public boolean isJson() {
        return !"text".equalsIgnoreCase(type);
    }
}
