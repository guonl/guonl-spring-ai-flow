/* ============================================================
   assistant-chat.js —— AI 助手前端核心
   AssistantChat 类：SSE 流式对话 / 会话管理 / 轻量 Markdown 渲染
   挂载：new AssistantChat(el, { mode: 'drawer' | 'page', onClose })
   依赖：common.js（esc / apiGet / apiPost / apiDelete / toast / uiConfirm）
   后端协议：POST /api/assistant/chat（text/event-stream）
     data: {"event":"start|delta|tool_trace|done|error","data":{...}}
   ============================================================ */

/** 轻量 Markdown → HTML（先整体转义防 XSS，再行级解析；自包含无外部依赖） */
function acMd(src) {
  if (!src) return '';
  src = String(src);
  // 1. 摘出代码围栏（容忍流式期间的未闭合围栏）
  const fences = [];
  src = src.replace(/```([\w+#-]*)[ \t]*\n?([\s\S]*?)(?:```|$)/g, (m, lang, code) => {
    fences.push({ lang: lang, code: code.replace(/\n$/, '') });
    return '\n\u0000F' + (fences.length - 1) + '\u0000\n';
  });
  // 2. 全文转义（防 XSS 基础）
  src = esc(src);
  // 行内解析：行内代码 / 加粗 / 斜体 / 链接（仅 http/https/相对路径）
  const inline = (s) => s
    .replace(/`([^`]+)`/g, (m, c) => '<code class="ac-code">' + c + '</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (m, t, u) =>
      /^(https?:\/\/|\/)/i.test(u) ? '<a href="' + u + '" target="_blank" rel="noopener">' + t + '</a>' : t);

  const lines = src.split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    // 代码围栏占位还原
    const fm = line.match(/^\s*\u0000F(\d+)\u0000\s*$/);
    if (fm) {
      const f = fences[+fm[1]];
      out.push('<pre class="ac-pre"><code>' + f.code + '</code></pre>');
      i++; continue;
    }
    if (!line.trim()) { i++; continue; }
    // 标题
    const hm = line.match(/^(#{1,4})\s+(.*)$/);
    if (hm) {
      const lv = Math.min(hm[1].length + 2, 6);
      out.push('<h' + lv + ' class="ac-h">' + inline(hm[2]) + '</h' + lv + '>');
      i++; continue;
    }
    // 分隔线
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) { out.push('<hr class="ac-hr">'); i++; continue; }
    // 表格：| a | b | + |---|---|
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      const row = (l) => l.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
      const head = row(line);
      i += 2;
      const rows = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) { rows.push(row(lines[i])); i++; }
      out.push('<div class="ac-tbl"><table><thead><tr>'
        + head.map((h) => '<th>' + inline(h) + '</th>').join('')
        + '</tr></thead><tbody>'
        + rows.map((r) => '<tr>' + r.map((c) => '<td>' + inline(c) + '</td>').join('') + '</tr>').join('')
        + '</tbody></table></div>');
      continue;
    }
    // 引用（esc 后 > 变 &gt;）
    if (/^\s*&gt;\s?/.test(line)) {
      const buf = [];
      while (i < lines.length && /^\s*&gt;\s?/.test(lines[i])) { buf.push(lines[i].replace(/^\s*&gt;\s?/, '')); i++; }
      out.push('<blockquote class="ac-quote">' + inline(buf.join('<br>')) + '</blockquote>');
      continue;
    }
    // 列表（无序 / 有序）
    if (/^\s*[-*]\s+/.test(line) || /^\s*\d+[.、)]\s+/.test(line)) {
      const ordered = /^\s*\d+[.、)]\s+/.test(line);
      const items = [];
      while (i < lines.length) {
        const mm = lines[i].match(/^\s*(?:[-*]|\d+[.、)])\s+(.*)$/);
        if (!mm) break;
        items.push(inline(mm[1])); i++;
      }
      const tag = ordered ? 'ol' : 'ul';
      out.push('<' + tag + ' class="ac-list">' + items.map((t) => '<li>' + t + '</li>').join('') + '</' + tag + '>');
      continue;
    }
    // 普通段落：连续非空非特殊行合并
    const buf = [line]; i++;
    while (i < lines.length && lines[i].trim()
      && !/^\s*(#{1,4}\s|[-*]\s|\d+[.、)]\s|\||&gt;|\u0000F\d+\u0000)/.test(lines[i])) {
      buf.push(lines[i]); i++;
    }
    out.push('<p class="ac-p">' + inline(buf.join('<br>')) + '</p>');
  }
  return out.join('');
}

/** ISO 时间 → MM-dd HH:mm */
function acTime(iso) {
  const d = iso ? new Date(iso) : null;
  if (!d || isNaN(d.getTime())) return '';
  const p = (n) => String(n).padStart(2, '0');
  return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

/** djb2 32位内容指纹（与后端 AssistantFeedbackService.hash 同算法 h*33+c，8位hex） */
function acHash(s) {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = (((h << 5) + h) + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(16);
}

/** 图片读取 + 压缩：长边≤1568px 转 JPEG（质量0.9）；小图（PNG<500KB）原样直传 */
function acCompressImage(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('读取失败'));
    reader.onload = () => {
      const src = String(reader.result);
      if (/^image\/png$/.test(file.type) && file.size < 500 * 1024) { resolve(src); return; }
      const img = new Image();
      img.onerror = () => reject(new Error('解析失败'));
      img.onload = () => {
        const LONG = 1568;
        const scale = Math.min(1, LONG / Math.max(img.width, img.height, 1));
        if (scale >= 1 && file.size < 500 * 1024) { resolve(src); return; }
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL('image/jpeg', 0.9));
      };
      img.src = src;
    };
    reader.readAsDataURL(file);
  });
}

class AssistantChat {
  constructor(root, opts = {}) {
    this.root = typeof root === 'string' ? document.querySelector(root) : root;
    this.mode = opts.mode || 'drawer';
    this.onClose = opts.onClose || null;
    this.conv = null;
    this.sessions = [];
    this.status = null;
    this.busy = false;
    this.ctrl = null;          // AbortController（停止生成）
    this.lastUser = '';        // 最后一条用户消息（失败重试用）
    this.turns = [];           // 会话内容 [{role:'user'|'assistant', content, images?}]（导出用）
    this.images = [];          // 待发送图片 dataURL 列表（多模态，最多3张）
    this.fbMap = {};           // 会话消息反馈指纹表 {contentHash: rating}
    this.renderQueued = false;
    if (!this.root) return;
    this.build();
    this.refreshStatus();
    this.restore();
  }

  /* ---------- DOM 构建 ---------- */
  build() {
    this.root.classList.add('ac-root', 'ac-' + this.mode);
    this.root.innerHTML = [
      '<aside class="ac-side">',
      '  <div class="ac-side-head"><span>会话历史</span><button type="button" class="ac-icon-btn ac-new-side" title="新会话">＋</button></div>',
      '  <div class="ac-side-list"></div>',
      '</aside>',
      '<section class="ac-chat">',
      '  <div class="ac-head">',
      '    <button type="button" class="ac-icon-btn ac-side-toggle" title="会话历史">≡</button>',
      '    <div class="ac-title">AI 助手 <span class="ac-badge" style="display:none"></span></div>',
      '    <div class="ac-head-actions">',
      '      <button type="button" class="ac-icon-btn ac-new-top" title="新会话">＋</button>',
      '      <button type="button" class="ac-icon-btn ac-export" title="导出会话 Markdown">⇩</button>',
      (this.mode === 'drawer' ? '      <a class="ac-icon-btn ac-gopage" href="/assistant" title="打开独立会话页">↗</a>' : ''),
      (this.mode === 'drawer' ? '      <button type="button" class="ac-icon-btn ac-close" title="收起">×</button>' : ''),
      '    </div>',
      '  </div>',
      '  <div class="ac-msgs"></div>',
      '  <form class="ac-inputbar">',
      '    <div class="ac-imgs" style="display:none"></div>',
      '    <button type="button" class="ac-icon-btn ac-imgbtn" title="添加图片（也可直接粘贴）" style="display:none">🖼️</button>',
      '    <textarea class="ac-input" rows="1" placeholder="问点什么… Enter 发送 / Shift+Enter 换行"></textarea>',
      '    <button type="submit" class="ac-send" title="发送">➤</button>',
      '  </form>',
      '  <div class="ac-foot-hint">内容由 AI 生成，请注意甄别' + (this.mode === 'drawer' ? ' · <a href="/assistant">打开独立会话页</a>' : '') + '</div>',
      '</section>',
    ].join('\n');

    this.sideList = this.root.querySelector('.ac-side-list');
    this.msgs = this.root.querySelector('.ac-msgs');
    this.input = this.root.querySelector('.ac-input');
    this.sendBtn = this.root.querySelector('.ac-send');
    this.form = this.root.querySelector('.ac-inputbar');
    this.badge = this.root.querySelector('.ac-badge');

    // 事件绑定
    this.form.addEventListener('submit', (e) => {
      e.preventDefault();
      if (this.busy) { this.stop(); return; }
      const v = this.input.value;
      this.input.value = '';
      this.autoGrow();
      this.send(v);
    });
    this.input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        this.form.dispatchEvent(new Event('submit', { cancelable: true }));
      }
    });
    this.input.addEventListener('input', () => this.autoGrow());
    const onNew = () => this.newChat();
    this.root.querySelector('.ac-new-side').addEventListener('click', onNew);
    this.root.querySelector('.ac-new-top').addEventListener('click', onNew);
    this.root.querySelector('.ac-export').addEventListener('click', () => this.exportMd());
    this.imgBtn = this.root.querySelector('.ac-imgbtn');
    this.imgsBox = this.root.querySelector('.ac-imgs');
    this.imgBtn.addEventListener('click', () => this.pickImages());
    // 粘贴截图直达
    this.input.addEventListener('paste', (e) => {
      const files = [...((e.clipboardData && e.clipboardData.files) || [])].filter((f) => f.type && f.type.indexOf('image/') === 0);
      if (files.length) {
        e.preventDefault();
        files.forEach((f) => this.addImageFile(f));
      }
    });
    const closeBtn = this.root.querySelector('.ac-close');
    if (closeBtn) closeBtn.addEventListener('click', () => this.onClose && this.onClose());
    const sideToggle = this.root.querySelector('.ac-side-toggle');
    if (sideToggle) sideToggle.addEventListener('click', () => this.root.classList.toggle('side-open'));

    // 会话列表点击（委托）
    this.sideList.addEventListener('click', (e) => {
      const item = e.target.closest('.ac-item');
      if (!item) return;
      const conv = item.dataset.conv;
      const act = e.target.closest('[data-act]');
      if (!act) { this.select(conv); return; }
      if (act.dataset.act === 'del') this.remove(conv);
      if (act.dataset.act === 'rename') this.rename(conv);
    });
  }

  autoGrow() {
    const el = this.input;
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
  }

  /* ---------- 助手状态 ---------- */
  async refreshStatus() {
    try {
      this.status = await apiGet('/api/assistant/status');
      if (!this.status.enabled) {
        this.banner('AI 助手未启用（flow.assistant.enabled=false），如需使用请联系管理员开启。');
        this.input.disabled = true;
        this.input.placeholder = '助手未启用';
        return;
      }
      if (this.status.provider === 'mock') {
        this.badge.textContent = 'MOCK';
        this.badge.classList.add('mock');
        // mock 不支持多模态，隐藏图片入口
        if (this.imgBtn) this.imgBtn.style.display = 'none';
      } else {
        this.badge.textContent = this.status.model || 'LLM';
        if (this.imgBtn) this.imgBtn.style.display = '';
      }
      this.badge.style.display = '';
    } catch (e) {
      this.banner('助手服务不可用：' + e.message);
      this.input.disabled = true;
    }
  }

  banner(text) {
    const el = document.createElement('div');
    el.className = 'ac-banner';
    el.textContent = text;
    this.msgs.appendChild(el);
  }

  /* ---------- 会话管理 ---------- */
  async restore() {
    await this.loadSessions();
    let saved = null;
    try { saved = localStorage.getItem('sf_assistant_conv'); } catch (e) { /* 忽略 */ }
    if (saved && this.sessions.some((s) => s.conversationId === saved)) {
      await this.select(saved);
    } else {
      this.newChat();
    }
  }

  async loadSessions() {
    try {
      this.sessions = await apiGet('/api/assistant/sessions');
    } catch (e) {
      this.sessions = [];
    }
    this.renderSessions();
  }

  renderSessions() {
    this.sideList.innerHTML = '';
    if (!this.sessions.length) {
      this.sideList.innerHTML = '<div class="ac-side-empty">暂无会话<br>发送第一条消息开始</div>';
      return;
    }
    this.sessions.forEach((s) => {
      const it = document.createElement('div');
      it.className = 'ac-item' + (s.conversationId === this.conv ? ' active' : '');
      it.dataset.conv = s.conversationId;
      it.innerHTML = [
        '<div class="ac-item-main">',
        '  <div class="ac-item-title">' + esc(s.title || '新会话') + '</div>',
        '  <div class="ac-item-meta">' + (s.messageCount || 0) + ' 条 · ' + acTime(s.updatedAt) + '</div>',
        '</div>',
        '<div class="ac-item-acts">',
        '  <button type="button" class="ac-mini" data-act="rename" title="重命名">✎</button>',
        '  <button type="button" class="ac-mini" data-act="del" title="删除">✕</button>',
        '</div>',
      ].join('');
      this.sideList.appendChild(it);
    });
  }

  newChat() {
    this.conv = null;
    try { localStorage.removeItem('sf_assistant_conv'); } catch (e) { /* 忽略 */ }
    this.msgs.innerHTML = '';
    this.turns = [];
    this.fbMap = {};
    this.welcome();
    this.renderSessions();
    this.input.focus();
  }

  async select(conv) {
    this.conv = conv;
    try { localStorage.setItem('sf_assistant_conv', conv); } catch (e) { /* 忽略 */ }
    this.msgs.innerHTML = '';
    try {
      const list = await apiGet('/api/assistant/sessions/' + encodeURIComponent(conv) + '/messages');
      this.turns = list.map((m) => ({ role: m.role, content: m.content }));
      if (!list.length) { this.welcome(); }
      list.forEach((m) => {
        if (m.role === 'user') this.userMsg(m.content);
        else this.historyMsg(m.content);
      });
      this.loadFeedback();
    } catch (e) {
      this.turns = [];
      this.welcome();
    }
    this.renderSessions();
    this.scroll();
  }

  /** 拉取会话反馈并按指纹高亮已渲染消息 */
  async loadFeedback() {
    this.fbMap = {};
    try {
      const list = await apiGet('/api/assistant/sessions/' + encodeURIComponent(this.conv) + '/feedback');
      (list || []).forEach((f) => { this.fbMap[f.contentHash] = f.rating; });
    } catch (e) { /* 静默 */ }
    this.msgs.querySelectorAll('.ac-fb-wrap').forEach((wrap) => {
      const r = this.fbMap[wrap.dataset.hash] || '';
      wrap.dataset.rating = r;
      wrap.querySelectorAll('.ac-fb').forEach((b) => b.classList.toggle('on', b.dataset.r === r));
    });
  }

  async rename(conv) {
    const s = this.sessions.find((x) => x.conversationId === conv);
    const title = prompt('重命名会话：', s ? s.title : '');
    if (title === null) return;
    const t = title.trim();
    if (!t) return;
    try {
      await apiPost('/api/assistant/sessions/' + encodeURIComponent(conv) + '/rename?title=' + encodeURIComponent(t));
      await this.loadSessions();
      this.renderSessions();
    } catch (e) { /* api 已 toast */ }
  }

  async remove(conv) {
    const ok = await uiConfirm({
      title: '删除会话',
      message: '删除后该会话的元数据与全部消息记忆将被清除，且不可恢复。',
      okText: '删除',
      danger: true,
    });
    if (!ok) return;
    try {
      await apiDelete('/api/assistant/sessions/' + encodeURIComponent(conv));
      toast('会话已删除');
      if (this.conv === conv) this.newChat();
      await this.loadSessions();
      this.renderSessions();
    } catch (e) { /* api 已 toast */ }
  }

  /* ---------- 发送与 SSE ---------- */
  send(text, opts = {}) {
    if (this.busy) return;
    text = String(text || '').trim();
    if (!text) return;
    // 重新生成只对「当前最后一条助手回复」有效：发送即移除旧按钮，防误点历史消息
    this.root.querySelectorAll('.ac-regen').forEach((b) => b.remove());
    this.lastUser = text;
    const imgs = this.images.slice();
    this.images = [];
    this.renderImgs();
    if (opts.regenerate) {
      // 重新生成：导出内容弹出上一条助手回复（记忆由后端截断），用户消息保留
      while (this.turns.length && this.turns[this.turns.length - 1].role === 'assistant') this.turns.pop();
    } else {
      this.turns.push({ role: 'user', content: text, images: imgs.length ? imgs : undefined });
    }
    if (!opts.replay) this.userMsg(text, imgs);
    this.busy = true;
    this.sendBtn.classList.add('stop');
    this.sendBtn.textContent = '■';
    this.sendBtn.title = '停止生成';

    const slot = this.assistantMsg();
    let failed = false;
    const paint = () => slot.setHtml(acMd(slot.raw));
    this.stream(text, imgs, !!opts.regenerate, {
      onStart: (conv) => {
        const changed = this.conv !== conv;
        this.conv = conv;
        try { localStorage.setItem('sf_assistant_conv', conv); } catch (e) { /* 忽略 */ }
        if (changed && !this.sessions.some((s) => s.conversationId === conv)) this.loadSessions();
        slot.begin();
      },
      onDelta: (t) => { slot.raw += t; this.queueRender(paint); },
      onTools: (list) => slot.addTools(list),
      onError: (msg) => {
        failed = true;
        slot.fail(msg, () => { slot.drop(); this.send(this.lastUser, { replay: true }); });
      },
      onDone: (d) => { slot.finish(d); paint(); },
    }).then(() => {
      if (!slot.finished && !failed) slot.finish({});
      if (!failed && slot.raw) this.turns.push({ role: 'assistant', content: slot.raw });
      this.afterTurn();
    }).catch((e) => {
      if (e && e.name === 'AbortError') {
        slot.stopped();
        if (slot.raw) this.turns.push({ role: 'assistant', content: slot.raw });
      } else if (!failed) {
        slot.fail(e.message, () => { slot.drop(); this.send(this.lastUser, { replay: true }); });
      }
      this.afterTurn();
    });
  }

  /** fetch + ReadableStream 解析 SSE（regenerate：后端截断最后一轮记忆后重新应答） */
  async stream(message, images, regenerate, handlers) {
    this.ctrl = new AbortController();
    let resp;
    try {
      resp = await fetch('/api/assistant/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          conversationId: this.conv,
          message: message,
          regenerate: !!regenerate,
          images: images && images.length ? images : undefined,
        }),
        signal: this.ctrl.signal,
      });
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      throw new Error('网络异常：' + e.message);
    }
    if (!resp.ok) {
      let msg = '请求失败（HTTP ' + resp.status + '）';
      try {
        const b = await resp.json();
        if (b && b.message) msg = b.message;
      } catch (e) { /* 非 JSON 响应体 */ }
      throw new Error(msg);
    }
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const r = await reader.read();
      if (r.done) break;
      buf += decoder.decode(r.value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        this.dispatchEvent(raw, handlers);
      }
    }
  }

  /** 解析单个 SSE 事件块（忽略心跳注释行，data 为一行 JSON） */
  dispatchEvent(raw, handlers) {
    const dataLines = [];
    raw.split('\n').forEach((line) => {
      if (!line || line.charAt(0) === ':') return; // 心跳注释 / 空行
      if (line.indexOf('data:') === 0) dataLines.push(line.slice(5).replace(/^\s/, ''));
    });
    if (!dataLines.length) return;
    let payload;
    try { payload = JSON.parse(dataLines.join('\n')); } catch (e) { return; }
    const ev = payload.event, data = payload.data;
    if (ev === 'start' && handlers.onStart) handlers.onStart(data && data.conversationId);
    else if (ev === 'delta' && handlers.onDelta) handlers.onDelta((data && data.text) || '');
    else if (ev === 'tool_trace' && handlers.onTools) handlers.onTools(data || []);
    else if (ev === 'error' && handlers.onError) handlers.onError((data && data.message) || '未知错误');
    else if (ev === 'done' && handlers.onDone) handlers.onDone(data || {});
  }

  stop() {
    if (this.ctrl) this.ctrl.abort();
  }

  afterTurn() {
    this.busy = false;
    this.ctrl = null;
    this.sendBtn.classList.remove('stop');
    this.sendBtn.textContent = '➤';
    this.sendBtn.title = '发送';
    this.loadSessions();
    this.scroll();
  }

  queueRender(fn) {
    if (this.renderQueued) return;
    this.renderQueued = true;
    requestAnimationFrame(() => { this.renderQueued = false; fn(); });
  }

  /* ---------- 消息渲染 ---------- */
  scroll() {
    this.msgs.scrollTop = this.msgs.scrollHeight;
  }

  welcome() {
    const chips = [
      { q: '这个平台能做什么？', t: '🌱 平台介绍' },
      { q: '看看最近的运行情况', t: '📈 最近运行' },
      { q: '帮我查一下流程列表', t: '📚 流程列表' },
      { q: '如何创建一个流程？', t: '🧭 建流程引导' },
      { q: '条件路由怎么配置？', t: '🔀 条件路由' },
    ];
    const el = document.createElement('div');
    el.className = 'ac-welcome';
    el.innerHTML = [
      '<div class="ac-welcome-logo">✦</div>',
      '<div class="ac-welcome-title">你好，我是小流</div>',
      '<div class="ac-welcome-sub">业务 × 大模型编排引擎的专属助手，可以答疑、协助编排流程、诊断运行问题、优化提示词。</div>',
      '<div class="ac-chips">' + chips.map((c) =>
        '<button type="button" class="ac-chip" data-q="' + esc(c.q) + '">' + esc(c.t) + '</button>').join('') + '</div>',
    ].join('');
    el.querySelectorAll('.ac-chip').forEach((btn) => {
      btn.addEventListener('click', () => this.send(btn.dataset.q));
    });
    this.msgs.appendChild(el);
  }

  userMsg(text, images = []) {
    const el = document.createElement('div');
    el.className = 'ac-msg user';
    const imgsHtml = images && images.length
      ? '<div class="ac-thumbs">' + images.map((d) => '<img class="ac-thumb" src="' + d + '" alt="图片">').join('') + '</div>'
      : '';
    el.innerHTML = [
      '<div class="ac-avatar">🧑</div>',
      '<div class="ac-col"><div class="ac-bubble">' + imgsHtml + esc(text).replace(/\n/g, '<br>') + '</div></div>',
    ].join('');
    this.msgs.appendChild(el);
    this.scroll();
    return el;
  }

  /** 历史消息中的助手回复（非流式，一次性渲染，带反馈按钮） */
  historyMsg(text) {
    const el = document.createElement('div');
    el.className = 'ac-msg assistant';
    el.innerHTML = [
      '<div class="ac-avatar">✦</div>',
      '<div class="ac-col"><div class="ac-bubble md">' + acMd(text) + '</div>',
      '<div class="ac-meta"><span>历史消息</span></div></div>',
    ].join('');
    this.addFeedbackUI(el.querySelector('.ac-meta'), text);
    this.msgs.appendChild(el);
    return el;
  }

  /** meta 区追加 👍/👎 反馈按钮（按 会话+内容指纹 记录，重复点击取消） */
  addFeedbackUI(meta, content) {
    const hash = acHash(content);
    const wrap = document.createElement('span');
    wrap.className = 'ac-fb-wrap';
    wrap.dataset.hash = hash;
    const cur = this.fbMap[hash] || '';
    wrap.dataset.rating = cur;
    wrap.innerHTML = [
      '<button type="button" class="ac-mini ac-fb' + (cur === 'up' ? ' on' : '') + '" data-r="up" title="有帮助">👍</button>',
      '<button type="button" class="ac-mini ac-fb' + (cur === 'down' ? ' on' : '') + '" data-r="down" title="没帮助">👎</button>',
    ].join('');
    wrap.querySelectorAll('.ac-fb').forEach((btn) => {
      btn.addEventListener('click', () => this.rate(content, btn.dataset.r, wrap));
    });
    meta.appendChild(wrap);
  }

  /** 评分/取消（再点同一下取消） */
  async rate(content, rating, wrap) {
    const next = wrap.dataset.rating === rating ? '' : rating;
    try {
      await apiPost('/api/assistant/feedback', {
        conversationId: this.conv,
        content: content,
        rating: next || null,
      });
      wrap.dataset.rating = next;
      this.fbMap[acHash(content)] = next;
      wrap.querySelectorAll('.ac-fb').forEach((b) => b.classList.toggle('on', b.dataset.r === next));
      toast(next === 'up' ? '已标记「有帮助」' : next === 'down' ? '已标记「没帮助」' : '已取消反馈');
    } catch (e) { /* api 已 toast */ }
  }

  /** 导出当前会话为 Markdown 文件下载 */
  exportMd() {
    if (!this.turns.length) { toast('暂无可导出的会话内容', 'err'); return; }
    const rounds = this.turns.filter((t) => t.role === 'user').length;
    const lines = [
      '# AI 助手会话导出',
      '',
      '- 导出时间：' + new Date().toLocaleString(),
      '- 会话ID：' + (this.conv || '（未发送消息）'),
      '- 对话轮数：' + rounds,
      '',
      '---',
      '',
    ];
    this.turns.forEach((t) => {
      if (t.role === 'user') {
        lines.push('## 🧑 用户', '');
        if (t.images && t.images.length) lines.push('> （附带图片 ' + t.images.length + ' 张）', '');
        lines.push(t.content, '');
      } else {
        lines.push('## ✦ 助手', '', t.content || '（无回复）', '');
      }
    });
    const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'ai-assistant-' + new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-') + '.md';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  /* ---------- 多模态贴图 ---------- */
  pickImages() {
    if (this.images.length >= 3) { toast('最多附带 3 张图片', 'err'); return; }
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = 'image/*';
    inp.multiple = true;
    inp.onchange = () => [...inp.files].forEach((f) => this.addImageFile(f));
    inp.click();
  }

  addImageFile(file) {
    if (this.images.length >= 3) { toast('最多附带 3 张图片', 'err'); return; }
    if (!file.type || file.type.indexOf('image/') !== 0) { toast('仅支持图片文件', 'err'); return; }
    acCompressImage(file).then((dataUrl) => {
      this.images.push(dataUrl);
      this.renderImgs();
    }).catch((e) => toast('图片处理失败：' + e.message, 'err'));
  }

  renderImgs() {
    if (!this.imgsBox) return;
    this.imgsBox.style.display = this.images.length ? 'flex' : 'none';
    this.imgsBox.innerHTML = this.images.map((d, i) =>
      '<div class="ac-img-item"><img src="' + d + '" alt="">'
      + '<button type="button" class="ac-img-del" data-i="' + i + '" title="移除">✕</button></div>').join('');
    this.imgsBox.querySelectorAll('.ac-img-del').forEach((btn) => {
      btn.addEventListener('click', () => {
        this.images.splice(Number(btn.dataset.i), 1);
        this.renderImgs();
      });
    });
  }

  /** 流式助手消息槽位：begin/addTools/fail/stopped/finish/drop */
  assistantMsg() {
    const self = this;
    const el = document.createElement('div');
    el.className = 'ac-msg assistant';
    el.innerHTML = [
      '<div class="ac-avatar">✦</div>',
      '<div class="ac-col">',
      '  <div class="ac-bubble md"><span class="ac-loading"><i></i><i></i><i></i></span></div>',
      '  <div class="ac-meta"></div>',
      '</div>',
    ].join('');
    this.msgs.appendChild(el);
    this.scroll();
    const bubble = el.querySelector('.ac-bubble');
    const meta = el.querySelector('.ac-meta');
    const slot = {
      el: el, bubble: bubble, meta: meta, raw: '', finished: false, toolsBox: null,
      begin() {
        const loading = bubble.querySelector('.ac-loading');
        if (loading && !this.raw) loading.remove();
      },
      setHtml(html) {
        if (this.finished) return;
        bubble.innerHTML = html || '&nbsp;';
        el.closest('.ac-msgs').scrollTop = el.closest('.ac-msgs').scrollHeight;
      },
      addTools(list) {
        if (!list || !list.length) return;
        if (!this.toolsBox) {
          this.toolsBox = document.createElement('details');
          this.toolsBox.className = 'ac-tools';
          this.toolsBox.innerHTML = '<summary>🔧 工具调用</summary><div class="ac-tools-body"></div>';
          meta.parentNode.insertBefore(this.toolsBox, meta);
        }
        this.toolsBox.querySelector('summary').textContent = '🔧 工具调用 ' + list.length + ' 次';
        const body = this.toolsBox.querySelector('.ac-tools-body');
        body.innerHTML = list.map((t) => [
          '<div class="ac-tool"><b>' + esc(t.name || 'tool') + '</b>',
          (t.arguments ? '<pre>' + esc(String(t.arguments).slice(0, 500)) + '</pre>' : ''),
          (t.result ? '<pre>' + esc(String(t.result).slice(0, 500)) + '</pre>' : ''),
          '</div>',
        ].join('')).join('');
      },
      fail(msg, retry) {
        if (this.finished) return;
        this.finished = true;
        bubble.classList.add('ac-error');
        bubble.innerHTML = '⚠ ' + esc(msg || '出错了');
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'ac-retry';
        btn.textContent = '重试';
        btn.addEventListener('click', retry);
        bubble.appendChild(btn);
        el.closest('.ac-msgs').scrollTop = el.closest('.ac-msgs').scrollHeight;
      },
      stopped() {
        if (this.finished) return;
        this.finished = true;
        if (!this.raw) { this.drop(); return; }
        bubble.innerHTML = acMd(this.raw);
        const s = document.createElement('span');
        s.textContent = '（已停止生成）';
        meta.appendChild(s);
      },
      finish(d) {
        if (this.finished) return;
        this.finished = true;
        bubble.innerHTML = acMd(this.raw) || '<span class="ac-p" style="color:var(--text-3)">（空回复）</span>';
        const model = (d && d.model) || '';
        meta.innerHTML = '<span>' + esc(model) + '</span><span>' + acTime(new Date().toISOString()) + '</span>';
        const copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'ac-mini';
        copy.textContent = '复制';
        copy.title = '复制全文';
        copy.addEventListener('click', () => {
          const done = () => { copy.textContent = '已复制'; setTimeout(() => { copy.textContent = '复制'; }, 1500); };
          if (navigator.clipboard) navigator.clipboard.writeText(this.raw).then(done, done);
          else done();
        });
        meta.appendChild(copy);
        // 重新生成：丢弃这条回复，按同一问题重发（后端截断记忆最后一轮；send 开始时移除旧按钮，保证只对最后一条有效）
        const regen = document.createElement('button');
        regen.type = 'button';
        regen.className = 'ac-mini ac-regen';
        regen.textContent = '⟳ 重新生成';
        regen.title = '丢弃这条回复并重新生成';
        regen.addEventListener('click', () => {
          const q = self.lastUser;
          slot.drop();
          self.send(q, { regenerate: true, replay: true });
        });
        meta.appendChild(regen);
        // 👍/👎 反馈
        self.addFeedbackUI(meta, this.raw || '');
        el.closest('.ac-msgs').scrollTop = el.closest('.ac-msgs').scrollHeight;
      },
      drop() { el.remove(); },
    };
    return slot;
  }
}

window.AssistantChat = AssistantChat;
