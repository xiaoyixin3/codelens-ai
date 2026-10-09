const root = document.querySelector('#root');

const CATEGORIES = [
  ['correctness', '正确性'], ['security', '安全'], ['data_integrity', '数据一致性'],
  ['concurrency', '并发'], ['performance', '性能'], ['architecture', '架构'], ['test_gap', '测试缺口']
];
const EVIDENCE_KINDS = [
  ['diff', '变更行'], ['base_source', 'Base 全文'], ['head_source', 'Head 全文'],
  ['symbol', '符号定义'], ['caller', '调用方'], ['test', '测试'], ['config', '配置'],
  ['build', '构建结果'], ['execution', '执行结果']
];
const emptyDraft = () => ({
  rootCauseId: '', category: 'correctness', severity: 'medium', claim: '', trigger: '', impact: '',
  affectedSymbols: '', acceptableFix: '', verification: '', confidence: 'likely', reviewerUncertainty: '',
  evidence: { kind: 'diff', path: '', revision: 'head', side: 'RIGHT', startLine: '', endLine: '', symbol: '', fact: '' }
});
const model = {
  state: null, activeId: '', activeFile: '', activeContextKey: '', view: 'overview', filter: 'all', query: '',
  rootCauses: [], draft: emptyDraft(), notes: '', reviewer: localStorage.getItem('codelens-reviewer') ?? '',
  message: '', saving: false, solutionOptions: {}, localInput: { repository: '', base: 'HEAD^', head: 'HEAD' }, localLoading: false, localError: ''
};

const escapeHtml = (value = '') => String(value)
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;').replaceAll("'", '&#039;');
const tone = (item) => item?.decision?.status ?? 'pending';
const contextKey = (file) => `${file.revision}:${file.path}`;

function filteredCases() {
  if (!model.state) return [];
  const needle = model.query.trim().toLowerCase();
  return model.state.cases.filter((item) => {
    if (model.filter !== 'all' && tone(item) !== model.filter) return false;
    return !needle || `${item.owner}/${item.repo} ${item.title} ${item.number}`.toLowerCase().includes(needle);
  });
}
function currentCase() { return model.state?.cases.find((item) => item.id === model.activeId) ?? filteredCases()[0]; }
function selectCase(id) {
  model.activeId = id;
  const item = currentCase();
  model.rootCauses = structuredClone(item?.decision?.rootCauses ?? []);
  model.notes = item?.decision?.notes ?? '';
  model.activeFile = item?.files[0]?.path ?? '';
  model.activeContextKey = item?.contextPacket?.files?.[0] ? contextKey(item.contextPacket.files[0]) : '';
  model.view = 'overview'; model.draft = emptyDraft(); model.message = ''; render();
}

function renderDiff(patch, selectedEvidence) {
  let oldLine = 0; let newLine = 0;
  return patch.split('\n').map((content) => {
    const hunk = content.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
    if (hunk) {
      oldLine = Number(hunk[1]); newLine = Number(hunk[2]);
      return `<div class="diff-row hunk"><span class="line-number"></span><span class="line-number"></span><code>${escapeHtml(content)}</code></div>`;
    }
    let previous = ''; let next = ''; let kind = 'context'; let side = '';
    if (content.startsWith('+') && !content.startsWith('+++')) { next = String(newLine++); kind = 'add'; side = 'RIGHT'; }
    else if (content.startsWith('-') && !content.startsWith('---')) { previous = String(oldLine++); kind = 'remove'; side = 'LEFT'; }
    else { previous = oldLine ? String(oldLine++) : ''; next = newLine ? String(newLine++) : ''; }
    const line = side === 'RIGHT' ? next : previous;
    const selected = side && selectedEvidence.kind === 'diff' && selectedEvidence.side === side && String(selectedEvidence.startLine) === line ? ' selected-line' : '';
    const attribute = side ? ` data-diff-line="${line}" data-diff-side="${side}"` : '';
    return `<div class="diff-row ${kind}${selected}"${attribute}><span class="line-number">${previous}</span><span class="line-number">${next}</span><code>${escapeHtml(content || ' ')}</code></div>`;
  }).join('');
}
function renderSource(file, selectedEvidence) {
  if (!file) return '<div class="empty-human">请选择上下文文件。</div>';
  return file.content.split(/\r?\n/).map((content, index) => {
    const line = index + 1;
    const selected = selectedEvidence.path === file.path && selectedEvidence.revision === file.revision && Number(selectedEvidence.startLine) === line ? ' selected-line' : '';
    return `<div class="source-row${selected}" data-context-line="${line}"><span class="line-number">${line}</span><code>${escapeHtml(content || ' ')}</code></div>`;
  }).join('');
}

function sidebarHtml(current) {
  if (model.state.localMode) return `<aside class="sidebar"><div class="brand"><span class="brand-mark">CL</span>CodeLens 本地版</div><div class="sidebar-kicker">JAVA · LOCAL REVIEW</div><div class="progress-card"><strong>先理解，再修改</strong><p>总览 → 行为变化 → 调用方与测试 → 复用方案</p><small>无需 GitHub App、数据库或人工标注。</small></div><div class="case-list">${current ? `<button class="case-card active"><span></span><span class="case-copy"><small>${escapeHtml(current.repo)}</small><strong>${escapeHtml(current.title)}</strong><em>只读 · 已提交版本</em></span></button>` : '<div class="empty-list">填写本地 Git 仓库开始</div>'}</div><div class="sidebar-footer">仅本机访问 · 不自动改代码</div></aside>`;
  const summary = model.state.summary; const reviewed = summary.frozen + summary.deferred;
  const progress = summary.total ? Math.round(reviewed / summary.total * 100) : 0;
  const filters = [['all', '全部', summary.total, '≡'], ['pending', '待判断', summary.pending, '○'], ['frozen', '已冻结', summary.frozen, '✓'], ['deferred', '暂缓', summary.deferred, 'Ⅱ']];
  return `<aside class="sidebar"><div class="brand"><span class="brand-mark">CL</span><span>CodeLens</span></div>
    <div class="sidebar-kicker">REVIEW REASONING · ${escapeHtml(model.state.mode.toUpperCase())}</div>
    <div class="progress-card"><div class="progress-label"><span>根因审阅进度</span><strong>${reviewed}/${summary.total}</strong></div><div class="progress-track"><span style="width:${progress}%"></span></div><small>${summary.contextReady}/${summary.total} 已有冻结上下文</small></div>
    <nav class="filters">${filters.map(([name, label, count, symbol]) => `<button data-filter="${name}" class="${model.filter === name ? 'active' : ''}"><span>${symbol}</span><span>${label}</span><b>${count}</b></button>`).join('')}</nav>
    <div class="search"><span>⌕</span><input id="search" value="${escapeHtml(model.query)}" placeholder="搜索仓库或 PR" /></div>
    <div class="case-list">${filteredCases().map((item, index) => `<button data-case="${escapeHtml(item.id)}" class="case-card ${current?.id === item.id ? 'active' : ''}"><span class="case-state ${tone(item)}"></span><span class="case-copy"><small>${escapeHtml(item.owner)}/${escapeHtml(item.repo)} · #${item.number}</small><strong>${escapeHtml(item.title)}</strong><em>${item.contextPacket.available ? '中立上下文已冻结' : '缺少上下文，不能冻结金标'}</em></span><span class="case-number">${String(index + 1).padStart(2, '0')}</span></button>`).join('') || '<div class="empty-list">没有符合条件的条目</div>'}</div>
    <div class="sidebar-footer"><span>◆</span> 根因级证据 · reasoning-v1</div></aside>`;
}

function changePanelHtml(current) {
  const file = current.files.find((item) => item.path === model.activeFile) ?? current.files[0];
  return `<div class="file-tabs">${current.files.map((item) => `<button data-file="${escapeHtml(item.path)}" class="${item.path === file?.path ? 'active' : ''}"><span>▱</span>${escapeHtml(item.path.split('/').at(-1))}<span class="delta">+${item.additions} −${item.deletions}</span></button>`).join('')}</div>
    <div class="file-meta"><span>${escapeHtml(file?.path)}</span><span>${escapeHtml(file?.status)} · 点击红色或绿色行添加证据</span></div>
    <div class="diff-scroll"><div class="diff" role="table" aria-label="代码变更">${file ? renderDiff(file.patch, model.draft.evidence) : ''}</div></div>`;
}
function contextPanelHtml(current) {
  const packet = current.contextPacket;
  if (!packet.available) return `<div class="context-missing"><h3>冻结上下文不可用</h3><p>${escapeHtml(packet.limitations.join('；'))}</p><p>可以暂缓该样本，但正式金标不能在 diff-only 状态下冻结。</p></div>`;
  const files = packet.files; const active = files.find((file) => contextKey(file) === model.activeContextKey) ?? files[0];
  const symbols = packet.symbols.filter((symbol) => symbol.path === active?.path && symbol.revision === active?.revision);
  const symbolIds = new Set(symbols.map((symbol) => symbol.id));
  const related = packet.relationships.filter((edge) => symbolIds.has(edge.fromSymbolId) || symbolIds.has(edge.toSymbolId));
  const byId = new Map(packet.symbols.map((symbol) => [symbol.id, symbol]));
  return `<div class="context-layout"><aside class="context-files"><div class="context-meta">PACKET ${escapeHtml(packet.packetId)}<small>${escapeHtml(packet.digest.slice(0, 12))}…</small></div>${files.map((file) => `<button data-context-file="${escapeHtml(contextKey(file))}" class="${contextKey(file) === contextKey(active) ? 'active' : ''}"><span>${file.role === 'test' ? 'T' : file.revision === 'base' ? 'B' : 'H'}</span><div><strong>${escapeHtml(file.path)}</strong><small>${escapeHtml(file.revision)} · ${escapeHtml(file.role)}</small></div></button>`).join('')}</aside>
    <section class="context-source"><div class="file-meta"><span>${escapeHtml(active?.path)}</span><span>${escapeHtml(active?.revision)} · 点击行添加跨文件证据</span></div><div class="source-scroll">${renderSource(active, model.draft.evidence)}</div></section>
    <aside class="context-relations"><h4>符号定义</h4>${symbols.length ? symbols.map((symbol) => `<button data-symbol-line="${symbol.startLine}" data-symbol-name="${escapeHtml(symbol.id)}"><strong>${escapeHtml(symbol.name)}</strong><small>${escapeHtml(symbol.kind)} · L${symbol.startLine}-${symbol.endLine}</small></button>`).join('') : '<p>该文件没有已索引符号。</p>'}<h4>调用方与测试关系</h4>${related.length ? related.map((edge) => `<div class="relation"><b>${escapeHtml(edge.type)}</b><span>${escapeHtml(byId.get(edge.fromSymbolId)?.name ?? edge.fromSymbolId)} → ${escapeHtml(byId.get(edge.toSymbolId)?.name ?? edge.toSymbolId)}</span><small>${escapeHtml(edge.evidencePath)}:${edge.evidenceLine}</small></div>`).join('') : '<p>冻结包没有该文件的关系记录。</p>'}</aside></div>`;
}

function evidenceChipHtml(current, evidenceId) {
  const evidence = current.changeBrief.evidence.find((item) => item.id === evidenceId);
  if (!evidence) return '';
  const location = evidence.path ? `${evidence.path}${evidence.startLine ? `:${evidence.startLine}` : ''}` : evidence.kind.replace('_', ' ');
  return `<button class="evidence-chip" data-brief-evidence="${escapeHtml(evidence.id)}" title="${escapeHtml(evidence.label)}">↗ ${escapeHtml(location)}</button>`;
}
function statementHtml(current, statement) {
  return `<div class="traceable-statement"><span class="epistemic ${statement.epistemicStatus}">${statement.epistemicStatus === 'fact' ? '事实' : statement.epistemicStatus === 'inference' ? '推断' : '未知'}</span><p>${escapeHtml(statement.text)}</p><div class="evidence-chips">${statement.evidenceIds.map((id) => evidenceChipHtml(current, id)).join('')}</div></div>`;
}
function questionHtml(current, question, index) {
  return `<article class="guided-question"><span>${String(index + 1).padStart(2, '0')}</span><div><strong>${escapeHtml(question.question)}</strong><p>${escapeHtml(question.purpose)}</p><div class="evidence-chips">${question.evidenceIds.slice(0, 4).map((id) => evidenceChipHtml(current, id)).join('')}</div></div></article>`;
}
function overviewPanelHtml(current) {
  const brief = current.changeBrief; const coverage = brief.coverage;
  const ready = current.contextPacket.available;
  return `<div class="overview-scroll"><section class="merge-verdict ${ready ? 'ready' : 'limited'}"><div><span>${ready ? 'READY FOR STRUCTURED REVIEW' : 'CONTEXT LIMITED'}</span><h3>${ready ? '上下文已冻结，可以按行为开始审阅' : '只能查看 Diff；正式金标应暂缓'}</h3></div><b>${coverage.level === 'semantic' ? 'SEMANTIC' : 'DIFF-ONLY'}</b></section>
    <section class="brief-section"><div class="brief-heading"><div><span class="eyebrow">WHAT IS THIS CHANGE?</span><h3>意图与证据</h3></div><small>每句话必须可追溯或标记未知</small></div>${statementHtml(current, brief.intent)}</section>
    <section class="coverage-strip"><div><strong>${coverage.changedFiles}</strong><span>变更文件</span></div><div><strong>${coverage.contextualFiles}</strong><span>上下文文件</span></div><div><strong>${coverage.indexedSymbols}</strong><span>索引符号</span></div><div><strong>${coverage.relationships}</strong><span>语义关系</span></div></section>
    <section class="brief-section"><div class="brief-heading"><div><span class="eyebrow">REVIEW LAYERS</span><h3>按行为组织的变更地图</h3></div><button data-view="behavior">查看全部行为卡 →</button></div><div class="behavior-preview">${brief.behaviorCards.map((card, index) => `<button data-open-behavior="${index}"><span>0${index + 1}</span><div><strong>${escapeHtml(card.title)}</strong><small>${escapeHtml(card.summary.text)}</small><em>${card.unchangedCallers.length} 调用方 · ${card.relatedTests.length} 测试</em></div></button>`).join('')}</div></section>
    <section class="brief-section attention"><div class="brief-heading"><div><span class="eyebrow">CHECK FIRST</span><h3>先回答这些中立问题</h3></div><small>不是机器结论</small></div><div class="question-list">${brief.questions.map((question, index) => questionHtml(current, question, index)).join('')}</div></section>
    ${coverage.limitations.length ? `<section class="coverage-limitations"><strong>覆盖限制</strong>${coverage.limitations.map((item) => `<p>• ${escapeHtml(item)}</p>`).join('')}</section>` : ''}</div>`;
}
function behaviorPanelHtml(current) {
  return `<div class="behavior-scroll">${current.changeBrief.behaviorCards.map((card, index) => `<article class="behavior-card" data-behavior-index="${index}"><header><span>BEHAVIOR ${String(index + 1).padStart(2, '0')}</span><h3>${escapeHtml(card.title)}</h3><p>${escapeHtml(card.summary.text)}</p></header><div class="behavior-columns"><section><h4>Before</h4>${card.before.map((statement) => statementHtml(current, statement)).join('')}</section><section><h4>After</h4>${card.after.map((statement) => statementHtml(current, statement)).join('')}</section></div><div class="scope-grid"><div><span>变更文件</span>${card.changedFiles.map((item) => `<button data-scope-path="${escapeHtml(item)}">${escapeHtml(item)}</button>`).join('')}</div><div><span>核心符号</span>${card.coreSymbols.length ? card.coreSymbols.map((item) => `<code>${escapeHtml(item)}</code>`).join('') : '<small>语义索引未提供</small>'}</div><div><span>未修改调用方</span>${card.unchangedCallers.length ? card.unchangedCallers.map((item) => `<code>${escapeHtml(item)}</code>`).join('') : '<small>未建立关系证据</small>'}</div><div><span>相关测试</span>${card.relatedTests.length ? card.relatedTests.map((item) => `<button data-scope-path="${escapeHtml(item)}">${escapeHtml(item)}</button>`).join('') : '<small>未发现关联测试证据</small>'}</div></div><section class="card-questions"><h4>Reviewer 检查清单</h4>${card.questions.map((question, questionIndex) => questionHtml(current, question, questionIndex)).join('')}</section></article>`).join('')}</div>`;
}
function reusePanelHtml(current) {
  const investigations = current.reuseInvestigations ?? [];
  if (!investigations.length) return '<div class="context-missing"><h3>复用调查仅在辅助模式可用</h3><p>正式金标不会显示候选、决策或方案，避免预测泄漏。</p></div>';
  return `<div class="reuse-scroll"><section class="reuse-principle"><div><span>REUSE BEFORE REWRITE</span><h3>先证明为什么复用、扩展、抽取或新建</h3></div><b>PATCH GATE</b></section>${investigations.map((investigation, index) => { const options = model.solutionOptions[`${current.id}:${investigation.behaviorId}`] ?? []; return `<article class="reuse-card"><header><span>BEHAVIOR ${String(index + 1).padStart(2, '0')}</span><h3>${escapeHtml(investigation.goal)}</h3><p>已检索 ${investigation.searchScope.searchedSymbols} 个未修改符号、${investigation.searchScope.searchedRelationships} 条关系 · ${investigation.searchScope.semanticCoverageComplete ? '语义覆盖完整' : '覆盖不足'}</p></header><section class="candidate-list">${investigation.candidates.length ? investigation.candidates.map((candidate) => `<article><div class="candidate-score">${Math.round(candidate.score * 100)}</div><div><span>${escapeHtml(candidate.relationship)} · ${escapeHtml(candidate.fit)}</span><strong>${escapeHtml(candidate.symbolName)}</strong><code>${escapeHtml(candidate.path)}</code><p>${escapeHtml(candidate.rationale)}</p><div class="candidate-actions"><div class="evidence-chips">${candidate.evidenceIds.map((id) => evidenceChipHtml(current, id)).join('')}</div>${candidate.fit !== 'rejected' ? `<button data-build-solution="${escapeHtml(investigation.behaviorId)}" data-candidate-id="${escapeHtml(candidate.id)}">基于此候选生成方案</button>` : ''}</div></div></article>`).join('') : '<div class="empty-human">当前冻结范围没有可验证候选；这不等于仓库中不存在可复用实现。</div>'}</section>${options.length ? `<section class="solution-options"><div class="solution-heading"><span>SOLUTION OPTIONS</span><h4>可审计方案，不是可直接提交的补丁</h4></div>${options.map((option, optionIndex) => `<article><b>0${optionIndex + 1}</b><div><span>${escapeHtml(option.strategy)}</span><strong>${escapeHtml(option.title)}</strong><p>${escapeHtml(option.summary)}</p><small>预计范围：${escapeHtml(option.expectedFiles.join('、'))}</small><small>验证：${escapeHtml(option.verification.join('；'))}</small></div></article>`).join('')}</section>` : ''}<footer class="patch-gate ${options.length ? 'decision-ready' : ''}"><strong>${options.length ? '决策已验证，补丁仍阻断' : '补丁已阻断'}</strong><div>${options.length ? '<p>✓ ReuseDecision 已通过服务端证据、候选和 change budget 校验。</p><p>• 方案尚未经过人工批准、局部补丁生成和验证器检查。</p>' : investigation.patchGate.reasons.map((reason) => `<p>• ${escapeHtml(reason)}</p>`).join('')}</div></footer></article>`; }).join('')}</div>`;
}

function rootCauseHtml(item, index, frozen) {
  const evidence = item.evidence.map((entry) => `${entry.kind} · ${entry.revision}:${entry.path}:${entry.startLine}-${entry.endLine}`).join('；');
  return `<article class="root-cause-card"><div><span><b>${escapeHtml(item.severity)}</b>${escapeHtml(item.category)} · ${escapeHtml(item.confidence)}</span><strong>${escapeHtml(item.claim)}</strong><details><summary>查看触发、影响、证据与解决方向</summary><small>触发：${escapeHtml(item.trigger)}</small><small>影响：${escapeHtml(item.impact)}</small><code>${escapeHtml(evidence)}</code>${item.acceptableFix ? `<small>可接受修复：${escapeHtml(item.acceptableFix)}</small>` : ''}<small>验证：${escapeHtml(item.verification)}</small></details></div>${frozen ? '' : `<button data-remove-root="${index}" aria-label="移除">×</button>`}</article>`;
}
function legacyHtml(current) {
  if (!current.legacyDrafts?.length) return '';
  return `<section class="legacy-drafts"><h4>旧 blind-v1 标签仅作为草稿</h4><p>旧数据缺少触发条件、影响、验证方法和置信度，必须补全后才能冻结。</p>${current.legacyDrafts.map((draft, index) => `<button data-import-legacy="${index}"><strong>${escapeHtml(draft.claim)}</strong><small>${escapeHtml(draft.evidence[0].path)}:${draft.evidence[0].startLine} · 缺少 ${escapeHtml(draft.incompleteFields.join(', '))}</small></button>`).join('')}</section>`;
}
function assistanceHtml(current) {
  if (model.state.mode !== 'assisted') return '';
  return `<section class="assistance"><div class="section-heading"><h3>机器线索</h3><span>仅辅助模式</span></div>${current.assistance?.length ? current.assistance.map((finding, index) => `<article><div><strong>${escapeHtml(finding.title)}</strong><p>${escapeHtml(finding.claim)}</p><small>建议：${escapeHtml(finding.suggestion)}</small><small>验证：${escapeHtml(finding.verification)}</small></div><button data-import-assistance="${index}">作为待核查线索</button></article>`).join('') : '<p>机器没有提供线索。</p>'}</section>`;
}
function draftFormHtml() {
  const d = model.draft; const e = d.evidence;
  return `<section class="root-form"><div class="section-heading"><h3>新增根因</h3><span>字段完整后才能加入</span></div><div class="finding-form reasoning-form">
    <label>根因 ID<input id="root-id" value="${escapeHtml(d.rootCauseId)}" placeholder="例如 auth-empty-account" /></label>
    <label>类型<select id="category">${CATEGORIES.map(([id, label]) => `<option value="${id}" ${d.category === id ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
    <label>严重度<select id="severity">${['critical', 'high', 'medium', 'low'].map((v) => `<option value="${v}" ${d.severity === v ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
    <label>置信度<select id="confidence">${['certain', 'likely', 'uncertain'].map((v) => `<option value="${v}" ${d.confidence === v ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
    <label class="wide">根因陈述<textarea id="claim" placeholder="错误来自什么，而不是只写哪一行可疑">${escapeHtml(d.claim)}</textarea></label>
    <label class="wide">触发条件<textarea id="trigger" placeholder="什么输入、状态或调用顺序会触发">${escapeHtml(d.trigger)}</textarea></label>
    <label class="wide">可观察影响<textarea id="impact" placeholder="用户、数据、权限或系统会发生什么">${escapeHtml(d.impact)}</textarea></label>
    <label>证据类型<select id="evidence-kind">${EVIDENCE_KINDS.map(([id, label]) => `<option value="${id}" ${e.kind === id ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
    <label>版本<select id="evidence-revision">${['base', 'head', 'both'].map((v) => `<option value="${v}" ${e.revision === v ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
    <label>Diff 侧<select id="evidence-side"><option value="RIGHT" ${e.side === 'RIGHT' ? 'selected' : ''}>RIGHT</option><option value="LEFT" ${e.side === 'LEFT' ? 'selected' : ''}>LEFT</option></select></label>
    <label class="wide">证据路径<input id="evidence-path" value="${escapeHtml(e.path)}" placeholder="点击左侧代码行自动填写" /></label>
    <label>开始行<input id="evidence-start" type="number" min="1" value="${escapeHtml(e.startLine)}" /></label><label>结束行<input id="evidence-end" type="number" min="1" value="${escapeHtml(e.endLine)}" /></label>
    <label class="wide">证据事实<textarea id="evidence-fact" placeholder="这段代码客观证明了什么">${escapeHtml(e.fact)}</textarea></label>
    <label class="wide">符号或调用方<input id="evidence-symbol" value="${escapeHtml(e.symbol)}" placeholder="可选，填写冻结包中的符号 ID 或名称" /></label>
    <label class="wide">受影响符号<input id="affected-symbols" value="${escapeHtml(d.affectedSymbols)}" placeholder="多个符号用逗号分隔" /></label>
    <label class="wide">可接受修复<textarea id="acceptable-fix" placeholder="至少给出一个修复方向，可选具体补丁">${escapeHtml(d.acceptableFix)}</textarea></label>
    <label class="wide">验证方法<textarea id="verification" placeholder="如何证明问题和修复">${escapeHtml(d.verification)}</textarea></label>
    <label class="wide">不确定点<textarea id="uncertainty" placeholder="可选；缺少什么信息或能力边界">${escapeHtml(d.reviewerUncertainty)}</textarea></label>
    <button id="add-root" class="add-finding">＋ 添加完整根因</button></div></section>`;
}

function decisionHtml(current) {
  const frozen = tone(current) === 'frozen'; const gold = model.state.mode === 'gold';
  return `<div class="guardrail ${frozen ? 'frozen' : ''}"><span>${frozen ? '✓' : '◉'}</span><p><strong>${gold ? '正式金标模式' : '辅助 Review 模式'}</strong><br />${gold ? '服务端不生成也不返回机器预测。你可以使用冻结的中立上下文；缺少上下文时只能暂缓。' : '机器线索可用于学习和分流，但结果不能计入正式金标。'}</p></div>${legacyHtml(current)}
    <section class="blind-section"><div class="section-heading"><h3>${frozen ? '冻结的根因标签' : '你确认的根因'}</h3><span>${model.rootCauses.length} 条</span></div><div class="human-list">${model.rootCauses.length ? model.rootCauses.map((item, index) => rootCauseHtml(item, index, frozen)).join('') : '<div class="empty-human">尚未形成完整根因。证据不足时请暂缓，不要猜测。</div>'}</div></section>${frozen ? '' : draftFormHtml()}${assistanceHtml(current)}`;
}
function workspaceHtml(current) {
  if (model.state.localMode) return localWorkspaceHtml(current);
  if (!current) return '<main class="loading">没有可审阅的条目</main>';
  const frozen = tone(current) === 'frozen'; const summary = model.state.summary;
  return `<main class="workspace"><header class="topbar"><div><span class="eyebrow">${escapeHtml(model.state.mode.toUpperCase())} · ROOT CAUSE REVIEW</span><h1>${escapeHtml(current.owner)}/${escapeHtml(current.repo)} <b>#${current.number}</b></h1></div><div class="top-actions"><label>审阅人<input id="reviewer" value="${escapeHtml(model.reviewer)}" placeholder="姓名或代号" /></label><button id="export" class="export" ${summary.frozen !== summary.total || model.saving ? 'disabled' : ''}>⇧ 导出冻结标签</button></div></header>
    <section class="review-grid"><div class="diff-panel"><div class="pr-heading"><div><h2>${escapeHtml(current.title)}</h2><p>${escapeHtml(current.body || '该 PR 没有描述。')}</p></div><a href="${escapeHtml(current.sourceUrl)}" target="_blank" rel="noreferrer">在 GitHub 打开 ↗</a></div><div class="workspace-tabs"><button data-view="overview" class="${model.view === 'overview' ? 'active' : ''}">总览</button><button data-view="behavior" class="${model.view === 'behavior' ? 'active' : ''}">行为层 ${current.changeBrief.behaviorCards.length}</button>${current.reuseInvestigations ? `<button data-view="reuse" class="${model.view === 'reuse' ? 'active' : ''}">复用调查 ${current.reuseInvestigations.reduce((sum, item) => sum + item.candidates.length, 0)}</button>` : ''}<button data-view="change" class="${model.view === 'change' ? 'active' : ''}">Diff 证据</button><button data-view="context" class="${model.view === 'context' ? 'active' : ''}">Base / Head / 关系 ${current.contextPacket.available ? '✓' : '!'}</button></div>${model.view === 'overview' ? overviewPanelHtml(current) : model.view === 'behavior' ? behaviorPanelHtml(current) : model.view === 'reuse' ? reusePanelHtml(current) : model.view === 'change' ? changePanelHtml(current) : contextPanelHtml(current)}</div>
    <aside class="decision-panel"><div class="decision-scroll"><div class="decision-title"><div><span class="eyebrow">ROOT CAUSE CONTRACT</span><h2>${frozen ? '查看冻结结果' : '记录可验证根因'}</h2></div><span class="status-pill ${tone(current)}">${frozen ? '已冻结' : tone(current) === 'deferred' ? '暂缓' : '待判断'}</span></div>${decisionHtml(current)}<label class="notes">审阅备注（可选）<textarea id="notes" ${frozen ? 'disabled' : ''} placeholder="记录判断依据或不确定点…">${escapeHtml(model.notes)}</textarea></label>${model.message ? `<div class="message">${escapeHtml(model.message)}</div>` : ''}</div>
    <div class="decision-actions">${frozen ? '' : `<button id="defer" class="secondary" ${model.saving ? 'disabled' : ''}>Ⅱ 暂缓</button><button id="clean" class="clean" ${model.saving || (model.state.mode === 'gold' && !current.contextPacket.available) ? 'disabled' : ''}>⊘ 冻结为无根因</button><button id="freeze" class="primary" ${model.saving || !model.rootCauses.length || (model.state.mode === 'gold' && !current.contextPacket.available) ? 'disabled' : ''}>✓ 冻结 ${model.rootCauses.length} 条根因</button>`}</div><div class="case-nav"><button id="previous">← 上一条</button><span>${model.state.cases.indexOf(current) + 1} / ${summary.total}</span><button id="next">下一条 →</button></div></aside></section></main>`;
}

function localEntryHtml() {
  const input = model.localInput;
  return `<section class="local-entry"><div><strong>本地 Java 代码审查</strong><small>读取两次 Git 提交，帮助理解变更和选择最小修改方案。</small></div><form id="local-review-form"><label class="repo-input">Git 仓库路径<input id="local-repository" required value="${escapeHtml(input.repository)}" placeholder="C:\\项目\\my-java-project" /></label><label>Base<input id="local-base" required value="${escapeHtml(input.base)}" /></label><label>Head<input id="local-head" required value="${escapeHtml(input.head)}" /></label><button class="primary" ${model.localLoading ? 'disabled' : ''}>${model.localLoading ? '正在索引 Java…' : '开始审查'}</button></form><p role="status">${escapeHtml(model.localError || (model.localLoading ? '正在冻结提交、索引 Java 和计算上下文，请稍候（最多 2 分钟）。' : '默认比较最近一次提交。未提交修改不包含在内；不会上传代码、执行构建或写回仓库。'))}</p></section>`;
}

function localWorkspaceHtml(current) {
  if (!current) return '<main class="loading">填写仓库路径后点击“开始审查”。无需先做人工标注。</main>';
  const views = [['overview', '总览'], ['behavior', '行为变化'], ['reuse', '复用与方案'], ['change', 'Diff 证据'], ['context', 'Base / Head / 关系']];
  const panel = model.view === 'overview' ? overviewPanelHtml(current) : model.view === 'behavior' ? behaviorPanelHtml(current) : model.view === 'reuse' ? reusePanelHtml(current) : model.view === 'change' ? changePanelHtml(current) : contextPanelHtml(current);
  return `<main class="workspace"><header class="topbar"><div><span class="eyebrow">READ ONLY · JAVA SEMANTIC INDEX</span><h1>${escapeHtml(current.repo)} <b>本地审查</b></h1></div><button id="local-download" class="export">下载本地报告</button></header><section class="review-grid"><div class="diff-panel"><div class="pr-heading"><div><h2>${escapeHtml(current.title)}</h2><p>${escapeHtml(current.body)}</p></div></div><div class="workspace-tabs">${views.map(([view, title]) => `<button data-view="${view}" class="${model.view === view ? 'active' : ''}">${title}</button>`).join('')}</div>${panel}</div><aside class="decision-panel"><div class="decision-scroll"><h2>阅读与修改引导</h2><div class="guardrail"><p><strong>辅助理解，不需要标注</strong><br />索引和方案是阅读线索，不是缺陷或修复正确性的证明。</p></div>${model.state.localDirty ? '<div class="message">仓库有未提交修改；本次只分析所选提交，不会改动工作区。</div>' : ''}<section class="blind-section"><h3>建议按这个顺序看</h3><p>1. 总览：确认这次改变了什么。</p><p>2. 行为变化：比较旧行为与新行为。</p><p>3. 上下文：查看调用方、原有实现和测试。</p><p>4. 复用与方案：选择已有候选，生成修改范围与验证步骤。</p></section><h3>覆盖与限制</h3>${current.contextPacket.limitations.map(value => `<p>${escapeHtml(value)}</p>`).join('')}${model.message ? `<div class="message" role="status">${escapeHtml(model.message)}</div>` : ''}<div class="guardrail"><p>自动改代码、GitHub 发布和“已验证修复”均关闭。没有候选或没有问题提示不等于安全。</p></div></div></aside></section></main>`;
}

async function runLocalReview(event) {
  event.preventDefault();
  model.localInput = { repository: document.querySelector('#local-repository').value.trim(), base: document.querySelector('#local-base').value.trim(), head: document.querySelector('#local-head').value.trim() };
  model.localLoading = true; model.localError = ''; render();
  try {
    const response = await fetch('/api/local/review', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(model.localInput) });
    const result = await response.json(); if (!response.ok) throw new Error(result.error || '分析失败');
    await loadState(result.id); model.solutionOptions = {}; selectCase(result.id);
  } catch (error) { model.localError = error instanceof Error ? error.message : String(error); }
  finally { model.localLoading = false; render(); }
}

function downloadLocalReport() {
  const current = currentCase();
  const report = { format: 'codelens-local-review-v1', localOnly: true, verifiedFix: false, generatedAt: new Date().toISOString(),
    baseSha: current.baseSha, headSha: current.headSha, changeBrief: current.changeBrief,
    reuseInvestigations: current.reuseInvestigations, solutionOptions: model.solutionOptions, limitations: current.contextPacket.limitations };
  const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = `${current.id}-report.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function syncDraft() {
  const value = (id, fallback = '') => document.querySelector(id)?.value ?? fallback;
  model.draft.rootCauseId = value('#root-id', model.draft.rootCauseId); model.draft.category = value('#category', model.draft.category);
  model.draft.severity = value('#severity', model.draft.severity); model.draft.confidence = value('#confidence', model.draft.confidence);
  model.draft.claim = value('#claim', model.draft.claim); model.draft.trigger = value('#trigger', model.draft.trigger); model.draft.impact = value('#impact', model.draft.impact);
  model.draft.affectedSymbols = value('#affected-symbols', model.draft.affectedSymbols); model.draft.acceptableFix = value('#acceptable-fix', model.draft.acceptableFix);
  model.draft.verification = value('#verification', model.draft.verification); model.draft.reviewerUncertainty = value('#uncertainty', model.draft.reviewerUncertainty);
  Object.assign(model.draft.evidence, { kind: value('#evidence-kind', model.draft.evidence.kind), revision: value('#evidence-revision', model.draft.evidence.revision), side: value('#evidence-side', model.draft.evidence.side), path: value('#evidence-path', model.draft.evidence.path), startLine: value('#evidence-start', model.draft.evidence.startLine), endLine: value('#evidence-end', model.draft.evidence.endLine), symbol: value('#evidence-symbol', model.draft.evidence.symbol), fact: value('#evidence-fact', model.draft.evidence.fact) });
  model.notes = value('#notes', model.notes);
}
function addRootCause() {
  syncDraft(); const d = model.draft; const e = d.evidence;
  const required = [d.rootCauseId, d.claim, d.trigger, d.impact, d.verification, e.path, e.startLine, e.endLine, e.fact];
  if (required.some((value) => !String(value).trim())) { model.message = '请补全根因、触发条件、影响、证据事实和验证方法。'; render(); return; }
  const startLine = Number(e.startLine); const endLine = Number(e.endLine);
  if (!Number.isInteger(startLine) || !Number.isInteger(endLine) || startLine < 1 || endLine < startLine) { model.message = '证据行号无效。'; render(); return; }
  if (model.rootCauses.some((item) => item.rootCauseId === d.rootCauseId.trim())) { model.message = '根因 ID 已存在。'; render(); return; }
  const evidence = { kind: e.kind, path: e.path.trim(), revision: e.revision, startLine, endLine, fact: e.fact.trim(), ...(e.kind === 'diff' ? { side: e.side } : {}), ...(e.symbol.trim() ? { symbol: e.symbol.trim() } : {}) };
  model.rootCauses.push({ rootCauseId: d.rootCauseId.trim(), category: d.category, severity: d.severity, claim: d.claim.trim(), trigger: d.trigger.trim(), impact: d.impact.trim(), evidence: [evidence], affectedSymbols: d.affectedSymbols.split(',').map((v) => v.trim()).filter(Boolean), ...(d.acceptableFix.trim() ? { acceptableFix: d.acceptableFix.trim() } : {}), verification: d.verification.trim(), confidence: d.confidence, ...(d.reviewerUncertainty.trim() ? { reviewerUncertainty: d.reviewerUncertainty.trim() } : {}) });
  model.draft = emptyDraft(); model.message = ''; render();
}
function importLegacy(current, index) {
  const draft = current.legacyDrafts[index]; if (!draft) return;
  model.draft = { ...emptyDraft(), rootCauseId: draft.rootCauseId, category: draft.category, severity: draft.severity, claim: draft.claim, evidence: { ...emptyDraft().evidence, ...draft.evidence[0] } };
  model.message = '已载入旧标签草稿。请重新核验证据并补全触发条件、影响、验证方法和置信度。'; render();
}
function importAssistance(current, index) {
  const finding = current.assistance?.[index]; if (!finding) return;
  model.draft = { ...emptyDraft(), rootCauseId: `candidate-${finding.category}-${finding.line}`, category: finding.category, severity: finding.severity, claim: finding.claim, acceptableFix: finding.suggestion, verification: finding.verification, confidence: 'uncertain', evidence: { ...emptyDraft().evidence, kind: 'diff', path: finding.path, revision: 'head', side: 'RIGHT', startLine: finding.line, endLine: finding.line, fact: finding.excerpt ?? finding.title } };
  model.message = '机器线索已载入，但仍需由你填写触发条件和影响并核验证据。'; render();
}

function openBriefEvidence(evidenceId) {
  syncDraft();
  const current = currentCase();
  const evidence = current.changeBrief.evidence.find((item) => item.id === evidenceId);
  if (!evidence?.path) {
    model.message = '该证据来自 PR 标题或正文，已显示在总览意图区。'; render(); return;
  }
  if (evidence.kind === 'diff') {
    model.activeFile = evidence.path; model.view = 'change'; render(); return;
  }
  const files = current.contextPacket.available ? current.contextPacket.files : [];
  const target = files.find((file) => file.path === evidence.path && (!evidence.revision || file.revision === evidence.revision))
    ?? files.find((file) => file.path === evidence.path && file.revision === 'head')
    ?? files.find((file) => file.path === evidence.path);
  if (!target) {
    model.message = '该证据路径不在冻结上下文中。'; render(); return;
  }
  model.activeContextKey = contextKey(target); model.view = 'context'; render();
}

function bindEvents() {
  document.querySelector('#local-review-form')?.addEventListener('submit', runLocalReview);
  document.querySelector('#local-download')?.addEventListener('click', downloadLocalReport);
  for (const [id, key] of [['local-repository', 'repository'], ['local-base', 'base'], ['local-head', 'head']]) document.querySelector(`#${id}`)?.addEventListener('input', event => { model.localInput[key] = event.target.value; });
  document.querySelectorAll('[data-filter]').forEach((button) => button.addEventListener('click', () => { model.filter = button.dataset.filter; render(); }));
  document.querySelectorAll('[data-case]').forEach((button) => button.addEventListener('click', () => selectCase(button.dataset.case)));
  document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => { syncDraft(); model.view = button.dataset.view; render(); }));
  document.querySelectorAll('[data-file]').forEach((button) => button.addEventListener('click', () => { syncDraft(); model.activeFile = button.dataset.file; render(); }));
  document.querySelectorAll('[data-context-file]').forEach((button) => button.addEventListener('click', () => { syncDraft(); model.activeContextKey = button.dataset.contextFile; render(); }));
  document.querySelectorAll('[data-diff-line]').forEach((row) => row.addEventListener('click', () => { syncDraft(); model.draft.evidence = { ...model.draft.evidence, kind: 'diff', path: model.activeFile, revision: row.dataset.diffSide === 'LEFT' ? 'base' : 'head', side: row.dataset.diffSide, startLine: row.dataset.diffLine, endLine: row.dataset.diffLine }; render(); }));
  document.querySelectorAll('[data-context-line]').forEach((row) => row.addEventListener('click', () => { syncDraft(); const current = currentCase(); const file = current.contextPacket.files.find((item) => contextKey(item) === model.activeContextKey); if (!file) return; const kind = file.role === 'test' ? 'test' : file.role === 'configuration' ? 'config' : file.revision === 'base' ? 'base_source' : 'head_source'; model.draft.evidence = { ...model.draft.evidence, kind, path: file.path, revision: file.revision, startLine: row.dataset.contextLine, endLine: row.dataset.contextLine, side: file.revision === 'base' ? 'LEFT' : 'RIGHT' }; render(); }));
  document.querySelectorAll('[data-symbol-line]').forEach((button) => button.addEventListener('click', () => { syncDraft(); const current = currentCase(); const file = current.contextPacket.files.find((item) => contextKey(item) === model.activeContextKey); if (!file) return; model.draft.evidence = { ...model.draft.evidence, kind: 'symbol', path: file.path, revision: file.revision, startLine: button.dataset.symbolLine, endLine: button.dataset.symbolLine, symbol: button.dataset.symbolName }; render(); }));
  document.querySelectorAll('[data-remove-root]').forEach((button) => button.addEventListener('click', () => { model.rootCauses.splice(Number(button.dataset.removeRoot), 1); render(); }));
  document.querySelectorAll('[data-import-legacy]').forEach((button) => button.addEventListener('click', () => importLegacy(currentCase(), Number(button.dataset.importLegacy))));
  document.querySelectorAll('[data-import-assistance]').forEach((button) => button.addEventListener('click', () => importAssistance(currentCase(), Number(button.dataset.importAssistance))));
  document.querySelectorAll('[data-brief-evidence]').forEach((button) => button.addEventListener('click', () => openBriefEvidence(button.dataset.briefEvidence)));
  document.querySelectorAll('[data-open-behavior]').forEach((button) => button.addEventListener('click', () => { syncDraft(); model.view = 'behavior'; render(); }));
  document.querySelectorAll('[data-build-solution]').forEach((button) => button.addEventListener('click', () => requestSolutionOptions(button.dataset.buildSolution, button.dataset.candidateId)));
  document.querySelectorAll('[data-scope-path]').forEach((button) => button.addEventListener('click', () => {
    syncDraft(); const current = currentCase(); const filePath = button.dataset.scopePath;
    if (current.files.some((file) => file.path === filePath)) { model.activeFile = filePath; model.view = 'change'; render(); return; }
    const target = current.contextPacket.available ? current.contextPacket.files.find((file) => file.path === filePath && file.revision === 'head') ?? current.contextPacket.files.find((file) => file.path === filePath) : undefined;
    if (target) { model.activeContextKey = contextKey(target); model.view = 'context'; render(); }
  }));
  document.querySelector('#add-root')?.addEventListener('click', addRootCause); document.querySelector('#search')?.addEventListener('input', (event) => { model.query = event.target.value; render(); });
  document.querySelector('#reviewer')?.addEventListener('input', (event) => { model.reviewer = event.target.value; localStorage.setItem('codelens-reviewer', model.reviewer); });
  document.querySelector('#notes')?.addEventListener('input', (event) => { model.notes = event.target.value; });
  document.querySelector('#defer')?.addEventListener('click', () => saveDecision('deferred', [])); document.querySelector('#clean')?.addEventListener('click', () => saveDecision('frozen', []));
  document.querySelector('#freeze')?.addEventListener('click', () => saveDecision('frozen', model.rootCauses)); document.querySelector('#export')?.addEventListener('click', exportDataset);
  const index = model.state.cases.indexOf(currentCase());
  document.querySelector('#previous')?.addEventListener('click', () => selectCase(model.state.cases[Math.max(0, index - 1)].id));
  document.querySelector('#next')?.addEventListener('click', () => selectCase(model.state.cases[Math.min(model.state.cases.length - 1, index + 1)].id));
}
function render() {
  if (!model.state) { root.innerHTML = '<main class="loading">✦ 正在准备根因审阅队列…</main>'; return; }
  const current = currentCase(); document.body.classList.toggle('local-mode', Boolean(model.state.localMode));
  root.innerHTML = `${model.state.localMode ? localEntryHtml() : ''}<div class="shell">${sidebarHtml(current)}${workspaceHtml(current)}</div>`; bindEvents();
}
async function loadState(preferId) {
  const response = await fetch('/api/state'); if (!response.ok) throw new Error('无法加载审阅队列');
  model.state = await response.json(); model.activeId = preferId ?? (model.activeId || model.state.cases[0]?.id || '');
  if (!currentCase()) model.activeId = model.state.cases[0]?.id ?? '';
}
async function saveDecision(status, rootCauses) {
  const item = currentCase(); syncDraft();
  if (!model.reviewer.trim()) { model.message = '请先填写审阅人姓名或代号。'; render(); return; }
  model.saving = true; model.message = ''; render();
  try {
    const response = await fetch(`/api/decisions/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ status, reviewer: model.reviewer.trim(), rootCauses, ...(model.notes.trim() ? { notes: model.notes.trim() } : {}) }) });
    const result = await response.json(); if (!response.ok) throw new Error(result.error ?? '保存失败');
    await loadState(item.id); selectCase(item.id); model.message = status === 'frozen' ? '根因答案已冻结，后续不能修改。' : '已暂缓；未形成金标。';
  } catch (error) { model.message = error instanceof Error ? error.message : String(error); } finally { model.saving = false; render(); }
}
async function requestSolutionOptions(behaviorId, candidateId) {
  const current = currentCase(); const investigation = current.reuseInvestigations?.find((item) => item.behaviorId === behaviorId);
  const candidate = investigation?.candidates.find((item) => item.id === candidateId);
  if (!investigation || !candidate) return;
  model.saving = true; model.message = ''; render();
  try {
    const decision = {
      behaviorId, goal: investigation.goal, searchScope: investigation.searchScope, candidates: investigation.candidates,
      decision: candidate.fit === 'direct' ? 'reuse' : 'extend', selectedCandidateId: candidate.id,
      justification: `${candidate.rationale} 方案必须保持现有外部契约并限制修改范围。`,
      changeBudget: { maxFiles: Math.max(2, investigation.searchScope.changedFiles.length + 1), maxChangedSymbols: 5, publicContractChangeAllowed: false }
    };
    const response = await fetch('/api/reuse/options', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(decision) });
    const result = await response.json(); if (!response.ok) throw new Error(result.error ?? '方案生成失败');
    model.solutionOptions[`${current.id}:${behaviorId}`] = result.options; model.message = '已生成结构化方案；补丁仍保持阻断，直到批准并通过验证。';
  } catch (error) { model.message = error instanceof Error ? error.message : String(error); } finally { model.saving = false; render(); }
}
async function exportDataset() {
  if (!confirm('确认导出全部冻结的根因标签？')) return;
  model.saving = true; render();
  try {
    const response = await fetch('/api/export', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ reviewer: model.reviewer.trim() }) });
    const result = await response.json(); if (!response.ok) throw new Error(result.error ?? '导出失败'); model.message = `已导出：${result.outputPath}`;
  } catch (error) { model.message = error instanceof Error ? error.message : String(error); } finally { model.saving = false; render(); }
}

render();
loadState().then(() => selectCase(model.state.cases[0]?.id ?? '')).catch((error) => { root.innerHTML = `<main class="loading">${escapeHtml(error instanceof Error ? error.message : String(error))}</main>`; });
