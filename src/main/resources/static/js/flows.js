/* ============================================================
   flows.js —— 流程库卡片列表（搜索/分页/编辑/运行/复制/删除）
   ============================================================ */
(function () {
  const grid = document.getElementById('flowGrid');
  const empty = document.getElementById('flowEmpty');
  const searchInput = document.getElementById('flowSearch');
  const totalEl = document.getElementById('flowTotal');
  const pager = document.getElementById('flowPager');

  const state = { keyword: '', page: 1, size: 12, total: 0 };

  function render(flows) {
    // 保留第一个「新建」卡片
    const create = grid.firstElementChild;
    grid.querySelectorAll('.flow-card[data-id]').forEach(el => el.remove());
    empty.style.display = flows.length ? 'none' : 'block';
    if (!flows.length) {
      empty.firstElementChild.textContent = state.keyword ? '🔍' : '🍃';
      empty.lastElementChild.textContent = state.keyword
        ? `没有匹配「${state.keyword}」的流程`
        : '暂无流程，点击上方「新建空白流程」或从工作台模板复制开始';
    }
    create.insertAdjacentHTML('afterend', flows.map(f => `
      <div class="flow-card" data-id="${esc(f.id)}">
        <h4>${esc(f.name)}</h4>
        <div class="desc">${esc(f.description || '（暂无描述）')}</div>
        <div class="nodes">${(f.nodes || []).map(n => ntBadge(n.type)).join('')}</div>
        <div class="ops">
          <a class="btn btn-primary btn-sm" href="/run/${esc(f.id)}">▶ 运行</a>
          <a class="btn btn-sm" href="/flow-editor?id=${esc(f.id)}">编辑</a>
          <button class="btn btn-sm" data-act="dup">复制</button>
          <button class="btn btn-sm btn-danger" data-act="del">删除</button>
        </div>
      </div>`).join(''));
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
    if (state.keyword) params.set('keyword', state.keyword);
    grid.querySelectorAll('.flow-card[data-id]').forEach(el => el.remove());
    try {
      const data = await apiGet('/api/flows/page?' + params.toString());
      state.total = data.total;
      render(data.items || []);
      renderPager();
      totalEl.textContent = state.total ? `共 ${state.total} 个流程` : '';
    } catch (e) { toast('流程加载失败', 'err'); }
  }

  // 搜索防抖
  let searchTimer = null;
  searchInput.addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      const kw = searchInput.value.trim();
      if (kw === state.keyword) return;
      state.keyword = kw;
      state.page = 1;
      load();
    }, 300);
  });

  // 翻页
  pager.addEventListener('click', (ev) => {
    const btn = ev.target.closest('button[data-p]');
    if (!btn || btn.disabled) return;
    const p = Number(btn.dataset.p);
    if (!p || p === state.page) return;
    state.page = p;
    load();
    window.scrollTo({ top: 0 });
  });

  grid.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button[data-act]');
    if (!btn) return;
    const id = btn.closest('.flow-card').dataset.id;
    if (btn.dataset.act === 'dup') {
      const copy = await apiPost(`/api/flows/${id}/duplicate`);
      toast('已复制为：' + copy.name, 'ok');
      load();
    } else if (btn.dataset.act === 'del') {
      const ok = await uiConfirm({
        title: '删除流程',
        message: '确定删除该流程？此操作不可恢复。',
        okText: '删除',
        danger: true,
      });
      if (!ok) return;
      await apiDelete(`/api/flows/${id}`);
      toast('已删除', 'ok');
      load();
    }
  });

  load();
})();
