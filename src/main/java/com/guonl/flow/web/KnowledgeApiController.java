package com.guonl.flow.web;

import com.guonl.flow.db.entity.KnowledgeBaseDO;
import com.guonl.flow.db.entity.KnowledgeDocDO;
import com.guonl.flow.knowledge.KnowledgeService;
import com.guonl.flow.knowledge.RetrievedChunk;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 REST API：知识库CRUD、文档上传（ETL入库）、文档管理、相似度检索调试。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库CRUD、文档上传ETL入库、相似度检索")
public class KnowledgeApiController {

    private final KnowledgeService knowledgeService;

    @Operation(summary = "新建知识库")
    @PostMapping
    public KnowledgeBaseDO create(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        return knowledgeService.create(name, body.getOrDefault("description", ""));
    }

    @Operation(summary = "知识库列表", description = "含文档数与切片数统计")
    @GetMapping
    public List<KnowledgeBaseDO> list() {
        return knowledgeService.list();
    }

    @Operation(summary = "知识库详情")
    @GetMapping("/{id}")
    public KnowledgeBaseDO get(@PathVariable Long id) {
        return requireKb(id);
    }

    @Operation(summary = "删除知识库", description = "级联删除文档记录、向量数据与落盘文件")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        requireKb(id);
        knowledgeService.delete(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        return result;
    }

    @Operation(summary = "文档列表", description = "含ETL进度字段（status=PROCESSING时可展示进度条）")
    @GetMapping("/{id}/docs")
    public List<KnowledgeDocDO> listDocs(@PathVariable Long id) {
        requireKb(id);
        return knowledgeService.listDocs(id);
    }

    @Operation(summary = "上传文档", description = "支持txt/md/json/csv等纯文本文件；同步切分落库（PROCESSING）后立即返回，分批嵌入向量化由后台异步执行，进度见文档列表 processed_chunks/total_chunks")
    @PostMapping("/{id}/docs")
    public KnowledgeDocDO uploadDoc(@PathVariable Long id,
                                    @RequestParam("file") MultipartFile file) throws Exception {
        requireKb(id);
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            name = "untitled.txt";
        }
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new IllegalArgumentException("文件内容为空");
        }
        return knowledgeService.uploadDoc(id, name, text);
    }

    @Operation(summary = "删除文档", description = "同步移除向量库中该文档的全部切片（处理中的文档不允许删除）")
    @DeleteMapping("/{id}/docs/{docId}")
    public Map<String, Object> deleteDoc(@PathVariable Long id, @PathVariable Long docId) {
        requireKb(id);
        knowledgeService.deleteDoc(id, docId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        return result;
    }

    @Operation(summary = "相似度检索", description = "输入查询文本，返回最相关片段（调试知识库检索效果）")
    @PostMapping("/{id}/search")
    public List<RetrievedChunk> search(@PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        requireKb(id);
        String query = String.valueOf(body.getOrDefault("query", "")).trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        Integer topK = null;
        Object topKValue = body.get("topK");
        if (topKValue instanceof Number number) {
            topK = number.intValue();
        }
        return knowledgeService.search(id, query, topK);
    }

    private KnowledgeBaseDO requireKb(Long id) {
        KnowledgeBaseDO kb = knowledgeService.get(id);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在：" + id);
        }
        return kb;
    }
}
