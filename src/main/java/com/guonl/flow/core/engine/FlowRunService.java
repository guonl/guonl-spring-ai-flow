package com.guonl.flow.core.engine;

import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.store.RunStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

/**
 * 流程运行服务：「提交即返回runId + 前端轮询」的异步运行门面。
 * <p>运行线程池独立于节点执行池，避免运行等待与节点调度互相争抢。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowRunService {

    private final FlowEngine flowEngine;

    private final RunStore runStore;

    private ExecutorService runExecutor;

    @PostConstruct
    public void init() {
        runExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "flow-run-" + UUID.randomUUID().toString().substring(0, 4));
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
        runExecutor.shutdownNow();
    }

    /**
     * 提交运行：立即注册RUNNING快照并返回（含runId），后台异步执行至终态。
     * 前端凭 runId 轮询 /api/runs/{runId} 获取节点实时状态。
     */
    public FlowExecution start(FlowDefinition flow, FlowInput input) {
        FlowExecution execution = flowEngine.prepare(flow, input);
        runStore.save(execution);
        runExecutor.submit(() -> {
            try {
                flowEngine.run(execution, flow, input);
            } catch (Exception e) {
                log.error("[Run] {} crashed", execution.getRunId(), e);
                execution.setStatus(FlowExecution.Status.FAILED);
                execution.setEndAt(System.currentTimeMillis());
                runStore.finish(execution);
            }
        });
        return execution;
    }
}
