package com.guonl.flow.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.guonl.flow.core.model.EdgeDefinition;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.PageResult;
import com.guonl.flow.db.entity.FlowDefinitionDO;
import com.guonl.flow.db.mapper.FlowDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 流程定义存储（MySQL flow_definition 表）：
 * nodes/edges 以 JSON 字符串列持久化，领域模型与表实体在层内互转。
 */
@Component
@RequiredArgsConstructor
public class FlowStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final FlowDefinitionMapper definitionMapper;

    /** 全量列表（按更新时间倒序） */
    public List<FlowDefinition> list() {
        List<FlowDefinition> list = new ArrayList<>();
        for (FlowDefinitionDO record : definitionMapper.findAll()) {
            list.add(toDomain(record));
        }
        return list;
    }

    public FlowDefinition get(String id) {
        FlowDefinitionDO record = definitionMapper.findById(id);
        return record == null ? null : toDomain(record);
    }

    /** 关键词分页检索（name/description 模糊匹配，按更新时间倒序；keyword 空则全量） */
    public PageResult<FlowDefinition> search(String keyword, int page, int size) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = definitionMapper.countSearch(kw);
        List<FlowDefinition> items = new ArrayList<>();
        if (total > 0) {
            int offset = (safePage - 1) * safeSize;
            if (offset < total) {
                for (FlowDefinitionDO record : definitionMapper.searchPage(kw, offset, safeSize)) {
                    items.add(toDomain(record));
                }
            }
        }
        return new PageResult<>(items, total, safePage, safeSize);
    }

    public FlowDefinition require(String id) {
        FlowDefinition flow = get(id);
        if (flow == null) {
            throw new IllegalArgumentException("流程不存在: " + id);
        }
        return flow;
    }

    /** 新建或更新（按ID判断） */
    public FlowDefinition save(FlowDefinition flow) {
        LocalDateTime now = LocalDateTime.now();
        boolean isNew = !StringUtils.hasText(flow.getId());
        if (isNew) {
            flow.setId("f_" + UUID.randomUUID().toString().substring(0, 8));
            flow.setCreatedAt(now);
        }
        flow.setUpdatedAt(now);
        FlowDefinitionDO record = toDO(flow);
        if (isNew || definitionMapper.findById(flow.getId()) == null) {
            definitionMapper.insert(record);
        } else {
            definitionMapper.update(record);
        }
        return flow;
    }

    public boolean delete(String id) {
        return definitionMapper.deleteById(id) > 0;
    }

    // ---------- 领域模型 <-> 表实体 ----------

    private FlowDefinitionDO toDO(FlowDefinition flow) {
        FlowDefinitionDO d = new FlowDefinitionDO();
        d.setId(flow.getId());
        d.setName(flow.getName());
        d.setDescription(flow.getDescription());
        d.setNodesJson(writeJson(flow.getNodes()));
        d.setEdgesJson(writeJson(flow.getEdges()));
        d.setCreatedAt(flow.getCreatedAt());
        d.setUpdatedAt(flow.getUpdatedAt());
        return d;
    }

    private FlowDefinition toDomain(FlowDefinitionDO d) {
        FlowDefinition f = new FlowDefinition();
        f.setId(d.getId());
        f.setName(d.getName());
        f.setDescription(d.getDescription());
        f.setNodes(readJson(d.getNodesJson(), new TypeReference<List<NodeDefinition>>() {
        }));
        f.setEdges(readJson(d.getEdgesJson(), new TypeReference<List<EdgeDefinition>>() {
        }));
        f.setCreatedAt(d.getCreatedAt());
        f.setUpdatedAt(d.getUpdatedAt());
        return f;
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("流程定义序列化失败: " + e.getMessage(), e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "[]" : json, type);
        } catch (Exception e) {
            throw new IllegalStateException("流程定义反序列化失败: " + e.getMessage(), e);
        }
    }
}
