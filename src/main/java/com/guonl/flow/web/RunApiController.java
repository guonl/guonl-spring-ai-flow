package com.guonl.flow.web;

import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.RunStreamHub;
import com.guonl.flow.core.model.PageResult;
import com.guonl.flow.core.store.RunStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 运行查询 API：历史列表、运行快照轮询、Dashboard统计。
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Tag(name = "运行记录查询", description = "运行历史列表、运行快照轮询、Dashboard 统计")
public class RunApiController {

    private final RunStore runStore;

    private final RunStreamHub streamHub;

    @Operation(summary = "运行历史列表", description = "按开始时间倒序返回运行记录（含各节点执行明细）")
    @GetMapping
    public List<FlowExecution> list() {
        return runStore.list();
    }

    @Operation(summary = "运行历史条件分页检索",
        description = "条件：runId 运行ID模糊 / flow 流程名称或ID模糊 / status 状态精确（RUNNING/SUCCESS/FAILED/CANCELLED）"
            + " / startDate 与 endDate 时间范围（yyyy-MM-dd，含起止两天）；按开始时间倒序")
    @GetMapping("/page")
    public PageResult<FlowExecution> page(@RequestParam(value = "runId", required = false) String runId,
                                          @RequestParam(value = "flow", required = false) String flow,
                                          @RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "startDate", required = false) String startDate,
                                          @RequestParam(value = "endDate", required = false) String endDate,
                                          @RequestParam(value = "page", defaultValue = "1") int page,
                                          @RequestParam(value = "size", defaultValue = "15") int size) {
        return runStore.search(runId, flow, status, startDate, endDate, page, size);
    }

    /** 前端轮询端点：返回含全部节点实时状态的完整快照 */
    @Operation(summary = "运行快照查询", description = "前端轮询端点：返回含全部节点实时状态的完整快照")
    @GetMapping("/{runId}")
    public FlowExecution get(@PathVariable String runId) {
        return runStore.require(runId);
    }

    /** SSE 流式端点：订阅该运行的节点增量输出（打字机效果），运行结束收到 done 帧后关闭 */
    @Operation(summary = "运行流式订阅", description = "SSE 推送各节点增量输出，运行结束发送 done 帧")
    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId) {
        runStore.require(runId); // 不存在的运行直接 404
        return streamHub.subscribe(runId);
    }

    @Operation(summary = "运行统计", description = "Dashboard 统计：总数/运行中/成功/失败/成功率/平均耗时/总Token")
    @GetMapping("/stats")
    public RunStore.Stats stats() {
        return runStore.stats();
    }
}
