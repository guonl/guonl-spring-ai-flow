/* ============================================================
   playground.js —— 场景体验：5场景tab / 示例预载 / 流式打字机渲染
   ============================================================ */
(function () {
  // ---------- 示例数据 ----------
  const EXAMPLES = {
    text: {
      prompt: '从以下客户留言中提取核心诉求，评估紧急程度，并给出一句客服回复话术。',
      text: '你好，我上周在你们平台订购的净水器到货后发现包装破损，机器无法开机，家里老人小孩等着用水，请尽快给我处理，要么换货要么退款！订单号 ORD20240501。',
      fields: [['issue', '核心诉求', 1], ['urgency', '紧急程度：high/medium/low', 1], ['reply', '建议回复话术', 0]],
    },
    image: {
      prompt: '识别这张营业执照图片中的企业关键信息，无法识别的字段返回null。',
      text: '',
      imageUrl: 'https://images.unsplash.com/photo-1497366216548-37526070297c?w=800',
      fields: [['company_name', '公司名称', 1], ['tax_no', '统一社会信用代码', 1], ['legal_rep', '法定代表人', 1], ['address', '注册地址', 0]],
    },
    json: {
      prompt: '清洗以下JSON数据：移除值为null或空字符串的字段，把金额字段统一转为数字（单位分），输出规整后的结构。',
      text: '{"orderNo":"A1024","amount":"129900","discount":null,"remark":"","items":[{"sku":"S1","price":"59900"},{"sku":"S2","price":"70000"}]}',
      fields: [['clean_data', '清洗后的完整JSON', 1], ['removed_fields', '被移除的字段列表', 1]],
    },
    excel: {
      prompt: '分析上传的订单表格：计算总金额，统计各状态订单数量，找出金额最大的一单。',
      text: '',
      fields: [['total_amount', '订单总金额', 1], ['status_summary', '各状态订单数JSON', 1], ['max_order', '金额最大的订单摘要', 1]],
    },
    combined: {
      prompt: '你是风控专员：综合订单表格数据与凭证图片信息，给出这笔订单的风险审核结论与理由。',
      text: '客户备注：加急发货，收货地址与下单地址不一致，联系人为代购中介。',
      fields: [['verdict', '审核结论：通过/拒绝/人工复核', 1], ['risk_level', '风险等级：high/medium/low', 1], ['reasons', '判断理由列表', 1]],
    },
  };

  let scene = new URLSearchParams(location.search).get('scene') || 'text';
  if (!EXAMPLES[scene]) scene = 'text';

  const $ = (id) => document.getElementById(id);

  // ---------- 场景切换 ----------
  function applyScene(s, loadExample = true) {
    scene = s;
    document.querySelectorAll('.pg-tab').forEach(t => t.classList.toggle('active', t.dataset.scene === s));
    $('grp-image').style.display = (s === 'image' || s === 'combined') ? '' : 'none';
    $('grp-excel').style.display = (s === 'excel' || s === 'combined') ? '' : 'none';
    if (loadExample) fillExample(s);
  }

  function fillExample(s) {
    const ex = EXAMPLES[s];
    $('fPrompt').value = ex.prompt;
    $('fText').value = ex.text || '';
    if (ex.imageUrl !== undefined) $('fImageUrl').value = ex.imageUrl;
    $('fFields').value = ex.fields.map(([n, d, r]) => `${n}|${d}|${r}`).join('\n');
  }

  document.querySelectorAll('.pg-tab').forEach(tab => {
    tab.addEventListener('click', () => applyScene(tab.dataset.scene));
  });

  // 输出类型联动
  $('fOutputType').addEventListener('change', (e) => {
    $('grp-fields').style.display = e.target.value === 'json' ? '' : 'none';
  });

  // ---------- 提交 ----------
  $('pgForm').addEventListener('submit', (e) => {
    e.preventDefault();
    if (!$('fPrompt').value.trim()) { toast('请填写处理指令', 'err'); return; }
    if ($('fStream').checked) streamRun();
    else blockRun();
  });

  function buildForm() {
    const fd = new FormData();
    fd.append('scene', scene);
    fd.append('prompt', $('fPrompt').value.trim());
    if ($('fSystemPrompt').value.trim()) fd.append('systemPrompt', $('fSystemPrompt').value.trim());
    fd.append('outputType', $('fOutputType').value);
    if ($('fOutputType').value === 'json') {
      const fields = $('fFields').value.split('\n').map(l => l.trim()).filter(Boolean).map(line => {
        const [name, desc, req] = line.split('|').map(s => (s || '').trim());
        return { name, desc: desc || name, required: req === '1' || req === 'true' };
      }).filter(f => f.name);
      fd.append('fields', JSON.stringify(fields));
    }
    if ($('fText').value.trim()) fd.append('text', $('fText').value.trim());
    if ($('fImageUrl').value.trim()) fd.append('imageUrl', $('fImageUrl').value.trim());
    if ($('fModel').value.trim()) fd.append('model', $('fModel').value.trim());
    if ($('fTemperature').value !== '') fd.append('temperature', $('fTemperature').value);
    const img = $('fImage').files[0];
    if (img) fd.append('image', img);
    const excel = $('fExcel').files[0];
    if (excel) fd.append('excel', excel);
    return fd;
  }

  function setRunning(running) {
    const btn = $('btnRun');
    btn.disabled = running;
    btn.innerHTML = running ? '<span class="spin"></span> 运行中…' : '▶ 运行场景';
  }

  function pretty(text) {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
  }

  function resetOutput(msg) {
    $('pgBadge').innerHTML = '<span class="badge badge-running">● 运行中</span>';
    $('pgMeta').textContent = '';
    $('pgOutput').innerHTML = esc(msg || '');
  }

  function finishMeta(r) {
    $('pgMeta').textContent = `${r.model || '–'} · ${fmtCost(r.costMillis)} · ${r.totalTokens || 0} tokens`;
  }

  // ---------- 阻塞模式 ----------
  async function blockRun() {
    setRunning(true);
    resetOutput('⏳ 模型处理中，请稍候…');
    try {
      const r = await apiPostForm('/api/playground', buildForm());
      $('pgBadge').innerHTML = r.error ? '<span class="badge badge-failed">✗ 失败</span>' : '<span class="badge badge-success">✓ 完成</span>';
      finishMeta(r);
      $('pgOutput').textContent = r.error ? ('✗ ' + r.error)
        : (r.parsedJson ? pretty(r.parsedJson) : (r.output || '（空输出）'));
    } catch (e) {
      $('pgBadge').innerHTML = '<span class="badge badge-failed">✗ 失败</span>';
      $('pgOutput').textContent = '✗ ' + e.message;
    }
    setRunning(false);
  }

  // ---------- 流式模式（SSE 打字机） ----------
  async function streamRun() {
    setRunning(true);
    resetOutput('');
    const output = $('pgOutput');
    output.innerHTML = '<span class="stream-cursor"></span>';
    let acc = '';
    const render = () => {
      output.innerHTML = esc(acc) + '<span class="stream-cursor"></span>';
      output.scrollTop = output.scrollHeight;
    };
    try {
      const res = await fetch('/api/playground/stream', { method: 'POST', body: buildForm() });
      if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);
      const reader = res.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buf = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const events = buf.split('\n\n');
        buf = events.pop(); // 末尾可能是半截事件
        for (const evt of events) {
          const line = evt.split('\n').find(l => l.startsWith('data:'));
          if (!line) continue;
          const payload = line.slice(5).trim();
          if (payload === '[DONE]') { buf = ''; break; }
          try {
            const obj = JSON.parse(payload);
            if (obj.error) throw new Error(obj.error);
            if (obj.chunk) { acc += obj.chunk; render(); }
          } catch (e) {
            if (e instanceof SyntaxError) { acc += payload; render(); }
            else throw e;
          }
        }
      }
      $('pgBadge').innerHTML = '<span class="badge badge-success">✓ 完成</span>';
      $('pgMeta').textContent = '流式输出 · ' + acc.length + ' 字符';
      if (!acc) output.textContent = '（模型无输出，请检查提示词或切换到 LLM 模式）';
      else output.textContent = acc; // 去掉光标
    } catch (e) {
      $('pgBadge').innerHTML = '<span class="badge badge-failed">✗ 失败</span>';
      output.textContent = '✗ ' + e.message + (acc ? '\n\n（已接收部分输出）\n' + acc : '');
    }
    setRunning(false);
  }

  // ---------- 初始化 ----------
  applyScene(scene, true);
})();
