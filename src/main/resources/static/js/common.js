/* ============================================================
   common.js —— 全局工具：API封装 / 转义 / 时间 / Toast / 节点元数据
   ============================================================ */

/** 六种节点类型元数据（与后端 NodeType 对齐） */
const NT = {
  TEXT:     { label: '文本理解',  icon: '📝', color: '#3b82f6' },
  IMAGE:    { label: '图片识别',  icon: '🖼️', color: '#10b981' },
  JSON:     { label: 'JSON处理',  icon: '🧩', color: '#f59e0b' },
  EXCEL:    { label: 'Excel处理', icon: '📊', color: '#8b5cf6' },
  COMBINED: { label: '组合处理',  icon: '🧬', color: '#ec4899' },
  ROUTER:   { label: '条件路由',  icon: '🔀', color: '#ef4444' },
  TOOL:     { label: '工具调用',  icon: '🔧', color: '#0ea5e9' },
  KNOWLEDGE:{ label: '知识检索',  icon: '📚', color: '#14b8a6' },
  IMAGE_GEN:{ label: '图片生成',  icon: '🎨', color: '#f97316' },
  MODERATION:{ label: '内容审核', icon: '🛡️', color: '#f43f5e' },
  EVALUATE:{ label: '输出评估', icon: '⚖️', color: '#84cc16' },
  SUBFLOW: { label: '子流程',  icon: '📦', color: '#6366f1' },
  AGGREGATE:{ label: '聚合',  icon: '🧲', color: '#a855f7' },
};

const STATUS_LABEL = { PENDING: '等待', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消' };

/** HTML转义 */
function esc(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** 请求封装：非2xx时抛出 message 并 toast */
async function api(url, options = {}) {
  const res = await fetch(url, options);
  let body = null;
  try { body = await res.json(); } catch (e) { /* 空响应 */ }
  if (!res.ok) {
    const msg = (body && body.message) ? body.message : `请求失败（HTTP ${res.status}）`;
    toast(msg, 'err');
    throw new Error(msg);
  }
  return body;
}

const apiGet = (url) => api(url);
const apiPost = (url, data) => api(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(data || {}),
});
const apiPostForm = (url, formData) => api(url, { method: 'POST', body: formData });
const apiDelete = (url) => api(url, { method: 'DELETE' });

/** Toast 提示 */
function toast(msg, type = '') {
  let wrap = document.getElementById('toastWrap');
  if (!wrap) { wrap = document.createElement('div'); wrap.id = 'toastWrap'; document.body.appendChild(wrap); }
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = msg;
  wrap.appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; }, 2600);
  setTimeout(() => el.remove(), 3000);
}

/** 时间格式化 */
function fmtTime(millis) {
  if (!millis) return '–';
  const d = new Date(millis);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** 耗时格式化：ms → 850ms / 1.2s / 1m05s */
function fmtCost(ms) {
  if (ms === null || ms === undefined) return '–';
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const m = Math.floor(ms / 60000), s = Math.round((ms % 60000) / 1000);
  return `${m}m${String(s).padStart(2, '0')}s`;
}

/** 节点类型徽章 HTML */
function ntBadge(type) {
  const meta = NT[type] || { label: type, icon: '❓', color: '#94a3b8' };
  return `<span class="nt" style="--dot:${meta.color}"><span class="nt-dot"></span>${meta.icon} ${meta.label}</span>`;
}

/** 流程执行状态徽章 HTML */
function statusBadge(status) {
  const map = { RUNNING: 'badge-running', SUCCESS: 'badge-success', FAILED: 'badge-failed' };
  const label = { RUNNING: '● 运行中', SUCCESS: '✓ 成功', FAILED: '✗ 失败' };
  return `<span class="badge ${map[status] || 'badge-pending'}">${label[status] || status}</span>`;
}

/** 节点执行状态徽章 HTML */
function nodeBadge(status) {
  const map = { PENDING: 'badge-pending', RUNNING: 'badge-running', SUCCESS: 'badge-success', FAILED: 'badge-failed', CANCELLED: 'badge-cancelled' };
  return `<span class="badge ${map[status] || 'badge-pending'}">${STATUS_LABEL[status] || status}</span>`;
}

/** 侧栏 provider 状态胶囊 */
(async function initProviderPill() {
  const pill = document.getElementById('providerPill');
  if (!pill) return;
  try {
    const cfg = await apiGet('/api/config');
    if (cfg.provider === 'llm') {
      pill.textContent = 'LLM · ' + (cfg.textModel || 'live');
      pill.classList.add('llm');
    } else {
      pill.textContent = 'MOCK · 演示模式';
    }
  } catch (e) { pill.textContent = 'provider: ?'; }
})();

/** 全局页脚：GitHub 项目 + 版权（固定底栏，整站唯一来源，年份自动取当前年） */
(function initFooter() {
  const el = document.createElement('footer');
  el.className = 'footer';
  el.innerHTML = [
    '<a class="footer-repo" href="https://github.com/guonl/guonl-spring-ai-flow" target="_blank" rel="noopener" title="GitHub 项目地址">',
    '<svg class="footer-ico" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z"/></svg>',
    '<span>guonl</span></a>',
    '<span class="footer-sep">·</span>',
    '<span class="footer-copy">© ' + new Date().getFullYear() + ' guonl. 保留所有权利.</span>'
  ].join('');
  document.body.appendChild(el);
})();

/* ============================================================
   主题切换 & 侧栏折叠（localStorage 持久化）
   ============================================================ */
const THEMES = [
  { id: 'light', name: '晨雾白', dot: 'linear-gradient(135deg,#6366f1,#f8fafc)' },
  { id: 'dark',  name: '星夜蓝', dot: 'linear-gradient(135deg,#6366f1,#0d1220)' },
  { id: 'warm',  name: '暖沙杏', dot: 'linear-gradient(135deg,#d97706,#f6f1e7)' },
];

/** 应用主题：html[data-theme] + localStorage('sf_theme') */
function applyTheme(id) {
  if (!THEMES.some(t => t.id === id)) id = 'light';
  document.documentElement.dataset.theme = id;
  try { localStorage.setItem('sf_theme', id); } catch (e) { /* 忽略 */ }
  document.querySelectorAll('.theme-pop .tp-item').forEach(el => {
    el.classList.toggle('active', el.dataset.theme === id);
  });
}

/** 应用侧栏折叠：body.sidebar-collapsed + localStorage('sf_sidebar_collapsed') */
function applySidebar(collapsed) {
  document.body.classList.toggle('sidebar-collapsed', collapsed);
  try { localStorage.setItem('sf_sidebar_collapsed', collapsed ? '1' : '0'); } catch (e) { /* 忽略 */ }
  const btn = document.getElementById('btnCollapse');
  if (btn) btn.textContent = collapsed ? '›' : '‹';
}

(function initSidebarTools() {
  const themeBtn = document.getElementById('btnTheme');
  const collapseBtn = document.getElementById('btnCollapse');
  if (!themeBtn && !collapseBtn) return;

  // 恢复折叠按钮箭头（防闪烁脚本已加 class，但箭头需同步）
  if (collapseBtn && document.body.classList.contains('sidebar-collapsed')) collapseBtn.textContent = '›';

  // 主题弹层（懒创建，点击按钮弹出，点外部关闭）
  let pop = null;
  function closeThemePop(e) {
    if (pop && e && (pop.contains(e.target) || themeBtn.contains(e.target))) return;
    if (pop) pop.classList.remove('open');
    document.removeEventListener('mousedown', closeThemePop);
  }
  function openThemePop() {
    if (!pop) {
      pop = document.createElement('div');
      pop.className = 'theme-pop';
      pop.innerHTML = THEMES.map(t => `
        <div class="tp-item" data-theme="${t.id}">
          <span class="tp-dot" style="background:${t.dot}"></span>${t.name}<span class="tp-check">✓</span>
        </div>`).join('');
      document.body.appendChild(pop);
      pop.addEventListener('click', (e) => {
        const item = e.target.closest('.tp-item');
        if (!item) return;
        applyTheme(item.dataset.theme);
        pop.classList.remove('open');
        document.removeEventListener('mousedown', closeThemePop);
      });
    }
    pop.classList.add('open');
    const r = themeBtn.getBoundingClientRect();
    pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - pop.offsetWidth - 8)) + 'px';
    pop.style.top = Math.max(8, r.top - pop.offsetHeight - 8) + 'px';
    applyTheme(document.documentElement.dataset.theme || 'light');
    setTimeout(() => document.addEventListener('mousedown', closeThemePop), 0);
  }
  if (themeBtn) {
    themeBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (pop && pop.classList.contains('open')) closeThemePop(); else openThemePop();
    });
  }
  if (collapseBtn) {
    collapseBtn.addEventListener('click', () => {
      applySidebar(!document.body.classList.contains('sidebar-collapsed'));
    });
  }
})();

/* ============================================================
   通用弹窗组件（主题化，替代原生 confirm/alert）
   uiConfirm({ title, message, okText, cancelText, danger }) → Promise<boolean>
   message 支持有限 HTML（调用方需对动态内容自行 esc()）
   ============================================================ */
function uiConfirm({ title = '确认操作', message = '', okText = '确定', cancelText = '取消', danger = false } = {}) {
  return new Promise((resolve) => {
    const mask = document.createElement('div');
    mask.className = 'ui-mask';
    mask.innerHTML = `
      <div class="ui-modal" role="dialog" aria-modal="true">
        <div class="ui-head">
          <span class="ui-icon ${danger ? 'danger' : 'info'}">${danger ? '⚠' : '✦'}</span>
          <span class="ui-title">${esc(title)}</span>
        </div>
        <div class="ui-msg">${message}</div>
        <div class="ui-btns">
          <button type="button" class="btn ui-cancel">${esc(cancelText)}</button>
          <button type="button" class="btn ${danger ? 'btn-danger' : 'btn-primary'} ui-ok">${esc(okText)}</button>
        </div>
      </div>`;
    document.body.appendChild(mask);
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    const done = (val) => {
      document.removeEventListener('keydown', onKey);
      mask.classList.add('closing');
      setTimeout(() => mask.remove(), 150);
      resolve(val);
    };
    mask.querySelector('.ui-ok').addEventListener('click', () => done(true));
    mask.querySelector('.ui-cancel').addEventListener('click', () => done(false));
    mask.addEventListener('mousedown', (e) => { if (e.target === mask) done(false); });
    document.addEventListener('keydown', onKey);
    mask.querySelector('.ui-ok').focus();
  });
}

/* ============================================================
   AI 助手悬浮窗（全站注入，/assistant 独立页除外）
   右下角悬浮按钮 → 点击展开聊天抽屉；资源（css/js）首次打开懒加载
   ============================================================ */
(function initAssistantLauncher() {
  // 独立会话页自身即是助手，不再注入悬浮窗
  if (location.pathname === '/assistant') return;

  // 探测助手开关：未启用则不注入
  let st = null;
  try {
    fetch('/api/assistant/status').then((r) => (r.ok ? r.json() : null)).then((s) => {
      if (s && s.enabled) { st = s; createFab(); }
    }).catch(() => { /* 静默 */ });
  } catch (e) { /* 静默 */ }

  function fabOffset() {
    // 画布页右下有缩放工具条，悬浮窗整体上移避让
    return location.pathname.indexOf('/flow-editor') === 0 ? '136px' : '54px';
  }

  // assistant.css 含 #aiFab 悬浮按钮样式，必须在创建按钮时就注入（不能等首次点开抽屉）
  function loadAssistantCss() {
    if (document.getElementById('assistantCss')) return;
    const link = document.createElement('link');
    link.id = 'assistantCss';
    link.rel = 'stylesheet';
    link.href = '/css/assistant.css';
    document.head.appendChild(link);
  }

  function createFab() {
    if (document.getElementById('aiFab')) return;
    loadAssistantCss();
    document.documentElement.style.setProperty('--fab-offset', fabOffset());
    const fab = document.createElement('button');
    fab.id = 'aiFab';
    fab.type = 'button';
    fab.title = 'AI 助手';
    fab.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">'
      + '<path d="M12 2l2.1 5.4 5.4 2.1-5.4 2.1L12 17l-2.1-5.4L4.5 9.5l5.4-2.1zM19 14l1.2 3.1 3.1 1.2-3.1 1.2L19 22.6l-1.2-3.1-3.1-1.2 3.1-1.2zM5.3 15.4l.9 2.3 2.3.9-2.3.9-.9 2.3-.9-2.3-2.3-.9 2.3-.9z"/></svg>'
      + (st && st.provider === 'mock' ? '<i class="ai-fab-badge" title="演示模式">M</i>' : '');
    fab.addEventListener('click', toggleDrawer);
    document.body.appendChild(fab);
  }

  let drawer = null;
  let chat = null;
  let loadingAssets = false;

  function ensureAssets(cb) {
    if (window.AssistantChat) { cb(); return; }
    if (loadingAssets) { const t = setInterval(() => { if (window.AssistantChat) { clearInterval(t); cb(); } }, 60); return; }
    loadingAssets = true;
    loadAssistantCss();
    const script = document.createElement('script');
    script.src = '/js/assistant-chat.js';
    script.onload = () => { loadingAssets = false; cb(); };
    script.onerror = () => { loadingAssets = false; toast('AI 助手资源加载失败', 'err'); };
    document.body.appendChild(script);
  }

  function toggleDrawer() {
    if (!drawer) {
      drawer = document.createElement('div');
      drawer.id = 'aiDrawer';
      drawer.innerHTML = '<div class="ai-drawer-panel"></div>';
      document.body.appendChild(drawer);
      document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && drawer.classList.contains('open')) toggleDrawer();
      });
    }
    const fab = document.getElementById('aiFab');
    if (drawer.classList.contains('open')) {
      drawer.classList.remove('open');
      if (fab) fab.classList.remove('active');
      return;
    }
    ensureAssets(() => {
      if (!chat) {
        chat = new AssistantChat(drawer.querySelector('.ai-drawer-panel'), {
          mode: 'drawer',
          onClose: toggleDrawer,
        });
      }
      // 直接加类：rAF 在后台/隐藏标签页不会执行，会导致抽屉无法展开
      drawer.classList.add('open');
      if (fab) fab.classList.add('active');
      setTimeout(() => { const ip = drawer.querySelector('.ac-input'); if (ip && !ip.disabled) ip.focus(); }, 220);
    });
  }

  // 供页面（运行诊断等）调用：window.GuonlAssistant.open({ text, newChat })
  // 打开助手抽屉；text 预填输入框（不自动发送），newChat=true 时先开新会话
  window.GuonlAssistant = {
    open(opts = {}) {
      ensureAssets(() => {
        if (!drawer) {
          toggleDrawer(); // 首次调用：资源已就绪，内部 ensureAssets 同步建 chat 并打开
        } else if (!drawer.classList.contains('open')) {
          toggleDrawer();
        }
        if (!chat) return;
        if (opts.newChat) chat.newChat();
        if (opts.text) {
          chat.input.value = opts.text;
          chat.autoGrow();
          setTimeout(() => { if (chat && chat.input) chat.input.focus(); }, 260);
        }
      });
    },
  };
})();
