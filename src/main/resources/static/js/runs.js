/* ============================================================
   runs.js —— 运行历史：统计 + 条件分页检索 + 详情弹层
   ============================================================ */
(function () {
  const body = document.getElementById('runsBody');
  const pager = document.getElementById('runPager');
  const totalEl = document.getElementById('runTotal');
  const filters = {
    runId: document.getElementById('fRunId'),
    flow: document.getElementById('fFlow'),
    status: document.getElementById('fStatus'),
    start: document.getElementById('fStart'),
    end: document.getElementById('fEnd'),
  };

  const state = { page: 1, size: 15, total: 0 };
  let curRun = null;   // 详情弹层当前展示的运行（供「问助手诊断」取上下文）

  async function loadStats() {
    try {
      const s = await apiGet('/api/runs/stats');
      document.getElementById('stTotal').textContent = s.total ?? 0;
      document.getElementById('stRate').textContent = (s.successRate ?? 0) + '%';
      document.getElementById('stCost').textContent = fmtCost(s.avgCostMillis || 0);
      document.getElementById('stTokens').textContent = (s.totalTokens ?? 0).toLocaleString();
      document.getElementById('stCalls').textContent = (s.llmCalls ?? 0).toLocaleString();
    } catch (e) { /* 静默 */ }
  }

  function render(runs) {
    if (!runs.length) {
      const hasFilter = filters.runId.value.trim() || filters.flow.value.trim()
        || filters.status.value || filters.start.value || filters.end.value;
      body.innerHTML = `<tr><td colspan="8" style="text-align:center; padding:40px 0" class="text-3">${hasFilter ? '没有符合条件的运行记录' : '暂无运行记录，去运行一个流程吧'}</td></tr>`;
      return;
    }
    body.innerHTML = runs.map(r => `
      <tr>
        <td class="mono text-3" title="${esc(r.runId)}">${esc((r.runId || '').slice(0, 12))}…</td>
        <td>${esc(r.flowName || r.flowId || '–')}<div class="text-3" style="font-size:11px">${esc(r.inputSummary || '')}</div></td>
        <td>${statusBadge(r.status)}</td>
        <td class="mono">${r.successCount ?? 0}/${(r.nodes && Object.keys(r.nodes).length) || 0}</td>
        <td class="mono">${fmtCost(r.costMillis)}</td>
        <td class="mono">${(r.totalTokens ?? 0).toLocaleString()}</td>
        <td class="text-3 mono" style="font-size:12px">${fmtTime(r.startAt)}</td>
        <td><button class="btn btn-sm" data-run="${esc(r.runId)}">详情</button></td>
      </tr>`).join('');
  }

  /** 紧凑页码：1 … 4 5 6 … 20 */
  function pageNumbers(current, pages) {
    if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1);
    const list = [];
    let prev = 0;
    for (const p of [1, ...[current - 1, current, current + 1].filter(x => x > 1 && x < pages), pages]) {
      if (p - prev > 1) list.push('…');
      list.push(p);
      prev = p;
    }
    return list;
  }

  function renderPager() {
    const pages = Math.ceil(state.total / state.size);
    if (pages <= 1) { pager.style.display = 'none'; pager.innerHTML = ''; return; }
    pager.style.display = 'flex';
    pager.innerHTML =
      `<button data-p="${state.page - 1}" ${state.page <= 1 ? 'disabled' : ''}>‹</button>` +
      pageNumbers(state.page, pages).map(p => p === '…'
        ? '<span class="pager-gap">…</span>'
        : `<button data-p="${p}" class="${p === state.page ? 'active' : ''}">${p}</button>`).join('') +
      `<button data-p="${state.page + 1}" ${state.page >= pages ? 'disabled' : ''}>›</button>`;
  }

  async function load() {
    const params = new URLSearchParams({ page: state.page, size: state.size });
    const kw = filters.runId.value.trim();
    const flow = filters.flow.value.trim();
    if (kw) params.set('runId', kw);
    if (flow) params.set('flow', flow);
    if (filters.status.value) params.set('status', filters.status.value);
    if (filters.start.value) params.set('startDate', filters.start.value);
    if (filters.end.value) params.set('endDate', filters.end.value);
    try {
      const data = await apiGet('/api/runs/page?' + params.toString());
      state.total = data.total;
      render(data.items || []);
      renderPager();
      totalEl.textContent = state.total ? `共 ${state.total} 条记录` : '';
    } catch (e) {
      body.innerHTML = '<tr><td colspan="8" class="text-3" style="text-align:center; padding:40px 0">加载失败</td></tr>';
    }
  }

  /** 条件变化 → 回到第 1 页重新查询 */
  function search() { state.page = 1; load(); }

  // 文本条件：400ms 防抖
  let timer = null;
  for (const el of [filters.runId, filters.flow]) {
    el.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(search, 400);
    });
  }
  // 状态 / 日期：变更即查
  for (const el of [filters.status, filters.start, filters.end]) {
    el.addEventListener('change', search);
  }
  // 重置
  document.getElementById('fReset').addEventListener('click', () => {
    for (const el of Object.values(filters)) el.value = '';
    search();
  });
  // 翻页
  pager.addEventListener('click', (ev) => {
    const btn = ev.target.closest('button[data-p]');
    if (!btn || btn.disabled) return;
    const p = Number(btn.dataset.p);
    if (!p || p === state.page) return;
    state.page = p;
    load();
  });

  // 详情弹层
  window.closeModal = () => document.getElementById('runModal').classList.remove('open');
  document.getElementById('runModal').addEventListener('click', (e) => {
    if (e.target.id === 'runModal') closeModal();
  });

  body.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button[data-run]');
    if (!btn) return;
    const run = await apiGet('/api/runs/' + btn.dataset.run);
    curRun = run;
    document.getElementById('mAskDiagnose').style.display = '';
    document.getElementById('mTitle').textContent = `${run.flowName || run.flowId || '–'} · ${run.runId}`;
    document.getElementById('mBadge').innerHTML = statusBadge(run.status);
    const nodesHtml = Object.values(run.nodes || {}).map(n => `
      <div class="card" style="padding:12px 14px; margin-bottom:10px">
        <div class="flex items-center gap-10 mb-8">
          ${ntBadge(n.nodeType)}<b style="font-size:13px">${esc(n.nodeName || n.nodeId)}</b>
          ${nodeBadge(n.status)}
          <div class="flex flex-1"></div>
          <span class="text-3 mono" style="font-size:11.5px">${esc(n.model || '')} · ${fmtCost(n.costMillis)} · ${n.totalTokens || 0} tok</span>
        </div>
        ${n.error ? `<div class="badge badge-failed mb-8">${esc(n.error)}</div>` : ''}
        ${n.parsedJson ? `<div class="m-sec">结构化输出</div><div class="output-block" style="max-height:200px">${esc(n.parsedJson)}</div>`
          : n.output ? `<div class="m-sec">原始输出</div><div class="output-block" style="max-height:200px">${esc(n.output)}</div>` : ''}
      </div>`).join('');
    document.getElementById('mBody').innerHTML = `
      <div class="text-3 mb-16" style="font-size:12.5px">
        输入摘要：${esc(run.inputSummary || '–')} · 图片 ${run.inputImages || 0} 张 · 表格 ${run.inputRows || 0} 行 · 耗时 ${fmtCost(run.costMillis)}
      </div>
      ${nodesHtml || '<div class="empty">无节点数据</div>'}`;
    document.getElementById('runModal').classList.add('open');
  });

  // 弹层「问助手诊断」：组装运行上下文预填进助手输入（失败运行为主场景）
  document.getElementById('mAskDiagnose').addEventListener('click', () => {
    if (!curRun) return;
    const failed = curRun.status === 'FAILED';
    const failedNodes = Object.values(curRun.nodes || {})
      .filter(n => n.status === 'FAILED' && n.error)
      .map(n => `  - ${n.nodeName || n.nodeId}：${n.error.slice(0, 120)}`);
    const lines = [
      '🩺 请帮我诊断这次流程运行：',
      '- runId：' + curRun.runId,
      '- 流程：' + (curRun.flowName || curRun.flowId || '–'),
      '- 状态：' + (STATUS_LABEL[curRun.status] || curRun.status),
      '- 耗时：' + fmtCost(curRun.costMillis),
    ];
    if (failedNodes.length) lines.push('- 失败节点：', ...failedNodes);
    lines.push('', failed
      ? '运行失败了，请先用 get_run 工具查询 ' + curRun.runId + ' 的失败节点与错误信息，定位原因并给出修复建议。'
      : '请分析这次运行的节点表现（可调用 get_run 查询详情），给出优化建议。');
    if (window.GuonlAssistant) window.GuonlAssistant.open({ text: lines.join('\n') });
  });

  loadStats();
  load();
})();
