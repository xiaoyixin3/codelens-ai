import { z } from 'zod';
import type { ChangeBrief, ReviewContextBundle } from '@codelens/evaluation';
import { changeEvidenceId } from '@codelens/evaluation';

const unique = <T>(values: T[]): T[] => [...new Set(values)];
const isTestPath = (value: string): boolean => /(?:^|\/)(?:test|tests|__tests__|spec)(?:\/|\.|$)/i.test(value)
  || /(?:Test|Tests|Spec)\.[^.]+$/i.test(value);
const namePart = (value: string): string => value.split(/[.#:]/).filter(Boolean).at(-1)?.toLowerCase() ?? value.toLowerCase();
const tokens = (value: string): Set<string> => new Set(value
  .replace(/([a-z])([A-Z])/g, '$1 $2')
  .toLowerCase()
  .split(/[^a-z0-9]+/)
  .filter((item) => item.length >= 3));

export const ReuseCandidateSchema = z.object({
  id: z.string().min(1),
  symbolId: z.string().min(1),
  symbolName: z.string().min(1),
  path: z.string().min(1),
  kind: z.string().min(1),
  relationship: z.enum(['same_contract', 'same_role', 'caller_pattern', 'test_fixture', 'similar_logic']),
  fit: z.enum(['direct', 'extendable', 'partial', 'rejected']),
  score: z.number().min(0).max(1),
  evidenceIds: z.array(z.string().min(1)).min(1),
  rationale: z.string().min(1),
  rejectionReason: z.string().min(1).optional()
}).superRefine((value, context) => {
  if (value.fit === 'rejected' && !value.rejectionReason) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['rejectionReason'], message: 'Rejected candidates require a reason.' });
  }
});
export type ReuseCandidate = z.infer<typeof ReuseCandidateSchema>;

export const ReuseSearchScopeSchema = z.object({
  changedFiles: z.array(z.string().min(1)).min(1),
  searchedSymbols: z.number().int().nonnegative(),
  searchedRelationships: z.number().int().nonnegative(),
  semanticCoverageComplete: z.boolean(),
  evidenceIds: z.array(z.string().min(1)).min(1),
  limitations: z.array(z.string())
});

export const ChangeBudgetSchema = z.object({
  maxFiles: z.number().int().positive().max(50),
  maxChangedSymbols: z.number().int().positive().max(200),
  publicContractChangeAllowed: z.boolean()
});
export type ChangeBudget = z.infer<typeof ChangeBudgetSchema>;

export const ReuseDecisionSchema = z.object({
  behaviorId: z.string().min(1),
  goal: z.string().min(8),
  searchScope: ReuseSearchScopeSchema,
  candidates: z.array(ReuseCandidateSchema).max(20),
  decision: z.enum(['reuse', 'extend', 'extract', 'new']),
  selectedCandidateId: z.string().min(1).optional(),
  justification: z.string().min(12),
  changeBudget: ChangeBudgetSchema
}).superRefine((value, context) => {
  const selected = value.candidates.find((candidate) => candidate.id === value.selectedCandidateId);
  if (value.decision !== 'new' && !selected) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['selectedCandidateId'], message: 'Reuse, extend, and extract decisions require a selected candidate.' });
  }
  if (selected?.fit === 'rejected') {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['selectedCandidateId'], message: 'A rejected candidate cannot be selected.' });
  }
  if (value.decision === 'new') {
    if (!value.searchScope.semanticCoverageComplete) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['searchScope'], message: 'New implementation cannot be justified with incomplete semantic coverage.' });
    }
    if (value.searchScope.searchedSymbols === 0) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['searchScope', 'searchedSymbols'], message: 'New implementation requires a non-empty repository search.' });
    }
    value.candidates.forEach((candidate, index) => {
      if (candidate.fit !== 'rejected' || !candidate.rejectionReason) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['candidates', index], message: 'Every candidate must be explicitly rejected before choosing new.' });
      }
    });
  }
});
export type ReuseDecision = z.infer<typeof ReuseDecisionSchema>;

export const SolutionOptionSchema = z.object({
  id: z.string().min(1),
  behaviorId: z.string().min(1),
  strategy: z.enum(['reuse', 'extend', 'extract', 'new']),
  title: z.string().min(1),
  summary: z.string().min(1),
  candidateId: z.string().min(1).optional(),
  expectedFiles: z.array(z.string().min(1)).min(1),
  expectedSymbols: z.array(z.string().min(1)).max(200),
  verification: z.array(z.string().min(1)).min(1),
  tradeoffs: z.array(z.string().min(1)).min(1),
  evidenceIds: z.array(z.string().min(1)).min(1)
});
export type SolutionOption = z.infer<typeof SolutionOptionSchema>;

export const ReuseInvestigationSchema = z.object({
  behaviorId: z.string().min(1),
  goal: z.string().min(1),
  searchScope: ReuseSearchScopeSchema,
  candidates: z.array(ReuseCandidateSchema),
  patchGate: z.object({ allowed: z.literal(false), reasons: z.array(z.string().min(1)).min(1) })
});
export type ReuseInvestigation = z.infer<typeof ReuseInvestigationSchema>;

export const PatchProposalSchema = z.object({
  behaviorId: z.string().min(1),
  solutionOptionId: z.string().min(1),
  touchedFiles: z.array(z.string().min(1)).min(1),
  changedSymbols: z.array(z.string().min(1)).min(1),
  changesPublicContract: z.boolean(),
  verification: z.array(z.string().min(1)).min(1),
  unifiedDiff: z.string().min(1)
});
export type PatchProposal = z.infer<typeof PatchProposalSchema>;

export interface PatchGateResult {
  allowed: boolean;
  reasons: string[];
}

function relationEvidence(bundle: ReviewContextBundle, symbolId: string, coreIds: Set<string>): string[] {
  return bundle.relationships.flatMap((edge, index) => (
    (edge.fromSymbolId === symbolId && coreIds.has(edge.toSymbolId))
    || (edge.toSymbolId === symbolId && coreIds.has(edge.fromSymbolId))
      ? [`relationship:${index}`]
      : []
  ));
}

function sharedCallerEvidence(bundle: ReviewContextBundle, symbolId: string, coreIds: Set<string>): string[] {
  const coreCallers = new Set(bundle.relationships.filter((edge) => edge.type === 'calls' && coreIds.has(edge.toSymbolId)).map((edge) => edge.fromSymbolId));
  return bundle.relationships.flatMap((edge, index) => edge.type === 'calls' && coreCallers.has(edge.fromSymbolId) && edge.toSymbolId === symbolId
    ? [`relationship:${index}`]
    : []);
}

export function retrieveReuseCandidates(brief: ChangeBrief, bundle?: ReviewContextBundle): ReuseInvestigation[] {
  return brief.behaviorCards.map((card) => {
    const limitations = bundle?.limitations ?? ['No frozen repository context packet was supplied.'];
    const changedPaths = new Set(card.changedFiles);
    const coreSymbols = (bundle?.symbols ?? []).filter((symbol) => changedPaths.has(symbol.path));
    const coreIds = new Set(coreSymbols.map((symbol) => symbol.id));
    const coreKinds = new Set(coreSymbols.map((symbol) => symbol.kind));
    const coreNames = new Set(coreSymbols.map((symbol) => namePart(symbol.name)));
    const coreTokens = new Set(coreSymbols.flatMap((symbol) => [...tokens(symbol.name)]));
    const headSymbols = (bundle?.symbols ?? []).filter((symbol) => symbol.revision === 'head' && !changedPaths.has(symbol.path));
    const candidates: ReuseCandidate[] = [];

    for (const symbol of headSymbols) {
      const directEvidence = relationEvidence(bundle!, symbol.id, coreIds);
      const callerEvidence = sharedCallerEvidence(bundle!, symbol.id, coreIds);
      const evidenceIds = unique([changeEvidenceId(`symbol:${symbol.id}`), ...directEvidence, ...callerEvidence]);
      let relationship: ReuseCandidate['relationship'] | undefined;
      let fit: ReuseCandidate['fit'] = 'partial';
      let score = 0;
      let rationale = '';
      if (isTestPath(symbol.path) && directEvidence.length) {
        relationship = 'test_fixture'; score = 0.9; fit = 'extendable';
        rationale = '该测试符号已通过冻结关系连接到变更行为，可优先扩展现有夹具。';
      } else if (coreNames.has(namePart(symbol.name)) && coreKinds.has(symbol.kind)) {
        relationship = 'same_contract'; score = 0.95; fit = 'direct';
        rationale = '符号名称和类型与变更核心一致，应先验证是否可直接复用现有契约。';
      } else if (callerEvidence.length && coreKinds.has(symbol.kind)) {
        relationship = 'same_role'; score = 0.84; fit = 'extendable';
        rationale = '该符号与变更核心共享调用方并承担相同符号角色。';
      } else if (callerEvidence.length) {
        relationship = 'caller_pattern'; score = 0.72; fit = 'partial';
        rationale = '同一调用方已使用该符号，可作为仓库内既有调用模式。';
      } else {
        const overlap = [...tokens(symbol.name)].filter((token) => coreTokens.has(token)).length;
        if (overlap && coreKinds.has(symbol.kind)) {
          relationship = 'similar_logic'; score = Math.min(0.69, 0.5 + overlap * 0.08); fit = 'partial';
          rationale = '符号类型和名称词元与变更核心相近，只能用于召回，不能单独证明应当复用。';
        }
      }
      if (!relationship) continue;
      candidates.push(ReuseCandidateSchema.parse({
        id: `${card.id}:${symbol.id}`,
        symbolId: symbol.id,
        symbolName: symbol.name,
        path: symbol.path,
        kind: symbol.kind,
        relationship,
        fit,
        score,
        evidenceIds,
        rationale
      }));
    }

    const deduplicated = [...new Map(candidates
      .sort((left, right) => right.score - left.score || left.symbolName.localeCompare(right.symbolName))
      .map((candidate) => [candidate.symbolId, candidate])).values()].slice(0, 8);
    const semanticCoverageComplete = Boolean(bundle)
      && bundle!.symbols.length > 0
      && !limitations.some((item) => /not supplied|missing|unavailable|failed|partial|truncat/i.test(item));
    return ReuseInvestigationSchema.parse({
      behaviorId: card.id,
      goal: `为 ${card.title} 选择最小且可验证的实现路径`,
      searchScope: {
        changedFiles: card.changedFiles,
        searchedSymbols: headSymbols.length,
        searchedRelationships: bundle?.relationships.length ?? 0,
        semanticCoverageComplete,
        evidenceIds: unique([...card.summary.evidenceIds, ...deduplicated.flatMap((candidate) => candidate.evidenceIds)]),
        limitations
      },
      candidates: deduplicated,
      patchGate: {
        allowed: false,
        reasons: [
          '尚未提交通过 ReuseDecisionSchema 校验的复用决策。',
          ...(semanticCoverageComplete ? [] : ['语义覆盖不完整，不能声称仓库中不存在可复用实现。'])
        ]
      }
    });
  });
}

export function buildSolutionOptions(decisionInput: ReuseDecision): SolutionOption[] {
  const decision = ReuseDecisionSchema.parse(decisionInput);
  const candidate = decision.candidates.find((item) => item.id === decision.selectedCandidateId);
  const evidenceIds = unique(candidate?.evidenceIds ?? [
    ...decision.searchScope.evidenceIds,
    ...decision.candidates.flatMap((item) => item.evidenceIds)
  ]);
  const selected = candidate ? `现有符号 ${candidate.symbolName}` : '经过完整检索后划定的新职责边界';
  const primary = SolutionOptionSchema.parse({
    id: `${decision.behaviorId}:primary`,
    behaviorId: decision.behaviorId,
    strategy: decision.decision,
    title: `${decision.decision === 'reuse' ? '直接复用' : decision.decision === 'extend' ? '扩展现有实现' : decision.decision === 'extract' ? '抽取共享能力' : '受控新建'}：${selected}`,
    summary: decision.justification,
    ...(candidate ? { candidateId: candidate.id } : {}),
    expectedFiles: unique([...(candidate ? [candidate.path] : []), ...decision.searchScope.changedFiles]).slice(0, decision.changeBudget.maxFiles),
    expectedSymbols: candidate ? [candidate.symbolName] : [],
    verification: ['执行受影响调用路径的定向测试。', '确认 Base/Head 外部契约与决策声明一致。'],
    tradeoffs: [decision.changeBudget.publicContractChangeAllowed ? '允许显式声明并审阅公共契约变化。' : '不得改变公共 API、异常或副作用契约。'],
    evidenceIds
  });
  const options = [primary];
  if (candidate && decision.decision !== 'reuse') {
    options.push(SolutionOptionSchema.parse({
      id: `${decision.behaviorId}:minimal-reuse`, behaviorId: decision.behaviorId, strategy: 'reuse',
      title: `更小改动：直接复用 ${candidate.symbolName}`,
      summary: '在扩展或抽取前，先验证调用方能否直接使用候选符号。', candidateId: candidate.id,
      expectedFiles: unique([candidate.path, ...decision.searchScope.changedFiles]).slice(0, decision.changeBudget.maxFiles),
      expectedSymbols: [candidate.symbolName],
      verification: ['运行候选符号现有测试并覆盖变更入口。'],
      tradeoffs: ['改动最小，但可能无法满足新的边界条件。'], evidenceIds: candidate.evidenceIds
    }));
  }
  return options.slice(0, 3);
}

export function verifyPatchRelease(
  decisionInput: unknown,
  optionInput: unknown,
  proposalInput: unknown
): PatchGateResult {
  const decisionResult = ReuseDecisionSchema.safeParse(decisionInput);
  const optionResult = SolutionOptionSchema.safeParse(optionInput);
  const proposalResult = PatchProposalSchema.safeParse(proposalInput);
  const reasons: string[] = [];
  if (!decisionResult.success) reasons.push('缺少有效的 ReuseDecision。');
  if (!optionResult.success) reasons.push('缺少有效且已选择的 Solution Option。');
  if (!proposalResult.success) reasons.push('补丁缺少完整改动范围、验证方法或局部 diff。');
  if (!decisionResult.success || !optionResult.success || !proposalResult.success) return { allowed: false, reasons };
  const decision = decisionResult.data; const option = optionResult.data; const proposal = proposalResult.data;
  if (option.behaviorId !== decision.behaviorId || proposal.behaviorId !== decision.behaviorId || proposal.solutionOptionId !== option.id) {
    reasons.push('决策、方案和补丁不属于同一个行为变更。');
  }
  if (proposal.touchedFiles.length > decision.changeBudget.maxFiles) reasons.push('补丁文件数超过 change budget。');
  if (proposal.changedSymbols.length > decision.changeBudget.maxChangedSymbols) reasons.push('修改符号数超过 change budget。');
  if (proposal.changesPublicContract && !decision.changeBudget.publicContractChangeAllowed) reasons.push('补丁改变公共契约，但复用决策未授权。');
  if (!proposal.verification.length) reasons.push('补丁没有验证方法。');
  return { allowed: reasons.length === 0, reasons };
}
