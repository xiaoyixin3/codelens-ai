const root = document.querySelector('#root');
const model = {
  state: null,
  activeId: '',
  activeFile: '',
  filter: 'all',
  query: '',
  selected: new Set(),
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
const icon = (symbol, label = '') => `<span aria-hidden="true">${symbol}</span><span class="sr-only">${escapeHtml(label)}</span>`;

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

function selectCase(id) {
  model.activeId = id;
  const item = currentCase();
  model.selected = new Set((item?.decision?.expectedFindings ?? []).map(findingKey));
  model.notes = item?.decision?.notes ?? '';
  model.activeFile = item?.suggestions[0]?.path ?? item?.files[0]?.path ?? '';
  model.message = '';
  render();
}

function renderDiff(patch) {
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
    return `<div class="diff-row ${kind}"><span class="line-number">${previous}</span><span class="line-number">${next}</span><code>${escapeHtml(content || ' ')}</code></div>`;
  }).join('');
}

function sidebarHtml(current) {
  const summary = model.state.summary;
  const progress = Math.round(summary.approved / summary.total * 100);
  const filters = [
    ['all', '全部', summary.total, '≡'],
    ['pending', '待审阅', summary.pending, '○'],
    ['approved', '已批准', summary.approved, '✓'],
    ['deferred', '暂缓', summary.deferred, 'Ⅱ']
  ];
  const list = filteredCases();
  return `<aside class="sidebar">
    <div class="brand"><span class="brand-mark">CL</span><span>CodeLens</span></div>
    <div class="sidebar-kicker">BENCHMARK REVIEW</div>
    <div class="progress-card">
      <div class="progress-label"><span>人工进度</span><strong>${summary.approved}/${summary.total}</strong></div>
      <div class="progress-track"><span style="width:${progress}%"></span></div>
      <small>${progress}% 已批准 · ${summary.deferred} 暂缓</small>
    </div>
    <nav class="filters" aria-label="评测筛选">${filters.map(([name, label, count, symbol]) => `
      <button data-filter="${name}" class="${model.filter === name ? 'active' : ''}"><span>${symbol}</span><span>${label}</span><b>${count}</b></button>`).join('')}
    </nav>
    <div class="search"><span>⌕</span><input id="search" value="${escapeHtml(model.query)}" placeholder="搜索仓库或 PR" /></div>
    <div class="case-list">${list.map((item, index) => `
      <button data-case="${escapeHtml(item.id)}" data-search="${escapeHtml(`${item.owner}/${item.repo} ${item.title} ${item.number}`.toLowerCase())}" class="case-card ${current?.id === item.id ? 'active' : ''}">
        <span class="case-state ${tone(item)}"></span>
        <span class="case-copy"><small>${escapeHtml(item.owner)}/${escapeHtml(item.repo)} · #${item.number}</small><strong>${escapeHtml(item.title)}</strong><em>${item.suggestions.length ? `${item.suggestions.length} 条机器建议` : '未发现规则命中'}</em></span>
        <span class="case-number">${String(index + 1).padStart(2, '0')}</span>
      </button>`).join('') || '<div class="empty-list">没有符合筛选条件的条目</div>'}</div>
    <div class="sidebar-footer"><span>◆</span> 仅本机 · 人工门禁</div>
  </aside>`;
}

function workspaceHtml(current) {
  if (!current) return '<main class="loading">没有可审阅的条目</main>';
  if (!current.files.some((file) => file.path === model.activeFile)) model.activeFile = current.suggestions[0]?.path ?? current.files[0]?.path ?? '';
  const file = current.files.find((item) => item.path === model.activeFile) ?? current.files[0];
  const statusLabel = tone(current) === 'approved' ? '已批准' : tone(current) === 'deferred' ? '暂缓' : '待审阅';
  const summary = model.state.summary;
  return `<main class="workspace">
    <header class="topbar">
      <div><span class="eyebrow">HISTORICAL PR</span><h1>${escapeHtml(current.owner)}/${escapeHtml(current.repo)} <b>#${current.number}</b></h1></div>
      <div class="top-actions"><label>审阅人<input id="reviewer" value="${escapeHtml(model.reviewer)}" placeholder="姓名或代号" /></label>
        <button id="export" class="export" ${summary.approved !== summary.total || model.saving ? 'disabled' : ''}>${icon('⇧')} 导出门禁数据</button></div>
    </header>
    <section class="review-grid">
      <div class="diff-panel">
        <div class="pr-heading"><div><h2>${escapeHtml(current.title)}</h2><p>${escapeHtml(current.body || '该 PR 没有描述。')}</p></div>
          <a href="${escapeHtml(current.sourceUrl)}" target="_blank" rel="noreferrer">${icon('◉')} 在 GitHub 打开 ↗</a></div>
        <div class="file-tabs">${current.files.map((item) => `<button data-file="${escapeHtml(item.path)}" class="${item.path === file?.path ? 'active' : ''}"><span>▱</span>${escapeHtml(item.path.split('/').at(-1))}<span class="delta">+${item.additions} −${item.deletions}</span></button>`).join('')}</div>
        <div class="file-meta"><span>${escapeHtml(file?.path)}</span><span>${escapeHtml(file?.status)} · ${escapeHtml(current.repositoryLicense)} · 采集于 ${formatDate(current.collectedAt)}</span></div>
        <div class="diff-scroll"><div class="diff" role="table" aria-label="代码变更">${file ? renderDiff(file.patch) : ''}</div></div>
      </div>
      <aside class="decision-panel">
        <div class="decision-title"><div><span class="eyebrow">HUMAN DECISION</span><h2>核验机器建议</h2></div><span class="status-pill ${tone(current)}">${statusLabel}</span></div>
        <div class="guardrail"><span>◆</span><p><strong>机器建议不是标签</strong><br />请阅读变更证据后，再确认或驳回。</p></div>
        <div class="suggestion-list">${current.suggestions.length ? current.suggestions.map((finding) => {
          const key = findingKey(finding);
          const checked = model.selected.has(key);
          return `<button data-finding="${escapeHtml(key)}" class="finding ${checked ? 'selected' : ''}"><span class="check-box">${checked ? '✓' : ''}</span><span class="finding-copy"><span><b>${escapeHtml(finding.severity)}</b>${escapeHtml(finding.category)}</span><strong>${escapeHtml(finding.title)}</strong><p>${escapeHtml(finding.claim)}</p><code>${escapeHtml(finding.path)}:${finding.line}</code></span></button>`;
        }).join('') : '<div class="clean-suggestion"><span class="clean-icon">✓</span><strong>未发现规则命中</strong><p>仍需人工通读 diff，确认没有应标注的问题。</p></div>'}</div>
        <label class="notes">审阅备注（可选）<textarea id="notes" placeholder="记录为什么接受、驳回或暂缓…">${escapeHtml(model.notes)}</textarea></label>
        ${model.message ? `<div class="message">${escapeHtml(model.message)}</div>` : ''}
        <div class="decision-actions">
          <button id="defer" class="secondary" ${model.saving ? 'disabled' : ''}>Ⅱ 暂缓</button>
          <button id="clean" class="clean" ${model.saving ? 'disabled' : ''}>⊘ 确认无问题</button>
          <button id="approve" class="primary" ${model.saving || !model.selected.size ? 'disabled' : ''}>✓ 确认所选问题</button>
        </div>
        <div class="case-nav"><button id="previous">← 上一条</button><span>${model.state.cases.indexOf(current) + 1} / ${summary.total}</span><button id="next">下一条 →</button></div>
      </aside>
    </section>
  </main>`;
}

function bindEvents() {
  document.querySelectorAll('[data-filter]').forEach((button) => button.addEventListener('click', () => {
    model.filter = button.dataset.filter;
    render();
  }));
  document.querySelectorAll('[data-case]').forEach((button) => button.addEventListener('click', () => selectCase(button.dataset.case)));
  document.querySelectorAll('[data-file]').forEach((button) => button.addEventListener('click', () => { model.activeFile = button.dataset.file; render(); }));
  document.querySelectorAll('[data-finding]').forEach((button) => button.addEventListener('click', () => {
    const key = button.dataset.finding;
    model.selected.has(key) ? model.selected.delete(key) : model.selected.add(key);
    const finding = currentCase()?.suggestions.find((item) => findingKey(item) === key);
    if (finding) model.activeFile = finding.path;
    render();
  }));
  document.querySelector('#search')?.addEventListener('input', (event) => {
    model.query = event.target.value;
    const needle = model.query.trim().toLowerCase();
    document.querySelectorAll('[data-case]').forEach((button) => {
      button.hidden = Boolean(needle) && !button.dataset.search.includes(needle);
    });
  });
  document.querySelector('#reviewer')?.addEventListener('input', (event) => {
    model.reviewer = event.target.value;
    localStorage.setItem('codelens-reviewer', model.reviewer);
  });
  document.querySelector('#notes')?.addEventListener('input', (event) => { model.notes = event.target.value; });
  document.querySelector('#defer')?.addEventListener('click', () => saveDecision('deferred', []));
  document.querySelector('#clean')?.addEventListener('click', () => saveDecision('approved', []));
  document.querySelector('#approve')?.addEventListener('click', () => {
    const item = currentCase();
    saveDecision('approved', item.suggestions.filter((finding) => model.selected.has(findingKey(finding))));
  });
  document.querySelector('#export')?.addEventListener('click', exportDataset);
  const index = model.state.cases.indexOf(currentCase());
  document.querySelector('#previous')?.addEventListener('click', () => selectCase(model.state.cases[Math.max(0, index - 1)].id));
  document.querySelector('#next')?.addEventListener('click', () => selectCase(model.state.cases[Math.min(model.state.cases.length - 1, index + 1)].id));
}

function render() {
  if (!model.state) {
    root.innerHTML = '<main class="loading">✦ 正在准备人工评测队列…</main>';
    return;
  }
  const current = currentCase();
  root.innerHTML = `<div class="shell">${sidebarHtml(current)}${workspaceHtml(current)}</div>`;
  bindEvents();
}

async function loadState(preferId) {
  const response = await fetch('/api/state');
  if (!response.ok) throw new Error('无法加载评测队列');
  model.state = await response.json();
  model.activeId = preferId ?? (model.activeId || model.state.cases[0]?.id || '');
  if (!currentCase()) model.activeId = model.state.cases[0]?.id ?? '';
}

async function saveDecision(status, findings) {
  const item = currentCase();
  if (!model.reviewer.trim()) {
    model.message = '请先填写审阅人姓名或代号。';
    render();
    return;
  }
  model.saving = true;
  model.message = '';
  render();
  try {
    const response = await fetch(`/api/decisions/${encodeURIComponent(item.id)}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        status,
        reviewer: model.reviewer.trim(),
        expectedFindings: findings.map(({ ruleId, path, line }) => ({ ruleId, path, line })),
        ...(model.notes.trim() ? { notes: model.notes.trim() } : {})
      })
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error ?? '保存失败');
    const list = filteredCases();
    const nextId = list[list.findIndex((candidate) => candidate.id === item.id) + 1]?.id ?? item.id;
    await loadState(nextId);
    selectCase(nextId);
    model.message = status === 'approved' ? '人工结论已保存。' : '已暂缓，稍后可以继续。';
  } catch (error) {
    model.message = error instanceof Error ? error.message : String(error);
  } finally {
    model.saving = false;
    render();
  }
}

async function exportDataset() {
  if (!confirm('确认导出 100 条已人工批准的历史 PR 数据集？')) return;
  model.saving = true;
  render();
  try {
    const response = await fetch('/api/export', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reviewer: model.reviewer.trim() })
    });
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
loadState().then(() => selectCase(model.state.cases[0]?.id ?? '')).catch((error) => {
  root.innerHTML = `<main class="loading">${escapeHtml(error instanceof Error ? error.message : String(error))}</main>`;
});
