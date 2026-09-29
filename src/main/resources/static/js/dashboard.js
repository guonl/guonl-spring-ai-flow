/* ============================================================
   dashboard.js —— 统计卡片 + 内置模板卡片
   ============================================================ */
(async function () {
  // 运行统计
  try {
    const s = await apiGet('/api/runs/stats');
    document.getElementById('stTotal').textContent = s.total ?? 0;
    document.getElementById('stRate').textContent = (s.successRate ?? 0) + '%';
    document.getElementById('stCost').textContent = fmtCost(s.avgCostMillis || 0);
    document.getElementById('stTokens').textContent = (s.totalTokens ?? 0).toLocaleString();
    document.getElementById('stCalls').textContent = (s.llmCalls ?? 0).toLocaleString();
  } catch (e) { /* 静默 */ }

  // 内置模板：取最近三个流程展示
  const grid = document.getElementById('tplGrid');
  try {
    const flows = (await apiGet('/api/flows')).slice(0, 3);
    if (!flows.length) {
      grid.innerHTML = '<div class="empty" style="grid-column:1/-1"><div class="big">📭</div>还没有流程，去「新建流程」创建第一个编排吧</div>';
      return;
    }
    grid.innerHTML = flows.map(f => `
      <div class="tpl-card">
        <h4>${esc(f.name)}</h4>
        <p>${esc(f.description || '（暂无描述）')}</p>
        <div class="tpl-meta">${(f.nodes || []).map(n => ntBadge(n.type)).join('')}</div>
        <div class="flex gap-8 mt-8">
          <a class="btn btn-primary btn-sm" href="/run/${esc(f.id)}">▶ 立即运行</a>
          <a class="btn btn-sm" href="/flow-editor?id=${esc(f.id)}">编辑</a>
        </div>
      </div>`).join('');
  } catch (e) {
    grid.innerHTML = '<div class="empty" style="grid-column:1/-1">模板加载失败</div>';
  }
})();
