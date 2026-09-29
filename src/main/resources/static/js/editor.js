/* ============================================================
   editor.js —— 可视化编排画布
   拖拽建节点 / 节点移动 / 端口连线 / 画布平移缩放 / 属性面板 / 变量chips / 保存校验运行
   ============================================================ */
(function () {
  const NODE_W = 208;
  const canvas = document.getElementById('canvas');
  const wires = document.getElementById('wires');
  const wrap = document.getElementById('canvasWrap');
  const props = document.getElementById('propsPanel');

  const state = {
    flow: { id: null, name: '', description: '', nodes: [], edges: [] },
    selected: null,     // 选中的节点id
    selectedEdge: null, // 选中的连线（"from>to"）
    connect: null,      // 连线中 {fromId, tempPath}
    dirty: false,
  };

  // ---------- 画布视图：平移 + 缩放 ----------
  const view = { x: 0, y: 0, scale: 1 };   // transform: translate(x,y) scale(s)
  const SCALE_MIN = 0.25, SCALE_MAX = 2.5;

  function applyView() {
    canvas.style.transform = `translate(${view.x}px, ${view.y}px) scale(${view.scale})`;
    const zv = document.getElementById('zoomVal');
    if (zv) zv.textContent = Math.round(view.scale * 100) + '%';
  }

  /** 以 wrap 内坐标 (cx,cy) 为锚点缩放到 ns，保持锚点下的画布点不动 */
  function zoomAt(ns, cx, cy) {
    ns = Math.min(SCALE_MAX, Math.max(SCALE_MIN, ns));
    const old = view.scale;
    if (ns === old) return;
    // 屏幕点 = 画布点 * scale + view，缩放前后锚点对应同一画布点
    view.x = cx - (cx - view.x) * (ns / old);
    view.y = cy - (cy - view.y) * (ns / old);
    view.scale = ns;
    applyView();
  }

  function zoomCenter(ns) {
    const r = wrap.getBoundingClientRect();
    zoomAt(ns, r.width / 2, r.height / 2);
  }

  /** 适应全貌：按节点包围盒 + 60px 边距计算缩放并居中 */
  function fitView() {
    const nodes = state.flow.nodes;
    if (!nodes.length) { view.x = 0; view.y = 0; view.scale = 1; applyView(); return; }
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    nodes.forEach(n => {
      const el = canvas.querySelector(`.flow-node[data-id="${n.id}"]`);
      const h = el ? el.offsetHeight : 120;
      minX = Math.min(minX, n.x || 0); minY = Math.min(minY, n.y || 0);
      maxX = Math.max(maxX, (n.x || 0) + NODE_W); maxY = Math.max(maxY, (n.y || 0) + h);
    });
    const r = wrap.getBoundingClientRect();
    const pad = 60;
    const s = Math.min(SCALE_MAX, Math.max(SCALE_MIN,
      Math.min(r.width / (maxX - minX + pad * 2), r.height / (maxY - minY + pad * 2))));
    view.scale = s;
    view.x = r.width / 2 - ((minX + maxX) / 2) * s;
    view.y = r.height / 2 - ((minY + maxY) / 2) * s;
    applyView();
  }

  /** 全屏切换：⛶ 进入全屏 / ⤢ 退出全屏；画布尺寸变化后自动重新适应全貌 */
  function toggleFullscreen() {
    if (document.fullscreenElement) { document.exitFullscreen(); return; }
    if (wrap.requestFullscreen) wrap.requestFullscreen();
  }
  document.addEventListener('fullscreenchange', () => {
    const on = document.fullscreenElement === wrap;
    const btn = document.getElementById('zoomFit');
    if (btn) { btn.textContent = on ? '⤢' : '⛶'; btn.title = on ? '退出全屏' : '全屏'; }
    requestAnimationFrame(fitView);
  });

  // ---------- 工具 ----------
  const nodeById = (id) => state.flow.nodes.find(n => n.id === id);
  const meta = (n) => NT[n.type] || NT.TEXT;
  const markDirty = () => { state.dirty = true; document.getElementById('saveState').textContent = '有未保存更改'; };

  /** 某节点的全部上游祖先（含间接） */
  function ancestorsOf(nodeId) {
    const parentMap = {};
    state.flow.edges.forEach(e => (parentMap[e.to] = [...(parentMap[e.to] || []), e.from]));
    const seen = new Set(); const stack = [...(parentMap[nodeId] || [])];
    while (stack.length) {
      const id = stack.pop();
      if (seen.has(id)) continue;
      seen.add(id);
      stack.push(...(parentMap[id] || []));
    }
    return [...seen];
  }

  // ---------- 初始化 ----------
  async function init() {
    bindToolbar();
    bindPalette();
    bindCanvas();
    bindKeys();
    const flowId = window.__FLOW_ID__;
    if (flowId) {
      try {
        state.flow = await apiGet('/api/flows/' + flowId);
        state.flow.nodes = state.flow.nodes || [];
        // 规范化连线字段并丢弃端点缺失的无效连线（历史脏数据）
        state.flow.edges = (state.flow.edges || [])
          .map(e => ({ from: e.from ?? e.source, to: e.to ?? e.target, condition: e.condition || '' }))
          .filter(e => e.from && e.to);
      } catch (e) { toast('流程加载失败，已新建空白流程', 'err'); }
    }
    document.getElementById('flowName').value = state.flow.name || '';
    const descInput = document.getElementById('flowDescInput');
    if (descInput) descInput.value = state.flow.description || '';
    if (state.flow.id) {
      const badge = document.getElementById('flowIdBadge');
      badge.style.display = ''; badge.textContent = state.flow.id;
    }
    renderAll();
    applyView();
  }

  // ---------- 渲染 ----------
  function renderAll() { renderNodes(); renderWires(); renderProps(); }

  function renderNodes() {
    canvas.querySelectorAll('.flow-node').forEach(el => el.remove());
    state.flow.nodes.forEach(n => {
      const m = meta(n);
      const el = document.createElement('div');
      el.className = 'flow-node' + (state.selected === n.id ? ' selected' : '');
      el.dataset.id = n.id;
      el.style.left = (n.x || 40) + 'px';
      el.style.top = (n.y || 40) + 'px';
      el.style.setProperty('--dot', m.color);
      el.innerHTML = `
        <span class="port-in" title="输入端口"></span>
        <div class="head">
          <span class="ico">${m.icon}</span><b>${esc(n.name || m.label)}</b>
          <span class="del" title="删除节点">✕</span>
        </div>
        <div class="body">
          <div class="prompt-preview">${esc(n.prompt || '（未设置处理指令）')}</div>
          <span class="out-badge">${outBadge(n)}</span>
        </div>
        <span class="port" title="从此拖到目标节点"></span>`;
      canvas.appendChild(el);
    });
  }

  function outBadge(n) {
    if (n.type === 'ROUTER') {
      const cnt = (n.routes || []).length;
      return `<span class="badge badge-pending" style="font-size:10px">路由 · ${cnt}分支</span>`;
    }
    const spec = n.outputSpec || {};
    if (spec.type === 'text') return '<span class="badge badge-pending" style="font-size:10px">文本输出</span>';
    const cnt = (spec.fields || []).length;
    return `<span class="badge badge-pending" style="font-size:10px">JSON · ${cnt}字段</span>`;
  }

  /** 贝塞尔连线 */
  function wirePath(x1, y1, x2, y2) {
    const dx = Math.max(40, Math.abs(x2 - x1) / 2);
    return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`;
  }

  function renderWires() {
    wires.innerHTML = state.flow.edges.map(e => {
      const s = nodeById(e.from), t = nodeById(e.to);
      if (!s || !t) return '';
      const se = canvas.querySelector(`.flow-node[data-id="${e.from}"]`);
      const te = canvas.querySelector(`.flow-node[data-id="${e.to}"]`);
      if (!se || !te) return '';
      const x1 = (s.x || 0) + NODE_W, y1 = (s.y || 0) + se.offsetHeight / 2;
      const x2 = t.x || 0, y2 = (t.y || 0) + te.offsetHeight / 2;
      const d = wirePath(x1, y1, x2, y2);
      const sel = state.selectedEdge === `${e.from}>${e.to}` ? ' wire-sel' : '';
      // 贝塞尔中点恰为两端点均值，用作条件标签锚点
      const label = e.condition
        ? `<text x="${(x1 + x2) / 2}" y="${(y1 + y2) / 2 - 7}" text-anchor="middle" class="wire-label">${esc(e.condition)}</text>` : '';
      return `<path class="wire-line${sel}" d="${d}"/>
              <path d="${d}" stroke="transparent" stroke-width="16" fill="none" style="pointer-events:stroke; cursor:pointer" data-edge="${esc(e.from)}>${esc(e.to)}"/>${label}`;
    }).join('');
  }

  // ---------- 连线交互（点击选中，删除走属性面板） ----------
  wires.addEventListener('click', (e) => {
    const hit = e.target.closest('path[data-edge]');
    if (!hit) return;
    selectEdge(hit.dataset.edge);
  });

  function selectEdge(key) {
    state.selectedEdge = key;
    state.selected = null;
    canvas.querySelectorAll('.flow-node').forEach(el => el.classList.remove('selected'));
    renderWires(); renderProps();
  }

  // ---------- 画布平移（空白处左键 / 任意处中键） ----------
  let pan = null; // {sx, sy, ox, oy}

  wrap.addEventListener('mousedown', (e) => {
    if (e.button !== 0 && e.button !== 1) return;
    // 左键仅空白处平移（节点/端口/按钮走各自逻辑）；中键任意位置平移
    const onBg = e.target === canvas || e.target === wrap;
    if (e.button === 0 && !onBg) return;
    e.preventDefault();
    pan = { sx: e.clientX, sy: e.clientY, ox: view.x, oy: view.y };
    wrap.classList.add('panning');
  });

  // ---------- 节点拖拽 / 选择 / 端口连线 ----------
  let dragNode = null; // {id, startX, startY, origX, origY, moved}

  /** 事件坐标 → 画布坐标（需除以缩放比例） */
  function canvasPos(e) {
    const rect = canvas.getBoundingClientRect();
    return { x: (e.clientX - rect.left) / view.scale, y: (e.clientY - rect.top) / view.scale };
  }

  canvas.addEventListener('mousedown', (e) => {
    const nodeEl = e.target.closest('.flow-node');
    // 端口开始连线
    if (e.target.classList.contains('port')) {
      e.preventDefault();
      const fromId = nodeEl.dataset.id;
      const temp = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      temp.setAttribute('stroke', '#6366f1'); temp.setAttribute('stroke-width', '2.5');
      temp.setAttribute('stroke-dasharray', '6 4'); temp.setAttribute('fill', 'none');
      wires.appendChild(temp);
      state.connect = { fromId, temp };
      const p = canvasPos(e);
      const s = nodeById(fromId);
      const se = canvas.querySelector(`.flow-node[data-id="${fromId}"]`);
      temp.setAttribute('d', wirePath((s.x || 0) + NODE_W, (s.y || 0) + se.offsetHeight / 2, p.x, p.y));
      return;
    }
    if (e.target.classList.contains('del')) {
      removeNode(nodeEl.dataset.id);
      return;
    }
    if (nodeEl) {
      select(nodeEl.dataset.id);
      const n = nodeById(nodeEl.dataset.id);
      dragNode = { id: n.id, startX: e.clientX, startY: e.clientY, origX: n.x || 0, origY: n.y || 0, moved: false };
      e.preventDefault();
    }
  });

  document.addEventListener('mousemove', (e) => {
    if (pan) {
      view.x = pan.ox + (e.clientX - pan.sx);
      view.y = pan.oy + (e.clientY - pan.sy);
      applyView();
      return;
    }
    if (state.connect) {
      const p = canvasPos(e);
      const s = nodeById(state.connect.fromId);
      const se = canvas.querySelector(`.flow-node[data-id="${state.connect.fromId}"]`);
      state.connect.temp.setAttribute('d',
        wirePath((s.x || 0) + NODE_W, (s.y || 0) + se.offsetHeight / 2, p.x, p.y));
      return;
    }
    if (dragNode) {
      const dx = (e.clientX - dragNode.startX) / view.scale;
      const dy = (e.clientY - dragNode.startY) / view.scale;
      if (Math.abs(dx) + Math.abs(dy) > 3) dragNode.moved = true;
      const n = nodeById(dragNode.id);
      if (n && dragNode.moved) {
        n.x = Math.max(0, dragNode.origX + dx);
        n.y = Math.max(0, dragNode.origY + dy);
        const el = canvas.querySelector(`.flow-node[data-id="${n.id}"]`);
        el.style.left = n.x + 'px'; el.style.top = n.y + 'px';
        renderWires();
      }
    }
  });

  document.addEventListener('mouseup', (e) => {
    if (pan) {
      pan = null;
      wrap.classList.remove('panning');
    }
    if (state.connect) {
      const targetEl = document.elementFromPoint(e.clientX, e.clientY)?.closest('.flow-node');
      if (targetEl && targetEl.dataset.id !== state.connect.fromId) {
        addEdge(state.connect.fromId, targetEl.dataset.id);
      }
      state.connect.temp.remove();
      state.connect = null;
    }
    if (dragNode) {
      if (dragNode.moved) markDirty();
      dragNode = null;
    }
  });

  function addEdge(source, target) {
    const dup = state.flow.edges.some(e => e.from === source && e.to === target);
    if (!dup) {
      state.flow.edges.push({ from: source, to: target });
      markDirty();
      renderWires(); renderProps();
    }
  }

  async function removeNode(id) {
    const n = nodeById(id);
    const ok = await uiConfirm({
      title: '删除节点',
      message: `确定删除节点 <b>${esc(n ? (n.name || id) : id)}</b> 及其相关连线？`,
      okText: '删除',
      danger: true,
    });
    if (!ok) return;
    state.flow.nodes = state.flow.nodes.filter(x => x.id !== id);
    state.flow.edges = state.flow.edges.filter(e => e.from !== id && e.to !== id);
    if (state.selectedEdge && state.selectedEdge.split('>').includes(id)) state.selectedEdge = null;
    if (state.selected === id) { state.selected = null; props.innerHTML = '<div class="no-select">点击画布中的节点<br>编辑属性与输出契约</div>'; }
    markDirty(); renderAll();
  }

  function select(id) {
    state.selected = id;
    state.selectedEdge = null;
    canvas.querySelectorAll('.flow-node').forEach(el => el.classList.toggle('selected', el.dataset.id === id));
    renderProps();
  }

  // ---------- Palette 拖入 ----------
  function bindPalette() {
    document.querySelectorAll('.palette-item').forEach(item => {
      item.addEventListener('dragstart', (e) => e.dataTransfer.setData('text/plain', item.dataset.type));
    });
  }

  function bindCanvas() {
    wrap.classList.add('pan-ready');
    applyView();

    // 滚轮缩放（以鼠标位置为锚点）
    wrap.addEventListener('wheel', (e) => {
      e.preventDefault();
      const r = wrap.getBoundingClientRect();
      const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
      zoomAt(view.scale * factor, e.clientX - r.left, e.clientY - r.top);
    }, { passive: false });

    // 缩放控件：全屏 / 缩小 / 百分比重置 / 放大
    const bind = (id, fn) => {
      const el = document.getElementById(id);
      if (el) el.addEventListener('click', fn);
    };
    bind('zoomFit', toggleFullscreen);
    bind('zoomOut', () => zoomCenter(view.scale / 1.2));
    bind('zoomIn', () => zoomCenter(view.scale * 1.2));
    bind('zoomVal', () => zoomCenter(1));

    // 双击空白处：适应全貌
    wrap.addEventListener('dblclick', (e) => {
      if (e.target === canvas || e.target === wrap) fitView();
    });

    wrap.addEventListener('dragover', (e) => e.preventDefault());
    wrap.addEventListener('drop', (e) => {
      e.preventDefault();
      const type = e.dataTransfer.getData('text/plain');
      if (!NT[type]) return;
      const p = canvasPos(e);
      addNode(type, p.x - NODE_W / 2, p.y - 40);
    });
  }

  function addNode(type, x, y) {
    const seq = state.flow.nodes.length + 1;
    const node = {
      id: 'n' + Date.now().toString(36) + seq,
      name: NT[type].label + ' ' + seq,
      type,
      prompt: '',
      systemPrompt: '',
      ...(type === 'ROUTER'
        ? { routes: [{ label: '分支A', desc: '' }, { label: '分支B', desc: '' }] }
        : type === 'AGGREGATE'
          ? { outputSpec: { type: 'text' }, aggregateStrategy: 'concat' }
          : type === 'TOOL' || type === 'KNOWLEDGE' || type === 'IMAGE_GEN' || type === 'MODERATION' || type === 'EVALUATE' || type === 'SUBFLOW'
            ? { outputSpec: { type: 'text' } }
            : { outputSpec: { type: 'json', fields: [{ name: 'result', desc: '处理结果', required: true }] } }),
      x: Math.max(10, x), y: Math.max(10, y),
    };
    state.flow.nodes.push(node);
    markDirty(); renderNodes(); select(node.id);
  }

  // ---------- 属性面板 ----------
  function renderProps() {
    const ek = state.selectedEdge;
    if (ek && !state.selected) {
      const [from, to] = ek.split('>');
      const edge = state.flow.edges.find(e => e.from === from && e.to === to);
      if (edge) { renderEdgeProps(edge); return; }
      state.selectedEdge = null; // 连线已被删除
    }
    const n = state.selected && nodeById(state.selected);
    if (!n) { props.innerHTML = '<div class="no-select">点击画布中的节点<br>编辑属性与输出契约</div>'; return; }
    const m = meta(n);
    const isRouter = n.type === 'ROUTER';
    const isFixed = n.type === 'IMAGE_GEN' || n.type === 'MODERATION';
    const isEval = n.type === 'EVALUATE';
    const isSubflow = n.type === 'SUBFLOW';
    const isAggregate = n.type === 'AGGREGATE';
    const aggStrategy = isAggregate ? (n.aggregateStrategy || 'concat') : null;
    const isJson = (n.outputSpec?.type || 'json') === 'json';
    const fields = n.outputSpec?.fields || [];

    props.innerHTML = `
      <h3><span class="ico" style="font-style:normal">${m.icon}</span> ${m.label} <span style="font-size:11px; color:var(--text-3); margin-left:auto">${esc(n.id)}</span></h3>
      <div class="field"><label>节点名称</label><input class="input" id="p-name" value="${esc(n.name)}"></div>
      <div class="field"><label>${isAggregate ? (aggStrategy === 'template' ? '合并模板' : aggStrategy === 'concat' ? '分隔符（可选，默认空行）' : '处理指令（该策略不使用，可留空）') : '处理指令（提示词）'}</label><textarea class="textarea" id="p-prompt" rows="${isAggregate && aggStrategy !== 'template' ? 2 : 5}" placeholder="${n.type === 'AGGREGATE' ? (aggStrategy === 'template' ? '支持变量：{{节点ID}} / {{节点名}} / {{节点ID.json.字段}} / {{input}}' : aggStrategy === 'concat' ? '留空用空行分隔；也可填 --- 等分隔文本' : '留空即可') : n.type === 'IMAGE_GEN' ? '描述要生成的画面，可用右侧变量，如：为{{n1.json.name}}生成一张产品宣传图…' : n.type === 'MODERATION' ? '可留空：未引用变量时自动审核流程输入与上游节点内容…' : n.type === 'EVALUATE' ? '可留空：按默认维度（准确性/完整性/格式规范性）评估上游输出；也可自定义评估维度与达标标准…' : n.type === 'SUBFLOW' ? '作为子流程的输入文本模板，可用右侧变量，如：请分析以下内容：{{input}}' : '描述这个节点要做什么，可用右侧变量…'}">${esc(n.prompt)}</textarea></div>
      ${isFixed || isEval || isSubflow || isAggregate ? '' : `
      <div class="field"><label>系统提示词（可选）</label><textarea class="textarea" id="p-system" rows="3" placeholder="留空使用全局默认">${esc(n.systemPrompt || '')}</textarea></div>`}
      ${varBoxHtml(n)}
      ${isRouter ? `
      <div class="field"><label>候选分支（模型从中选择一个）</label>
        <div id="p-routes">${(n.routes || []).map((r, i) => routeRowHtml(r, i)).join('')}</div>
        <button class="btn btn-sm" id="p-addroute" type="button">＋ 添加分支</button>
        <div class="text-3" style="font-size:11px; margin-top:5px">分支名即路由值：模型输出 <code>{"route":"分支名"}</code> 后走对应连线，需在连线属性里设置分支条件。</div>
      </div>` : isFixed ? `
      <div class="field"><label>固定输出</label>
        <div class="text-3" style="font-size:11px; line-height:1.7">${n.type === 'IMAGE_GEN'
          ? '文本输出 = 图片URL（下游 <code>{{节点id}}</code> 引用）；JSON 输出含 <code>url</code> 字段（<code>{{节点id.json.url}}</code>）。'
          : '文本输出 = 审核结论（含命中类别）；JSON 输出含 <code>flagged</code> 与 <code>categories</code>（如 <code>{{节点id.json.flagged}}</code>），可接条件路由分流。'}</div>
      </div>` : isEval ? `
      <div class="field"><label>固定输出</label>
        <div class="text-3" style="font-size:11px; line-height:1.7">JSON 输出 <code>score</code>（0-100 综合得分）、<code>passed</code>（是否达标）、<code>reason</code>（评估理由）、<code>dimensions</code>（维度得分明细），文本输出 = 得分摘要。<code>passed=false</code> 时可接条件路由走重试或人工兜底分支（如 <code>{{节点id.json.passed}}</code>）。</div>
      </div>` : isSubflow ? `
      <div class="field"><label>引用流程（作为宏同步执行）</label>
        <select class="select" id="p-subflow"><option value="">加载中…</option></select>
        <div class="text-3" style="font-size:11px; margin-top:5px">选中流程将整体同步执行（不单独产生运行记录）；被引用流程中不能再引用本流程（防环）。</div>
      </div>
      <div class="field"><label>固定输出</label>
        <div class="text-3" style="font-size:11px; line-height:1.7">取子流程末端节点（无出边）输出：文本换行拼接；恰好一个成功末端带 JSON 时透传（下游 <code>{{节点id.json.路径}}</code> 引用）。子流程全部末端失败则本节点失败。</div>
      </div>` : isAggregate ? `
      <div class="field"><label>合并策略</label>
        <select class="select" id="p-agg-strategy">
          <option value="concat" ${aggStrategy === 'concat' ? 'selected' : ''}>拼接（按上游顺序合并文本）</option>
          <option value="jsonMerge" ${aggStrategy === 'jsonMerge' ? 'selected' : ''}>JSON 字段合并（每上游一个字段）</option>
          <option value="template" ${aggStrategy === 'template' ? 'selected' : ''}>模板渲染（自定义合并格式）</option>
        </select>
        <div class="text-3" style="font-size:11px; margin-top:5px">合并单位：直接连线到本节点的上游（按定义顺序）；不经模型、零 token 消耗。</div>
      </div>
      <div class="field"><label>固定输出</label>
        <div class="text-3" style="font-size:11px; line-height:1.7">${aggStrategy === 'jsonMerge'
          ? 'JSON 输出：每个上游一个字段（字段名=节点名，重名回退节点ID），值为该上游 JSON（无则文本），下游 <code>{{节点id.json.字段名}}</code> 引用。'
          : '文本输出：拼接结果或模板渲染结果，下游 <code>{{节点id}}</code> 引用。'}</div>
      </div>` : `
      <div class="field"><label>输出类型</label>
        <select class="select" id="p-outtype">
          <option value="json" ${isJson ? 'selected' : ''}>JSON 结构化</option>
          <option value="text" ${isJson ? '' : 'selected'}>纯文本</option>
        </select>
      </div>
      <div id="p-fields-wrap" style="${isJson ? '' : 'display:none'}">
        <div class="field"><label>字段契约 <span class="text-3" style="font-weight:400">（输出JSON的字段定义）</span></label>
          <div id="p-fields">${fields.map((f, i) => fieldRowHtml(f, i)).join('')}</div>
          <button class="btn btn-sm" id="p-addfield" type="button">＋ 添加字段</button>
        </div>
      </div>`}
      ${m === NT.IMAGE || n.type === 'IMAGE' || n.type === 'COMBINED' ? `
      <div class="field"><label>节点图片URL（可选，覆盖流程输入图片）</label><input class="input" id="p-imageurl" value="${esc(n.imageUrl || '')}" placeholder="https://…"></div>` : ''}
      ${isFixed || isSubflow || isAggregate ? '' : `
      <div class="flex gap-8">
        <div class="field flex-1"><label>模型覆盖</label><input class="input mono" id="p-model" value="${esc(n.model || '')}" placeholder="留空默认"></div>
        <div class="field" style="width:96px"><label>温度</label><input class="input" id="p-temp" type="number" min="0" max="2" step="0.1" value="${n.temperature ?? ''}"></div>
      </div>`}
      ${isFixed || isEval || isSubflow || isAggregate ? '' : `
      <div class="field"><label>会话记忆轮数（可选）</label><input class="input" id="p-memory" type="number" min="1" max="50" step="1" value="${n.memoryTurns ?? ''}" placeholder="填入后该节点携带最近N轮对话历史（需运行时传入会话ID）">
        <div class="text-3" style="font-size:11px; margin-top:5px">多轮对话记忆：历史消息持久化到MySQL，同一会话ID跨运行共享；留空则无记忆。</div>
      </div>`}
      ${n.type === 'KNOWLEDGE' ? `
      <div class="flex gap-8">
        <div class="field flex-1"><label>绑定知识库</label>
          <select class="select" id="p-kb"><option value="">加载中…</option></select>
          <div class="text-3" style="font-size:11px; margin-top:5px">节点指令（变量解析后）将作为查询语句，向量检索该知识库并把命中片段注入提示词，模型基于参考资料作答。</div>
        </div>
        <div class="field" style="width:96px"><label>TopK</label><input class="input" id="p-topk" type="number" min="1" max="20" step="1" value="${n.topK ?? ''}" placeholder="默认"></div>
      </div>` : ''}
      <div class="link-line">⇦ 输入端口：连接上游节点输出</div>`;

    // ---- 面板事件 ----
    props.querySelector('#p-name').addEventListener('input', (e) => {
      n.name = e.target.value;
      canvas.querySelector(`.flow-node[data-id="${n.id}"] b`).textContent = n.name || m.label;
      markDirty();
    });
    props.querySelector('#p-prompt').addEventListener('input', (e) => {
      n.prompt = e.target.value;
      canvas.querySelector(`.flow-node[data-id="${n.id}"] .prompt-preview`).textContent = n.prompt || '（未设置处理指令）';
      markDirty();
    });
    const sysPr = props.querySelector('#p-system');
    if (sysPr) sysPr.addEventListener('input', (e) => { n.systemPrompt = e.target.value; markDirty(); });
    const imgUrl = props.querySelector('#p-imageurl');
    if (imgUrl) imgUrl.addEventListener('input', (e) => { n.imageUrl = e.target.value; markDirty(); });
    const modelIn = props.querySelector('#p-model');
    if (modelIn) modelIn.addEventListener('input', (e) => { n.model = e.target.value; markDirty(); });
    const tempIn = props.querySelector('#p-temp');
    if (tempIn) tempIn.addEventListener('input', (e) => { n.temperature = e.target.value === '' ? null : Number(e.target.value); markDirty(); });
    const memIn = props.querySelector('#p-memory');
    if (memIn) memIn.addEventListener('input', (e) => { n.memoryTurns = e.target.value === '' ? null : Number(e.target.value); markDirty(); });
    if (n.type === 'KNOWLEDGE') {
      const sel = props.querySelector('#p-kb');
      apiGet('/api/knowledge').then(list => {
        sel.innerHTML = '<option value="">（请选择知识库）</option>'
          + (list || []).map(k => `<option value="${k.id}" ${k.id === n.knowledgeBaseId ? 'selected' : ''}>${esc(k.name)}</option>`).join('');
      }).catch(() => { sel.innerHTML = '<option value="">知识库加载失败</option>'; });
      sel.addEventListener('change', (e) => { n.knowledgeBaseId = e.target.value === '' ? null : Number(e.target.value); markDirty(); });
      props.querySelector('#p-topk').addEventListener('input', (e) => { n.topK = e.target.value === '' ? null : Number(e.target.value); markDirty(); });
    }
    if (n.type === 'SUBFLOW') {
      const sel = props.querySelector('#p-subflow');
      apiGet('/api/flows').then(list => {
        sel.innerHTML = '<option value="">（请选择被引用流程）</option>'
          + (list || []).filter(f => f.id !== state.flow.id)
            .map(f => `<option value="${esc(f.id)}" ${f.id === n.subflowId ? 'selected' : ''}>${esc(f.name)}（${esc(f.id)}）</option>`).join('');
      }).catch(() => { sel.innerHTML = '<option value="">流程加载失败</option>'; });
      sel.addEventListener('change', (e) => { n.subflowId = e.target.value || null; markDirty(); });
    }
    if (isAggregate) {
      props.querySelector('#p-agg-strategy').addEventListener('change', (e) => {
        n.aggregateStrategy = e.target.value;
        markDirty(); renderProps(); // 重渲染：prompt 字段 label/占位随策略切换
      });
    }
    if (isRouter) {
      bindRouteRows(n);
    } else if (!isFixed && !isSubflow && !isAggregate) {
      props.querySelector('#p-outtype').addEventListener('change', (e) => {
        n.outputSpec = n.outputSpec || {};
        n.outputSpec.type = e.target.value;
        if (e.target.value === 'json' && !n.outputSpec.fields?.length) {
          n.outputSpec.fields = [{ name: 'result', desc: '处理结果', required: true }];
        }
        canvas.querySelector(`.flow-node[data-id="${n.id}"] .out-badge`).innerHTML = outBadge(n);
        markDirty(); renderProps();
      });
      bindFieldRows(n);
    }
    bindVarChips(n);
  }

  /** 选中连线时的轻量边面板：分支条件（仅路由出线）+ 删除 */
  function renderEdgeProps(edge) {
    const from = nodeById(edge.from), to = nodeById(edge.to);
    const fromMeta = from ? (NT[from.type] || NT.TEXT) : null;
    const isRouter = from && from.type === 'ROUTER';
    const routes = isRouter ? (from.routes || []).filter(r => r && (r.label || '').trim()) : [];
    const opts = [`<option value="" ${!edge.condition ? 'selected' : ''}>默认分支（未命中其他分支时兜底）</option>`]
      .concat(routes.map(r => `<option value="${esc(r.label)}" ${edge.condition === r.label ? 'selected' : ''}>${esc(r.label)}${r.desc ? '：' + esc(r.desc) : ''}</option>`))
      .join('');
    props.innerHTML = `
      <h3><span class="ico" style="font-style:normal">🔗</span> 连线 <span style="font-size:11px; color:var(--text-3); margin-left:auto">${esc(edge.from)} → ${esc(edge.to)}</span></h3>
      <div class="field"><label>连线方向</label>
        <div class="text-2" style="font-size:12.5px">${fromMeta ? fromMeta.icon + ' ' : ''}${esc(from?.name || edge.from)} ⇦ ${esc(to?.name || edge.to)}</div>
      </div>
      ${isRouter ? `
      <div class="field"><label>分支条件</label>
        <select class="select" id="edge-condition">${opts}</select>
        <div class="text-3" style="font-size:11px; margin-top:5px">模型输出的 route 与所选分支名一致时走这条连线；其他分支均未命中时走「默认分支」。</div>
      </div>` : `<div class="link-line">普通连线：上游完成后下游即执行</div>`}
      <button class="btn btn-sm" id="edge-del" type="button" style="margin-top:10px; color:var(--failed)">删除连线</button>`;
    const sel = props.querySelector('#edge-condition');
    if (sel) sel.addEventListener('change', (e) => { edge.condition = e.target.value; markDirty(); renderWires(); });
    props.querySelector('#edge-del').addEventListener('click', async () => {
      const ok = await uiConfirm({
        title: '删除连线',
        message: '确定删除这条节点连线？删除后可重新拖拽连接。',
        okText: '删除',
        danger: true,
      });
      if (!ok) return;
      state.flow.edges = state.flow.edges.filter(ed => !(ed.from === edge.from && ed.to === edge.to));
      state.selectedEdge = null;
      markDirty(); renderWires(); renderProps();
    });
  }

  function routeRowHtml(r, i) {
    return `<div class="field-row" data-route-i="${i}">
      <input class="input r-label" value="${esc(r.label || '')}" placeholder="分支名">
      <input class="input r-desc" value="${esc(r.desc || '')}" placeholder="选择依据（给模型的提示）">
      <span class="rm" title="移除">✕</span>
    </div>`;
  }

  function bindRouteRows(n) {
    const wrapEl = props.querySelector('#p-routes');
    const addBtn = props.querySelector('#p-addroute');

    function sync() {
      n.routes = [...wrapEl.querySelectorAll('.field-row')].map(row => ({
        label: row.querySelector('.r-label').value.trim(),
        desc: row.querySelector('.r-desc').value.trim(),
      })).filter(r => r.label);
      const el = canvas.querySelector(`.flow-node[data-id="${n.id}"] .out-badge`);
      if (el) el.innerHTML = outBadge(n);
      markDirty();
    }

    wrapEl.addEventListener('input', sync);
    wrapEl.addEventListener('click', (e) => {
      const row = e.target.closest('.field-row');
      if (e.target.classList.contains('rm') && row) { row.remove(); sync(); }
    });
    if (addBtn) addBtn.addEventListener('click', () => {
      wrapEl.insertAdjacentHTML('beforeend', routeRowHtml({ label: '', desc: '' }, -1));
      sync();
    });
  }

  function fieldRowHtml(f, i) {
    return `<div class="field-row" data-i="${i}">
      <input class="input f-name" value="${esc(f.name || '')}" placeholder="字段名">
      <input class="input f-desc" value="${esc(f.desc || '')}" placeholder="说明">
      <label class="req-toggle ${f.required ? 'on' : ''}" title="是否必填"><input type="checkbox" ${f.required ? 'checked' : ''} style="display:none">必填</label>
      <span class="rm" title="移除">✕</span>
    </div>`;
  }

  function bindFieldRows(n) {
    const wrapEl = props.querySelector('#p-fields');
    const addBtn = props.querySelector('#p-addfield');

    function sync() {
      n.outputSpec.fields = [...wrapEl.querySelectorAll('.field-row')].map(row => ({
        name: row.querySelector('.f-name').value.trim(),
        desc: row.querySelector('.f-desc').value.trim(),
        required: row.querySelector('.req-toggle input').checked,
      }));
      const el = canvas.querySelector(`.flow-node[data-id="${n.id}"] .out-badge`);
      if (el) el.innerHTML = outBadge(n);
      markDirty();
    }

    wrapEl.addEventListener('input', sync);
    wrapEl.addEventListener('change', sync);
    wrapEl.addEventListener('click', (e) => {
      const row = e.target.closest('.field-row');
      if (e.target.classList.contains('rm') && row) { row.remove(); sync(); }
      if (e.target.closest('.req-toggle')) {
        const t = e.target.closest('.req-toggle');
        t.classList.toggle('on', t.querySelector('input').checked);
      }
    });
    if (addBtn) addBtn.addEventListener('click', () => {
      wrapEl.insertAdjacentHTML('beforeend', fieldRowHtml({ name: '', desc: '', required: true }, -1));
      sync();
    });
  }

  // ---------- 变量 chips ----------
  function varBoxHtml(n) {
    const anc = ancestorsOf(n.id).map(nodeById).filter(Boolean);
    const chips = [];
    chips.push(['{{input}}', '流程输入文本']);
    chips.push(['{{input.json}}', '同input别名']);
    if (state.flow.nodes.some(x => x.type === 'EXCEL') || true) chips.push(['{{input.rows}}', 'Excel解析数据（存在时生效）']);
    anc.forEach(a => {
      chips.push([`{{${a.id}.json}}`, `${a.name} · JSON输出`]);
      (a.outputSpec?.fields || []).forEach(f => {
        if (f.name) chips.push([`{{${a.id}.json.${f.name}}}`, `${a.name} · ${f.name}`]);
      });
      chips.push([`{{${a.id}.output}}`, `${a.name} · 原始输出`]);
    });
    if (!anc.length) chips.push(['（无上游节点，可将左侧节点连线到本节点）', '']);
    return `<div class="var-box">
      <div class="vb-title">可用变量 · 点击插入到处理指令</div>
      <div class="chips">${chips.map(([v, tip]) => `<span class="chip ${tip ? '' : 'chip-doc'}" data-var="${esc(v)}" title="${esc(tip)}">${esc(v)}</span>`).join('')}</div>
    </div>`;
  }

  function bindVarChips(n) {
    props.querySelectorAll('.chip[data-var]').forEach(chip => {
      chip.addEventListener('click', () => {
        const ta = props.querySelector('#p-prompt');
        const v = chip.dataset.var;
        if (!v.startsWith('{{')) return;
        const at = ta.selectionStart ?? ta.value.length;
        ta.value = ta.value.slice(0, at) + v + ta.value.slice(ta.selectionEnd ?? at);
        ta.dispatchEvent(new Event('input'));
        ta.focus();
      });
    });
  }

  // ---------- 工具栏 ----------
  function bindToolbar() {
    document.getElementById('flowName').addEventListener('input', (e) => { state.flow.name = e.target.value; markDirty(); });
    const descInput = document.getElementById('flowDescInput');
    if (descInput) descInput.addEventListener('input', (e) => { state.flow.description = e.target.value; markDirty(); });

    document.getElementById('btnValidate').addEventListener('click', async () => {
      const r = await apiPost('/api/flows/validate', payload());
      if (r.valid) toast('校验通过，编排结构合法', 'ok');
      else toast(r.errors.join('\n'), 'err');
    });
    document.getElementById('btnSave').addEventListener('click', save);
    document.getElementById('btnRun').addEventListener('click', async () => {
      const id = await save();
      if (id) location.href = '/run/' + id;
    });
  }

  function payload() {
    return {
      id: state.flow.id || null,
      name: document.getElementById('flowName').value.trim() || '未命名流程',
      description: (document.getElementById('flowDescInput') || {}).value || '',
      nodes: state.flow.nodes,
      edges: state.flow.edges,
    };
  }

  async function save() {
    if (!state.flow.nodes.length) { toast('请先拖入至少一个节点', 'err'); return null; }
    try {
      const saved = await apiPost('/api/flows', payload());
      state.flow.id = saved.id;
      state.dirty = false;
      document.getElementById('saveState').textContent = '已保存 ' + new Date().toLocaleTimeString();
      const badge = document.getElementById('flowIdBadge');
      badge.style.display = ''; badge.textContent = saved.id;
      history.replaceState(null, '', '/flow-editor?id=' + saved.id);
      toast('流程已保存', 'ok');
      return saved.id;
    } catch (e) { return null; }
  }

  // ---------- 快捷键 ----------
  function bindKeys() {
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Delete' && e.key !== 'Backspace') return;
      const tag = (document.activeElement || {}).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (state.selected) removeNode(state.selected);
    });
  }

  init();
})();
