/* ============================================================
   knowledge.js —— 知识库管理：列表 / 新建 / 文档上传 / 检索调试
   ============================================================ */

let currentKbId = null;

/** 页面初始化 */
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('btnNew').addEventListener('click', openCreateDialog);
  document.getElementById('btnUpload').addEventListener('click', () => document.getElementById('fileInput').click());
  document.getElementById('fileInput').addEventListener('change', onUploadFile);
  document.getElementById('btnSearch').addEventListener('click', doSearch);
  loadList();
});

/** 加载知识库列表 */
async function loadList() {
  const grid = document.getElementById('kbGrid');
  try {
    const list = await apiGet('/api/knowledge');
    if (!list.length) {
      grid.innerHTML = `<div class="text-3" style="text-align:center; padding:60px 0; grid-column:1/-1">
        还没有知识库，点击右上角「+ 新建知识库」开始</div>`;
      return;
    }
    grid.innerHTML = list.map(kb => `
      <div class="kb-card" onclick="openDetail(${kb.id}, '${esc(kb.name).replace(/'/g, '&#39;')}')">
        <button type="button" class="kb-del" title="删除知识库"
                onclick="event.stopPropagation(); deleteKb(${kb.id}, '${esc(kb.name).replace(/'/g, '&#39;')}')">🗑</button>
        <div class="kb-name"><span class="kb-ico">📚</span>${esc(kb.name)}</div>
        <div class="kb-desc">${esc(kb.description) || '<span style="opacity:.5">暂无描述</span>'}</div>
        <div class="kb-stats">
          <span><b>${kb.docCount ?? 0}</b> 文档</span>
          <span><b>${kb.chunkCount ?? 0}</b> 切片</span>
          <span style="margin-left:auto; color:var(--text-3)">${fmtLocalTime(kb.updatedAt)}</span>
        </div>
      </div>`).join('');
  } catch (e) {
    grid.innerHTML = `<div class="text-3" style="text-align:center; padding:60px 0; grid-column:1/-1">加载失败</div>`;
  }
}

/** 时间格式化（ISO字符串 → 本地短格式） */
function fmtLocalTime(iso) {
  if (!iso) return '–';
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 新建知识库弹层（轻量输入表单） */
function openCreateDialog() {
  const mask = document.createElement('div');
  mask.className = 'ui-mask';
  mask.innerHTML = `
    <div class="ui-modal" role="dialog" aria-modal="true">
      <div class="ui-head"><span class="ui-icon info">✦</span><span class="ui-title">新建知识库</span></div>
      <div class="ui-msg" style="text-align:left">
        <div style="margin-bottom:10px">
          <div style="font-size:12px; color:var(--text-2); margin-bottom:4px">名称 *</div>
          <input class="input" id="dlgKbName" placeholder="如：客服知识库" maxlength="64">
        </div>
        <div>
          <div style="font-size:12px; color:var(--text-2); margin-bottom:4px">描述</div>
          <input class="input" id="dlgKbDesc" placeholder="用途说明（可选）" maxlength="200">
        </div>
      </div>
      <div class="ui-btns">
        <button type="button" class="btn ui-cancel">取消</button>
        <button type="button" class="btn btn-primary ui-ok">创建</button>
      </div>
    </div>`;
  document.body.appendChild(mask);
  const close = () => { mask.classList.add('closing'); setTimeout(() => mask.remove(), 150); };
  mask.querySelector('.ui-cancel').addEventListener('click', close);
  mask.addEventListener('mousedown', (e) => { if (e.target === mask) close(); });
  mask.querySelector('.ui-ok').addEventListener('click', async () => {
    const name = mask.querySelector('#dlgKbName').value.trim();
    if (!name) { toast('请输入知识库名称', 'err'); return; }
    const description = mask.querySelector('#dlgKbDesc').value.trim();
    try {
      await apiPost('/api/knowledge', { name, description });
      toast('知识库已创建');
      close();
      loadList();
    } catch (e) { /* toast 已提示 */ }
  });
  mask.querySelector('#dlgKbName').focus();
}

/** 删除知识库 */
async function deleteKb(id, name) {
  const ok = await uiConfirm({
    title: '删除知识库',
    message: `确定删除 <b>${esc(name)}</b> 吗？其全部文档与向量数据将被清除，此操作不可恢复。`,
    okText: '删除', danger: true,
  });
  if (!ok) return;
  try {
    await apiDelete(`/api/knowledge/${id}`);
    toast('知识库已删除');
    if (currentKbId === id) closeModal();
    loadList();
  } catch (e) { /* toast 已提示 */ }
}

/** 打开知识库详情弹层 */
async function openDetail(kbId, kbName) {
  currentKbId = kbId;
  document.getElementById('mKbName').textContent = `📚 ${kbName}`;
  document.getElementById('searchResult').innerHTML = '';
  document.getElementById('searchQuery').value = '';
  document.getElementById('kbModal').classList.add('open');
  await loadDocs();
}

function closeModal() {
  stopProgressPolling(false);
  document.getElementById('kbModal').classList.remove('open');
  currentKbId = null;
}

/** 加载文档列表（返回docs；含PROCESSING时自动启动进度轮询） */
async function loadDocs() {
  const body = document.getElementById('docBody');
  body.innerHTML = `<tr><td colspan="5" class="text-3" style="text-align:center; padding:24px 0">加载中…</td></tr>`;
  try {
    const docs = await apiGet(`/api/knowledge/${currentKbId}/docs`);
    if (!docs.length) {
      body.innerHTML = `<tr><td colspan="5" class="text-3" style="text-align:center; padding:24px 0">暂无文档，点击上方「上传文档」导入</td></tr>`;
      ensureProgressPolling(docs);
      return docs;
    }
    body.innerHTML = docs.map(d => `
      <tr>
        <td style="max-width:320px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap" title="${esc(d.docName)}">📄 ${esc(d.docName)}</td>
        <td>${d.status === 'PROCESSING' ? '–' : (d.chunkCount ?? 0)}</td>
        <td>${docStatusCell(d)}</td>
        <td class="text-3">${fmtLocalTime(d.createdAt)}</td>
        <td><button type="button" class="btn btn-ghost" style="padding:2px 8px; font-size:12px"
                    onclick="deleteDoc(${d.id}, '${esc(d.docName).replace(/'/g, '&#39;')}')">删除</button></td>
      </tr>`).join('');
    ensureProgressPolling(docs);
    return docs;
  } catch (e) {
    body.innerHTML = `<tr><td colspan="5" class="text-3" style="text-align:center; padding:24px 0">加载失败</td></tr>`;
    return [];
  }
}

/** 文档状态单元格：PROCESSING进度条 / FAILED红色徽标 / READY就绪 */
function docStatusCell(d) {
  if (d.status === 'PROCESSING') {
    const total = d.totalChunks ?? 0;
    const done = d.processedChunks ?? 0;
    const pct = total > 0 ? Math.round(done * 100 / total) : 0;
    return `<div class="doc-progress" title="${done}/${total} 切片已向量化">
      <div class="doc-progress-bar"><i style="width:${pct}%"></i></div>
      <span class="doc-progress-txt">${pct}%</span>
    </div>`;
  }
  if (d.status === 'FAILED') {
    return `<span class="badge badge-failed" title="${esc(d.errorMsg || '处理失败，可删除后重新上传')}">失败</span>`;
  }
  return '<span class="badge badge-success">就绪</span>';
}

/** 进度轮询：文档列表存在PROCESSING时每1.5s刷新，全部完成后提示并停止 */
let progressTimer = null;
let hadProcessing = false;

function ensureProgressPolling(docs) {
  const hasProcessing = (docs || []).some(d => d.status === 'PROCESSING');
  if (hasProcessing) {
    if (!progressTimer) {
      progressTimer = setInterval(() => {
        if (currentKbId === null) { stopProgressPolling(false); return; }
        loadDocs();
      }, 1500);
    }
  } else if (progressTimer) {
    stopProgressPolling(true);
  }
  hadProcessing = hasProcessing;
}

function stopProgressPolling(done) {
  if (progressTimer) { clearInterval(progressTimer); progressTimer = null; }
  if (done && hadProcessing) toast('全部文档入库完成');
  hadProcessing = false;
}

/** 上传文档（接口立即返回，向量化后台执行，进度条见文档列表） */
async function onUploadFile(e) {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file || currentKbId === null) return;
  const fd = new FormData();
  fd.append('file', file);
  try {
    const doc = await apiPostForm(`/api/knowledge/${currentKbId}/docs`, fd);
    toast(`已开始处理「${file.name}」（${doc.totalChunks} 个切片），向量化完成后自动就绪`);
    await loadDocs();
  } catch (err) { /* toast 已提示 */ }
}

/** 删除文档 */
async function deleteDoc(docId, docName) {
  const ok = await uiConfirm({
    title: '删除文档',
    message: `确定删除文档 <b>${esc(docName)}</b> 吗？其向量切片将同步移除。`,
    okText: '删除', danger: true,
  });
  if (!ok) return;
  try {
    await apiDelete(`/api/knowledge/${currentKbId}/docs/${docId}`);
    toast('文档已删除');
    loadDocs();
  } catch (e) { /* toast 已提示 */ }
}

/** 检索调试 */
async function doSearch() {
  const query = document.getElementById('searchQuery').value.trim();
  const result = document.getElementById('searchResult');
  if (!query) { toast('请输入查询内容', 'err'); return; }
  if (currentKbId === null) return;
  const topK = Number(document.getElementById('searchTopK').value);
  result.innerHTML = `<div class="text-3" style="text-align:center; padding:16px 0">检索中…</div>`;
  try {
    const chunks = await apiPost(`/api/knowledge/${currentKbId}/search`, { query, topK });
    if (!chunks.length) {
      result.innerHTML = `<div class="text-3" style="text-align:center; padding:16px 0">未命中任何片段</div>`;
      return;
    }
    result.innerHTML = chunks.map((c, i) => `
      <div class="chunk">
        <div class="c-meta">
          <span class="c-score">#${i + 1} 相似度 ${c.score.toFixed(4)}</span>
          <span>📄 ${esc(c.docName)}</span>
        </div>
        <div class="c-text">${esc(c.text)}</div>
      </div>`).join('');
  } catch (e) {
    result.innerHTML = '';
  }
}
