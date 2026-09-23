const root = document.querySelector('#root');

const FINDING_TYPES = [
  ['human/correctness', '正确性'],
  ['human/security', '安全'],
  ['human/data-integrity', '数据一致性'],
  ['human/concurrency', '并发'],
  ['human/performance', '性能'],
  ['human/architecture', '架构'],
  ['human/test-gap', '测试缺口'],
  ['concurrency/no-async-foreach', '规则：异步 forEach'],
  ['security/no-eval', '规则：危险 eval']
];

const model = {
  state: null,
  activeId: '',
  activeFile: '',
  filter: 'all',
  query: '',
  humanFindings: [],
  draft: { ruleId: 'human/correctness', severity: 'medium', title: '', line: '' },
  notes: '',
  reviewer: localStorage.getItem('codelens-reviewer') ?? '',
  message: '',
  saving: false
};

const escapeHtml = (value = '') => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#039;');

const findingKey = (finding) => `${finding.ruleId}|${finding.path}|${finding.line}`;
const tone = (item) => item?.decision?.status ?? 'pending';
const formatDate = (value) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value));
const categoryFor = (ruleId) => ruleId.startsWith('human/')
  ? ruleId.slice('human/'.length).replaceAll('-', '_')
  : ruleId.split('/')[0];
const comparisonKey = (finding) => `${(finding.category ?? categoryFor(finding.ruleId)).replaceAll('-', '_')}|${finding.path}|${finding.line}`;

function filteredCases() {
  if (!model.state) return [];
  const needle = model.query.trim().toLowerCase();
  return model.state.cases.filter((item) => {
    if (model.filter !== 'all' && tone(item) !== model.filter) return false;
    return !needle || `${item.owner}/${item.repo} ${item.title} ${item.number}`.toLowerCase().includes(needle);
  });
}

function currentCase() {
  return model.state?.cases.find((item) => item.id === model.activeId) ?? filteredCases()[0];
}

function resetDraft() {
  model.draft = { ruleId: 'human/correctness', severity: 'medium', title: '', line: '' };
}

function selectCase(id) {
  model.activeId = id;
  const item = currentCase();
  model.humanFindings = structuredClone(item?.decision?.expectedFindings ?? []);
  model.notes = item?.decision?.notes ?? '';
  model.activeFile = item?.files[0]?.path ?? '';
  model.message = '';
  resetDraft();
  render();
}

function renderDiff(patch, activeLine) {
  let oldLine = 0;
  let newLine = 0;
  return patch.split('\n').map((content) => {
    const hunk = content.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
    if (hunk) {
      oldLine = Number(hunk[1]);
      newLine = Number(hunk[2]);
      return `<div class="diff-row hunk"><span class="line-number"></span><span class="line-number"></span><code>${escapeHtml(content)}</code></div>`;
    }
    let previous = '';
    let next = '';
    let kind = 'context';
    if (content.startsWith('+') && !content.startsWith('+++')) {
      next = String(newLine++);
      kind = 'add';
    } else if (content.startsWith('-') && !content.startsWith('---')) {
      previous = String(oldLine++);
      kind = 'remove';
    } else {
      previous = oldLine ? String(oldLine++) : '';
      next = newLine ? String(newLine++) : '';
    }
    const selected = kind === 'add' && String(activeLine) === next ? ' selected-line' : '';
    const attribute = kind === 'add' ? ` data-added-line="${next}"` : '';
    return `<div class="diff-row ${kind}${selected}"${attribute}><span class="line-number">${previous}</span><span class="line-number">${next}</span><code>${escapeHtml(content || ' ')}</code></div>`;
  }).join('');
}

function sidebarHtml(current) {
  const summary = model.state.summary;
  const reviewed = summary.approved + summary.deferred;
  const progress = summary.total ? Math.round(reviewed / summary.total * 100) : 0;
  const filters = [
    ['all', '全部', summary.total, '≡'],
    ['pending', '待盲标', summary.pending, '○'],
    ['approved', '已冻结', summary.approved, '✓'],
    ['deferred', '暂缓', summary.deferred, 'Ⅱ']
  ];
  return `<aside class="sidebar">
    <div class="brand"><span class="brand-mark">CL</span><span>CodeLens</span></div>
    <div class="sidebar-kicker">BLIND BENCHMARK · V1</div>
    <div class="progress-card">
      <div class="progress-label"><span>盲标进度</span><strong>${reviewed}/${summary.total}</strong></div>
      <div class="progress-track"><span style="width:${progress}%"></span></div>
      <small>${summary.approved} 已冻结 · ${summary.deferred} 暂缓</small>
    </div>
    <nav class="filters">${filters.map(([name, label, count, symbol]) => `<button data-filter="${name}" class="${model.filter === name ? 'active' : ''}"><span>${symbol}</span><span>${label}</span><b>${count}</b></button>`).join('')}</nav>
    <div class="search"><span>⌕</span><input id="search" value="${escapeHtml(model.query)}" placeholder="搜索仓库或 PR" /></div>
    <div class="case-list">${filteredCases().map((item, index) => `<button data-case="${escapeHtml(item.id)}" data-search="${escapeHtml(`${item.owner}/${item.repo} ${item.title} ${item.number}`.toLowerCase())}" class="case-card ${current?.id === item.id ? 'active' : ''}">
      <span class="case-state ${tone(item)}"></span>
      <span class="case-copy"><small>${escapeHtml(item.owner)}/${escapeHtml(item.repo)} · #${item.number}</small><strong>${escapeHtml(item.title)}</strong><em>${tone(item) === 'approved' ? '人类标签已冻结' : tone(item) === 'deferred' ? '稍后再判断' : '机器结果已隐藏'}</em></span>
      <span class="case-number">${String(index + 1).padStart(2, '0')}</span>
    </button>`).join('') || '<div class="empty-list">没有符合条件的条目</div>'}</div>
    <div class="sidebar-footer"><span>◆</span> 独立样本 · 冻结后揭示</div>
  </aside>`;
}

function humanFindingHtml(finding, index, frozen) {
  return `<div class="human-finding"><div><span><b>${escapeHtml(finding.severity ?? 'medium')}</b>${escapeHtml(FINDING_TYPES.find(([id]) => id === finding.ruleId)?.[1] ?? finding.ruleId)}</span><strong>${escapeHtml(finding.title ?? finding.ruleId)}</strong><code>${escapeHtml(finding.path)}:${finding.line}</code></div>${frozen ? '' : `<button data-remove-finding="${index}" aria-label="移除">×</button>`}</div>`;
}

function compareFindings(current) {
  const human = current.decision?.expectedFindings ?? [];
  const machine = current.machineSuggestions ?? [];
  const humanKeys = new Set(human.map(comparisonKey));
  const machineKeys = new Set(machine.map(comparisonKey));
  const matched = machine.filter((finding) => humanKeys.has(comparisonKey(finding))).length;
  const misses = human.filter((finding) => !machineKeys.has(comparisonKey(finding))).length;
  const extras = machine.filter((finding) => !humanKeys.has(comparisonKey(finding))).length;
  return { matched, misses, extras, humanKeys };
}

function pendingDecisionHtml(current, file) {
  return `<div class="guardrail"><span>◉</span><p><strong>现在是盲标阶段</strong><br />机器结果不会发送到浏览器。请只根据 PR 和 diff 独立判断。</p></div>
    <section class="blind-section"><div class="section-heading"><h3>你发现的问题</h3><span>${model.humanFindings.length} 条</span></div>
      <div class="human-list">${model.humanFindings.length ? model.humanFindings.map((finding, index) => humanFindingHtml(finding, index, false)).join('') : '<div class="empty-human">尚未添加问题。若通读后确实没有，可直接确认无问题。</div>'}</div>
      <div class="finding-form">
        <label>类型<select id="finding-type">${FINDING_TYPES.map(([id, label]) => `<option value="${id}" ${model.draft.ruleId === id ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
        <label>严重度<select id="finding-severity">${['critical', 'high', 'medium', 'low'].map((value) => `<option value="${value}" ${model.draft.severity === value ? 'selected' : ''}>${value}</option>`).join('')}</select></label>
        <label class="wide">问题标题<input id="finding-title" value="${escapeHtml(model.draft.title)}" placeholder="用一句话说明风险" /></label>
        <label>新增行号<input id="finding-line" type="number" min="1" value="${escapeHtml(model.draft.line)}" placeholder="点击左侧绿色代码行" /></label>
        <button id="add-finding" class="add-finding">＋ 添加人工问题</button>
        <small class="active-path">文件：${escapeHtml(file?.path ?? '未选择')}</small>
      </div>
    </section>`;
}

function frozenDecisionHtml(current) {
  const comparison = compareFindings(current);
  return `<div class="guardrail frozen"><span>✓</span><p><strong>人类答案已冻结</strong><br />现在才显示机器结果；本条不能再修改，防止看答案后反向调标签。</p></div>
    <section class="blind-section"><div class="section-heading"><h3>冻结的人类标签</h3><span>${model.humanFindings.length} 条</span></div>
      <div class="human-list">${model.humanFindings.length ? model.humanFindings.map((finding, index) => humanFindingHtml(finding, index, true)).join('') : '<div class="empty-human">人工判断：没有问题</div>'}</div>
    </section>
    <section class="comparison"><div class="section-heading"><h3>盲测对照</h3><span>冻结后揭示</span></div>
      <div class="comparison-summary"><b>${comparison.matched}<small>匹配</small></b><b>${comparison.misses}<small>机器漏报</small></b><b>${comparison.extras}<small>机器多报</small></b></div>
      <h4 class="machine-heading">机器预测（${current.machineSuggestions.length}）</h4>
      <div class="machine-list">${current.machineSuggestions.length ? current.machineSuggestions.map((finding) => `<div class="machine-finding ${comparison.humanKeys.has(comparisonKey(finding)) ? 'matched' : 'extra'}"><span>${comparison.humanKeys.has(comparisonKey(finding)) ? '匹配' : '多报'}</span><div><strong>${escapeHtml(finding.title)}</strong><code>${escapeHtml(finding.path)}:${finding.line}</code></div></div>`).join('') : '<div class="empty-human">机器没有预测任何问题</div>'}</div>
    </section>`;
}

function workspaceHtml(current) {
  if (!current) return '<main class="loading">没有可审阅的条目</main>';
  if (!current.files.some((file) => file.path === model.activeFile)) model.activeFile = current.files[0]?.path ?? '';
  const file = current.files.find((item) => item.path === model.activeFile) ?? current.files[0];
  const frozen = tone(current) === 'approved';
  const summary = model.state.summary;
  return `<main class="workspace">
    <header class="topbar"><div><span class="eyebrow">BLIND REVIEW · HISTORICAL PR</span><h1>${escapeHtml(current.owner)}/${escapeHtml(current.repo)} <b>#${current.number}</b></h1></div>
      <div class="top-actions"><label>审阅人<input id="reviewer" value="${escapeHtml(model.reviewer)}" placeholder="姓名或代号" /></label><button id="export" class="export" ${summary.approved !== summary.total || model.saving ? 'disabled' : ''}>⇧ 导出盲测数据</button></div></header>
    <section class="review-grid"><div class="diff-panel">
      <div class="pr-heading"><div><h2>${escapeHtml(current.title)}</h2><p>${escapeHtml(current.body || '该 PR 没有描述。')}</p></div><a href="${escapeHtml(current.sourceUrl)}" target="_blank" rel="noreferrer">在 GitHub 打开 ↗</a></div>
      <div class="file-tabs">${current.files.map((item) => `<button data-file="${escapeHtml(item.path)}" class="${item.path === file?.path ? 'active' : ''}"><span>▱</span>${escapeHtml(item.path.split('/').at(-1))}<span class="delta">+${item.additions} −${item.deletions}</span></button>`).join('')}</div>
      <div class="file-meta"><span>${escapeHtml(file?.path)}</span><span>${escapeHtml(file?.status)} · ${escapeHtml(current.repositoryLicense)} · ${formatDate(current.collectedAt)}</span></div>
      <div class="diff-scroll"><div class="diff" role="table" aria-label="代码变更">${file ? renderDiff(file.patch, model.draft.line) : ''}</div></div>
    </div><aside class="decision-panel"><div class="decision-scroll">
      <div class="decision-title"><div><span class="eyebrow">HUMAN GROUND TRUTH</span><h2>${frozen ? '查看盲测结果' : '独立标注问题'}</h2></div><span class="status-pill ${tone(current)}">${frozen ? '已冻结' : tone(current) === 'deferred' ? '暂缓' : '待盲标'}</span></div>
      ${frozen ? frozenDecisionHtml(current) : pendingDecisionHtml(current, file)}
      <label class="notes">审阅备注（可选）<textarea id="notes" ${frozen ? 'disabled' : ''} placeholder="记录判断依据或不确定点…">${escapeHtml(model.notes)}</textarea></label>
      ${model.message ? `<div class="message">${escapeHtml(model.message)}</div>` : ''}
    </div><div class="decision-actions">${frozen ? '' : `<button id="defer" class="secondary" ${model.saving ? 'disabled' : ''}>Ⅱ 暂缓</button><button id="clean" class="clean" ${model.saving ? 'disabled' : ''}>⊘ 确认无问题并冻结</button><button id="approve" class="primary" ${model.saving || !model.humanFindings.length ? 'disabled' : ''}>✓ 冻结 ${model.humanFindings.length} 条人工问题</button>`}</div>
      <div class="case-nav"><button id="previous">← 上一条</button><span>${model.state.cases.indexOf(current) + 1} / ${summary.total}</span><button id="next">下一条 →</button></div>
    </aside></section>
  </main>`;
}

function syncDraft() {
  model.draft.ruleId = document.querySelector('#finding-type')?.value ?? model.draft.ruleId;
  model.draft.severity = document.querySelector('#finding-severity')?.value ?? model.draft.severity;
  model.draft.title = document.querySelector('#finding-title')?.value ?? model.draft.title;
  model.draft.line = document.querySelector('#finding-line')?.value ?? model.draft.line;
  model.notes = document.querySelector('#notes')?.value ?? model.notes;
}

function addFinding() {
  syncDraft();
  const line = Number(model.draft.line);
  if (!model.draft.title.trim() || !Number.isInteger(line) || line < 1) {
    model.message = '请填写问题标题，并点击左侧绿色新增行选择证据位置。';
    render();
    return;
  }
  const finding = { ruleId: model.draft.ruleId, category: categoryFor(model.draft.ruleId), severity: model.draft.severity, title: model.draft.title.trim(), path: model.activeFile, line };
  if (model.humanFindings.some((item) => findingKey(item) === findingKey(finding))) {
    model.message = '同一类型、文件和行号已经添加。';
    render();
    return;
  }
  model.humanFindings.push(finding);
  resetDraft();
  model.message = '';
  render();
}

function bindEvents() {
  document.querySelectorAll('[data-filter]').forEach((button) => button.addEventListener('click', () => { model.filter = button.dataset.filter; render(); }));
  document.querySelectorAll('[data-case]').forEach((button) => button.addEventListener('click', () => selectCase(button.dataset.case)));
  document.querySelectorAll('[data-file]').forEach((button) => button.addEventListener('click', () => { syncDraft(); model.activeFile = button.dataset.file; model.draft.line = ''; render(); }));
  document.querySelectorAll('[data-added-line]').forEach((row) => row.addEventListener('click', () => { syncDraft(); model.draft.line = row.dataset.addedLine; render(); }));
  document.querySelectorAll('[data-remove-finding]').forEach((button) => button.addEventListener('click', () => { syncDraft(); model.humanFindings.splice(Number(button.dataset.removeFinding), 1); render(); }));
  document.querySelector('#add-finding')?.addEventListener('click', addFinding);
  document.querySelector('#search')?.addEventListener('input', (event) => { model.query = event.target.value; render(); });
  document.querySelector('#reviewer')?.addEventListener('input', (event) => { model.reviewer = event.target.value; localStorage.setItem('codelens-reviewer', model.reviewer); });
  document.querySelector('#notes')?.addEventListener('input', (event) => { model.notes = event.target.value; });
  document.querySelector('#defer')?.addEventListener('click', () => saveDecision('deferred', []));
  document.querySelector('#clean')?.addEventListener('click', () => saveDecision('approved', []));
  document.querySelector('#approve')?.addEventListener('click', () => saveDecision('approved', model.humanFindings));
  document.querySelector('#export')?.addEventListener('click', exportDataset);
  const index = model.state.cases.indexOf(currentCase());
  document.querySelector('#previous')?.addEventListener('click', () => selectCase(model.state.cases[Math.max(0, index - 1)].id));
  document.querySelector('#next')?.addEventListener('click', () => selectCase(model.state.cases[Math.min(model.state.cases.length - 1, index + 1)].id));
}

function render() {
  if (!model.state) { root.innerHTML = '<main class="loading">✦ 正在准备盲测队列…</main>'; return; }
  const current = currentCase();
  root.innerHTML = `<div class="shell">${sidebarHtml(current)}${workspaceHtml(current)}</div>`;
  bindEvents();
}

async function loadState(preferId) {
  const response = await fetch('/api/state');
  if (!response.ok) throw new Error('无法加载盲测队列');
  model.state = await response.json();
  model.activeId = preferId ?? (model.activeId || model.state.cases[0]?.id || '');
  if (!currentCase()) model.activeId = model.state.cases[0]?.id ?? '';
}

async function saveDecision(status, findings) {
  const item = currentCase();
  syncDraft();
  if (!model.reviewer.trim()) { model.message = '请先填写审阅人姓名或代号。'; render(); return; }
  model.saving = true;
  model.message = '';
  render();
  try {
    const response = await fetch(`/api/decisions/${encodeURIComponent(item.id)}`, {
      method: 'PUT', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ status, reviewer: model.reviewer.trim(), expectedFindings: findings, ...(model.notes.trim() ? { notes: model.notes.trim() } : {}) })
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error ?? '保存失败');
    await loadState(item.id);
    selectCase(item.id);
    model.message = status === 'approved' ? '人类答案已冻结，机器结果现已揭示。' : '已暂缓；机器结果仍然隐藏。';
  } catch (error) {
    model.message = error instanceof Error ? error.message : String(error);
  } finally {
    model.saving = false;
    render();
  }
}

async function exportDataset() {
  if (!confirm('确认导出全部已冻结的盲测标签？')) return;
  model.saving = true;
  render();
  try {
    const response = await fetch('/api/export', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ reviewer: model.reviewer.trim() }) });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error ?? '导出失败');
    model.message = `已导出：${result.outputPath}`;
  } catch (error) {
    model.message = error instanceof Error ? error.message : String(error);
  } finally {
    model.saving = false;
    render();
  }
}

render();
loadState().then(() => selectCase(model.state.cases[0]?.id ?? '')).catch((error) => { root.innerHTML = `<main class="loading">${escapeHtml(error instanceof Error ? error.message : String(error))}</main>`; });
