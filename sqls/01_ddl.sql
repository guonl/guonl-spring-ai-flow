-- =====================================================================
-- guonl-spring-ai-flow 数据库初始化脚本（DDL）
-- 数据库：guonl_ai_flow
-- 说明：
--   1. 所有字段均 NOT NULL；
--   2. 除 Date(DATETIME)、Json(JSON)、Text(MEDIUMTEXT) 类型外，
--      其他类型均包含默认值；
--   3. 执行方式：
--      mysql -h <host> -P <port> -u <user> -p --default-character-set=utf8mb4 < sqls/01_ddl.sql
--   4. 建表后可执行 sqls/02_dml.sql 写入三个内置示例流程
--      （不执行也可以，应用启动时检测到流程库为空会自动写入同样的种子数据）。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `guonl_ai_flow` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `guonl_ai_flow`;

-- ---------------------------------------------------------------------
-- 流程定义表：流程编排的持久化单元（节点/连线以 JSON 存储）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_definition` (
    `id`          VARCHAR(40)   NOT NULL COMMENT '流程ID（f_前缀，应用生成）',
    `name`        VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '流程名称',
    `description` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '流程描述',
    `nodes`       JSON          NOT NULL COMMENT '节点定义列表（JSON数组，NodeDefinition结构）',
    `edges`       JSON          NOT NULL COMMENT '连线定义列表（JSON数组，EdgeDefinition结构）',
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '流程定义表';

-- ---------------------------------------------------------------------
-- 流程运行记录表：一次流程提交运行的快照与结果统计
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_run` (
    `run_id`        VARCHAR(32)  NOT NULL COMMENT '运行ID（r_前缀，应用生成）',
    `flow_id`       VARCHAR(40)  NOT NULL DEFAULT '' COMMENT '流程ID',
    `flow_name`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '流程名称快照',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT '运行状态：RUNNING/SUCCESS/FAILED',
    `input_summary` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '输入文本摘要（≤120字）',
    `input_images`  INT          NOT NULL DEFAULT 0 COMMENT '输入图片数量',
    `input_rows`    INT          NOT NULL DEFAULT 0 COMMENT '输入Excel行数',
    `cost_millis`   BIGINT       NOT NULL DEFAULT 0 COMMENT '总耗时（毫秒）',
    `total_tokens`  INT          NOT NULL DEFAULT 0 COMMENT '全部节点Token消耗合计',
    `start_at`      BIGINT       NOT NULL DEFAULT 0 COMMENT '开始时间（epoch毫秒）',
    `end_at`        BIGINT       NOT NULL DEFAULT 0 COMMENT '结束时间（epoch毫秒，0表示未结束）',
    PRIMARY KEY (`run_id`),
    KEY `idx_flow_run_start_at` (`start_at`),
    KEY `idx_flow_run_flow_id` (`flow_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '流程运行记录表';

-- ---------------------------------------------------------------------
-- 流程节点执行记录表：一次运行中每个节点的实时状态与输出（运行页轮询数据源）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_node_execution` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `run_id`            VARCHAR(32)   NOT NULL COMMENT '所属运行ID',
    `seq`               INT           NOT NULL DEFAULT 0 COMMENT '节点序号（按流程定义顺序，用于还原展示顺序）',
    `node_id`           VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '节点ID',
    `node_name`         VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '节点名称',
    `node_type`         VARCHAR(32)   NOT NULL DEFAULT 'TEXT' COMMENT '节点类型：TEXT/IMAGE/EXCEL/JSON/COMBINED',
    `status`            VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '节点状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED',
    `output`            MEDIUMTEXT    NOT NULL COMMENT '模型原始输出',
    `parsed_json`       MEDIUMTEXT    NOT NULL COMMENT 'JSON契约解析后的格式化输出（非JSON契约为空串）',
    `error`             VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '失败/取消原因',
    `model`             VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '实际使用模型',
    `prompt_tokens`     INT           NOT NULL DEFAULT 0 COMMENT '输入Token数',
    `completion_tokens` INT           NOT NULL DEFAULT 0 COMMENT '输出Token数',
    `total_tokens`      INT           NOT NULL DEFAULT 0 COMMENT 'Token总数',
    `cost_millis`       BIGINT        NOT NULL DEFAULT 0 COMMENT '节点耗时（毫秒）',
    `start_at`          BIGINT        NOT NULL DEFAULT 0 COMMENT '开始时间（epoch毫秒）',
    `end_at`            BIGINT        NOT NULL DEFAULT 0 COMMENT '结束时间（epoch毫秒）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_flow_node_run_node` (`run_id`, `node_id`),
    KEY `idx_flow_node_run_id` (`run_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '流程节点执行记录表';

-- ---------------------------------------------------------------------
-- 知识库表：RAG 知识库元数据（向量切片以 SimpleVectorStore JSON 形式落盘）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_knowledge` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库ID',
    `name`        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '知识库名称',
    `description` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '描述',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '知识库表';

-- ---------------------------------------------------------------------
-- 知识库文档表：知识库内文档元数据（chunk_ids 指向向量库切片）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_knowledge_doc` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    `kb_id`           BIGINT       NOT NULL DEFAULT 0 COMMENT '所属知识库ID',
    `doc_name`        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文档名称',
    `chunk_count`     INT          NOT NULL DEFAULT 0 COMMENT '切片数',
    `total_chunks`    INT          NOT NULL DEFAULT 0 COMMENT '切片总数（ETL进度基数）',
    `processed_chunks` INT         NOT NULL DEFAULT 0 COMMENT '已向量化切片数（ETL进度）',
    `chunk_ids`       MEDIUMTEXT   NOT NULL COMMENT '向量库切片ID列表（JSON数组字符串）',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'READY' COMMENT '状态：PROCESSING/READY/FAILED',
    `error_msg`       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '失败原因（FAILED时）',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_flow_knowledge_doc_kb` (`kb_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '知识库文档表';

-- =====================================================================
-- AI 助手会话元数据表（需手动执行）
-- 说明：消息正文存于 Spring AI JDBC ChatMemory 表 SPRING_AI_CHAT_MEMORY
--       （由 spring.ai.chat.memory.repository.jdbc.initialize-schema=always 自动维护），
--       本表仅存会话级元数据，供会话列表/跨页续聊使用。
-- =====================================================================
CREATE TABLE IF NOT EXISTS `assistant_session` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` VARCHAR(64)  NOT NULL                COMMENT '会话ID（与 SPRING_AI_CHAT_MEMORY.conversation_id 对齐）',
    `title`           VARCHAR(128) NOT NULL DEFAULT '新会话' COMMENT '标题（首条用户消息前40字）',
    `message_count`   INT          NOT NULL DEFAULT 0      COMMENT '消息条数（用户+助手）',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation` (`conversation_id`),
    KEY `idx_updated` (`updated_at`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='AI 助手会话元数据';

-- =====================================================================
-- P2 助手增强：消息反馈表 + 会话记忆内容列扩容（需手动执行，或由脚本自动执行）
-- 1. assistant_feedback：助手消息 👍/👎 反馈（按 会话ID+内容指纹 唯一，可改评/取消）
-- 2. SPRING_AI_CHAT_MEMORY.content 扩为 MEDIUMTEXT：多模态消息（含图片 base64）可能超 TEXT 64KB 上限
-- =====================================================================
CREATE TABLE IF NOT EXISTS `assistant_feedback` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` VARCHAR(64)  NOT NULL                COMMENT '会话ID（与 SPRING_AI_CHAT_MEMORY.conversation_id 对齐）',
    `content_hash`    VARCHAR(32)  NOT NULL                COMMENT '消息内容指纹（djb2 32位hex，前后端同算法）',
    `rating`          VARCHAR(4)   NOT NULL                COMMENT '反馈：up / down',
    `content_preview` VARCHAR(500) DEFAULT NULL            COMMENT '消息内容预览（前500字）',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg` (`conversation_id`, `content_hash`),
    KEY `idx_conv` (`conversation_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='AI 助手消息反馈';

-- =====================================================================
-- 注：会话记忆表 SPRING_AI_CHAT_MEMORY 由 Spring AI 启动时自动创建
--    （spring.ai.chat.memory.repository.jdbc.initialize-schema=always，幂等），无需在此建表。
-- =====================================================================
CREATE TABLE IF NOT EXISTS `spring_ai_chat_memory` (
     `conversation_id` varchar(36) COLLATE utf8mb4_general_ci NOT NULL,
     `content` mediumtext COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息内容',
     `type` enum('USER','ASSISTANT','SYSTEM','TOOL') COLLATE utf8mb4_general_ci NOT NULL,
     `timestamp` timestamp NOT NULL,
     `sequence_id` bigint NOT NULL,
     KEY `SPRING_AI_CHAT_MEMORY_CONV_TS_IDX` (`conversation_id`,`timestamp`),
     KEY `SPRING_AI_CHAT_MEMORY_CONV_SEQ_IDX` (`conversation_id`,`sequence_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
    COMMENT='AI 助手会话记忆';
