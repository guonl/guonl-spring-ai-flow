/* ============================================================
   run.js —— 流程运行页：动态输入表单 + 提交 + 800ms轮询 + DAG实时渲染
   ============================================================ */
(function () {
  const flowId = window.__FLOW_ID__;
  let pollTimer = null;
  let selectedNodeId = null;
  let lastExec = null;
  let streamBuf = {};       // SSE 增量缓冲：nodeId -> 已收到的实时全文
  let eventSource = null;   // 当前运行的 SSE 订阅

  // ---------- 加载流程定义，装配输入表单 ----------
  async function loadFlow() {
    const flow = await apiGet('/api/flows/' + flowId);
    document.getElementById('flowTitle').textContent = flow.name || flowId;
    document.getElementById('flowDesc').textContent = flow.description || '';

    const nodes = flow.nodes || [];
    const hasImage = nodes.some(n => n.type === 'IMAGE' || n.type === 'COMBINED');
    const hasExcel = nodes.some(n => n.type === 'EXCEL' || n.type === 'COMBINED'
      || (n.prompt || '').includes('{{input.rows}}'));

    if (!hasImage) { document.getElementById('grp-imageUrl').style.display = 'none'; document.getElementById('grp-images').style.display = 'none'; }
    if (!hasExcel) document.getElementById('grp-excel').style.display = 'none';
  }

  // ---------- 提交运行 ----------
  document.getElementById('runForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = document.getElementById('btnRun');
    btn.disabled = true;
    btn.innerHTML = '<span class="spin"></span> 提交中…';
    document.getElementById('runHint').textContent = '';
    try {
      const fd = new FormData();
      const text = document.getElementById('fText').value.trim();
      const imageUrl = document.getElementById('fImageUrl').value.trim();
      if (text) fd.append('text', text);
      if (imageUrl) fd.append('imageUrl', imageUrl);
      const images = document.getElementById('fImages').files;
      for (const f of images) fd.append('images', f);
      const excel = document.getElementById('fExcel').files[0];
      if (excel) fd.append('excel', excel);
      const audio = document.getElementById('fAudio').files[0];
      if (audio) fd.append('audio', audio);
      const conversationId = document.getElementById('fConversationId').value.trim();
      if (conversationId) fd.append('conversationId', conversationId);

      const exec = await apiPostForm(`/api/flows/${flowId}/run`, fd);
      startPolling(exec.runId);
      document.getElementById('runHint').textContent = '已提交，runId=' + exec.runId;
    } catch (err) {
      btn.disabled = false;
      btn.textContent = '▶ 提交运行';
    }
  });

  // ---------- SSE 流式订阅：节点增量输出打字机 ----------
  function subscribeStream(runId) {
    if (eventSource) { eventSource.close(); eventSource = null; }
    streamBuf = {};
    const es = new EventSource(`/api/runs/${runId}/stream`);
    eventSource = es;
    es.onmessage = ev => {
      let p; try { p = JSON.parse(ev.data); } catch (e) { return; }
      if (p.done) { es.close(); if (eventSource === es) eventSource = null; return; }
      streamBuf[p.nodeId] = (streamBuf[p.nodeId] || '') + p.delta;
      renderStreaming(p.nodeId);
    };
    es.onerror = () => { /* EventSource 自动重连；运行已结束的 404 交给轮询兜底 */ };
  }

  // 运行中节点的增量输出实时渲染到详情面板
  function renderStreaming(nodeId) {
    if (selectedNodeId !== nodeId) return;
    const out = document.getElementById('ndOutput');
    if (out) out.textContent = streamBuf[nodeId] || '';
  }

  // ---------- 轮询 ----------
  function startPolling(runId) {
    subscribeStream(runId);
    document.getElementById('progressBar').style.display = '';
    const tick = async () => {
      let exec;
      try { exec = await apiGet('/api/runs/' + runId); }
      catch (e) { pollTimer = setTimeout(tick, 2000); return; }
      lastExec = exec;
      render(exec);
      if (exec.finished) {
        clearTimeout(pollTimer);
        if (eventSource) { eventSource.close(); eventSource = null; }
        const btn = document.getElementById('btnRun');
        btn.disabled = false;
        btn.textContent = '▶ 再次运行';
        toast(exec.status === 'SUCCESS' ? '流程执行成功' : '流程执行失败', exec.status === 'SUCCESS' ? 'ok' : 'err');
      } else {
        pollTimer = setTimeout(tick, 800);
      }
    };
    tick();
  }

  // ---------- DAG 渲染 ----------
  function layerOf(nodes, edges) {
    const depth = {};
    const parents = {};
    edges.forEach(e => (parents[e.to] = [...(parents[e.to] || []), e.from]));
    const resolve = (id, guard) => {
      if (depth[id] !== undefined) return depth[id];
      if (guard.has(id)) return 0; // 防环
      guard.add(id);
      const ps = parents[id] || [];
      depth[id] = ps.length ? Math.max(...ps.map(p => resolve(p, guard))) + 1 : 0;
      return depth[id];
    };
    nodes.forEach(n => resolve(n.id, new Set()));
    return depth;
  }

  function render(exec) {
    document.getElementById('flowBadge').innerHTML = statusBadge(exec.status);
    const total = Object.keys(exec.nodes).length;
    const done = Object.values(exec.nodes).filter(n => n.status === 'SUCCESS' || n.status === 'FAILED' || n.status === 'CANCELLED').length;
    document.querySelector('#progressBar > i').style.width = (total ? Math.round(done / total * 100) : 0) + '%';
    document.getElementById('runSummary').innerHTML =
      `<span>进度 <b>${done}/${total}</b></span><span>耗时 <b>${fmtCost(exec.costMillis)}</b></span><span>Tokens <b>${(exec.totalTokens ?? 0).toLocaleString()}</b></span>`;
    document.getElementById('btnAskDiagnose').style.display = '';

    const nodes = Object.values(exec.nodes);

    // 按依赖深度分层渲染
    getEdges().then(edges => {
      const depth = layerOf(nodes.map(n => ({ id: n.nodeId })), edges);
      const layers = {};
      nodes.forEach(n => (layers[depth[n.nodeId] ?? 0] = [...(layers[depth[n.nodeId] ?? 0] || []), n]));
      const maxLayer = Math.max(...Object.keys(layers).map(Number), 0);
      document.getElementById('dag').innerHTML = Array.from({ length: maxLayer + 1 }, (_, L) => `
        <div class="dag-layer">${(layers[L] || []).map(nodeCard).join('')}</div>`).join('');
      bindNodeClick();
      if (selectedNodeId) showDetail(selectedNodeId);
    });
  }

  let edgesPromise = null;
  function getEdges() {
    if (!edgesPromise) edgesPromise = apiGet('/api/flows/' + flowId).then(f => f.edges || []);
    return edgesPromise;
  }

  function nodeCard(n) {
    const m = NT[n.nodeType] || { icon: '❓', color: '#94a3b8', label: n.nodeType };
    const st = n.status;
    return `<div class="dag-node st-${st}" data-node="${esc(n.nodeId)}" style="--dot:${m.color}">
      <div class="head">
        <span class="ico">${m.icon}</span>
        <div><b>${esc(n.nodeName || n.nodeId)}</b><span>${m.label} · ${esc(n.nodeId)}</span></div>
        <span class="status-dot" title="${STATUS_LABEL[st]}"></span>
      </div>
      <div class="foot">
        <span>${esc(n.model || '')}</span>
        ${n.costMillis ? `<span>· ${fmtCost(n.costMillis)}</span>` : ''}
        ${n.totalTokens ? `<span>· ${n.totalTokens} tok</span>` : ''}
        ${st === 'FAILED' ? '<span style="color:var(--failed)">· 失败</span>' : ''}
        ${st === 'CANCELLED' ? '<span>· 已取消</span>' : ''}
      </div>
    </div>`;
  }

  function bindNodeClick() {
    document.querySelectorAll('.dag-node').forEach(el => {
      el.addEventListener('click', () => { selectedNodeId = el.dataset.node; showDetail(selectedNodeId); });
    });
  }

  function showDetail(nodeId) {
    if (!lastExec) return;
    const n = Object.values(lastExec.nodes).find(x => x.nodeId === nodeId);
    if (!n) return;
    document.getElementById('nodeDetail').style.display = '';
    document.getElementById('ndTitle').textContent = `${n.nodeName || nodeId} · 节点输出`;
    document.getElementById('ndMeta').textContent =
      `${STATUS_LABEL[n.status] || n.status} · ${n.model || '–'} · ${fmtCost(n.costMillis)} · ${n.totalTokens || 0} tokens`;
    const out = document.getElementById('ndOutput');
    // 工具调用轨迹（SSE 只传增量，轨迹在轮询数据到达后展示）
    const traceEl = document.getElementById('ndTrace');
    if (n.toolCalls && n.toolCalls.length) {
      traceEl.style.display = '';
      const isKb = n.nodeType === 'KNOWLEDGE';
      const isMod = n.nodeType === 'MODERATION';
      traceEl.innerHTML = `<div class="trace-title">${isKb ? '📚 知识命中片段' : isMod ? '🛡️ 审核记录' : '🔧 工具调用轨迹'}（${n.toolCalls.length} ${isKb ? '条检索' : '次'}）</div>`
        + n.toolCalls.map(t =>
          `<div class="trace-item"><b>${esc(t.name)}</b> <span class="args">${esc(t.arguments || '')}</span>`
          + `<div class="result">${esc(t.result || '')}</div></div>`).join('');
    } else {
      traceEl.style.display = 'none';
    }
    // 流式优先：运行中且有增量缓冲时显示实时输出；到终态清缓冲，改用轮询数据（防空输出覆盖闪烁）
    const streaming = n.status === 'RUNNING' && streamBuf[nodeId];
    if (n.status !== 'RUNNING' && streamBuf[nodeId]) delete streamBuf[nodeId];
    if (streaming) out.textContent = streamBuf[nodeId];
    else if (n.error) out.textContent = '✗ ' + n.error;
    else if (n.nodeType === 'IMAGE_GEN' && (n.output || n.parsedJson)) renderImageGen(out, n);
    else if (n.nodeType === 'MODERATION' && n.parsedJson) renderModeration(out, n);
    else if (n.nodeType === 'EVALUATE' && n.parsedJson) renderEvaluate(out, n);
    else if (n.parsedJson) out.textContent = pretty(n.parsedJson);
    else if (n.output) out.textContent = n.output;
    else out.textContent = n.status === 'RUNNING' ? '⏳ 执行中，等待模型输出…' : '（暂无输出）';
  }

  /** 图片生成节点：output/parsedJson.url 即图片地址，渲染预览 */
  function renderImageGen(out, n) {
    let j = null; try { j = typeof n.parsedJson === 'string' ? JSON.parse(n.parsedJson) : n.parsedJson; } catch (e) { /* 忽略 */ }
    const url = (j && j.url) || (n.output || '').trim();
    if (!url) { out.textContent = '（暂无输出）'; return; }
    out.innerHTML = `<img class="gen-image" src="${esc(url)}" alt="生成图片">` + `<div class="gen-image-url">${esc(url)}</div>`;
  }

  /** 内容审核节点：parsedJson {flagged, categories} 渲染徽章与类别标签 */
  function renderModeration(out, n) {
    let j = null; try { j = typeof n.parsedJson === 'string' ? JSON.parse(n.parsedJson) : n.parsedJson; } catch (e) { /* 忽略 */ }
    if (!j || typeof j.flagged !== 'boolean') { out.textContent = n.output || ''; return; }
    const cats = Array.isArray(j.categories) ? j.categories : [];
    out.innerHTML = (j.flagged
      ? '<div class="mod-badge flagged">⚠️ 审核不通过，命中违规类别</div>'
      : '<div class="mod-badge pass">✅ 审核通过</div>')
      + cats.map(c => `<span class="mod-cat">${esc(c)}</span>`).join('');
  }

  /** 输出评估节点：parsedJson {score, passed, reason, dimensions} 渲染评分面板 */
  function renderEvaluate(out, n) {
    let j = null; try { j = typeof n.parsedJson === 'string' ? JSON.parse(n.parsedJson) : n.parsedJson; } catch (e) { /* 忽略 */ }
    if (!j || typeof j.score !== 'number') { out.textContent = n.output || ''; return; }
    const passed = j.passed === true;
    out.innerHTML = `<div class="eval-head"><div class="eval-score">${j.score}<small>/100</small></div>`
      + `<span class="mod-badge ${passed ? 'pass' : 'flagged'}">${passed ? '✅ 达标' : '⚠️ 不达标'}</span></div>`
      + (j.dimensions ? `<div class="eval-dims">${esc(String(j.dimensions))}</div>` : '')
      + (j.reason ? `<div class="eval-reason">${esc(j.reason)}</div>` : '');
  }

  function pretty(text) {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
  }

  // ---------- 问助手诊断：组装运行上下文预填进助手输入 ----------
  document.getElementById('btnAskDiagnose').addEventListener('click', () => {
    if (!lastExec) return;
    const failed = lastExec.status === 'FAILED';
    const lines = [
      '🩺 请帮我诊断这次流程运行：',
      '- runId：' + lastExec.runId,
      '- 流程：' + (document.getElementById('flowTitle').textContent || flowId) + '（' + flowId + '）',
      '- 状态：' + (STATUS_LABEL[lastExec.status] || lastExec.status),
      '- 耗时：' + fmtCost(lastExec.costMillis),
      '',
      failed
        ? '运行失败了，请先用 get_run 工具查询 ' + lastExec.runId + ' 的失败节点与错误信息，定位原因并给出修复建议。'
        : '请分析这次运行的节点表现（可调用 get_run 查询详情），给出优化建议。',
    ];
    if (window.GuonlAssistant) window.GuonlAssistant.open({ text: lines.join('\n') });
  });

  loadFlow().catch(e => toast('流程加载失败：' + e.message, 'err'));
})();
