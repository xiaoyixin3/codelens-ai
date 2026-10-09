import { describe, it, expect } from 'vitest';
import { LocalReviewInputSchema } from '../scripts/local-review.js';
import { buildNeutralChangeBrief, type ReplayCase, type ReviewContextBundle } from '@codelens/evaluation';
import { retrieveReuseCandidates } from '@codelens/solution-planning';

describe('local review request boundary', () => {
  it('has explicit immutable-commit defaults and bounded input', () => {
    expect(LocalReviewInputSchema.parse({ repository: 'C:/java/project' })).toEqual({ repository: 'C:/java/project', base: 'HEAD^', head: 'HEAD' });
    expect(() => LocalReviewInputSchema.parse({ repository: '', base: 'HEAD', head: 'HEAD' })).toThrow();
    expect(() => LocalReviewInputSchema.parse({ repository: 'C:/java', head: 'x'.repeat(201) })).toThrow();
    expect(() => LocalReviewInputSchema.parse({ repository: 'C:/java', execute: true })).toThrow();
  });

  it('handles long Java signatures and many references without dropping full context', () => {
    const item: ReplayCase = { id: 'local-boundary', context: { owner: 'local', repo: 'service', number: 1,
      title: 'Update service', body: '', baseSha: 'a'.repeat(40), headSha: 'b'.repeat(40),
      files: [{ path: 'src/Service.java', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' }] },
      expectedFindings: [], approval: { status: 'candidate' }, provenance: { kind: 'fixture' } };
    const symbols: ReviewContextBundle['symbols'] = Array.from({ length: 160 }, (_, index) => ({
      id: `head:${index}:${'long-signature'.repeat(25)}`, name: `Service.method${index}`, kind: 'method', path: 'src/Service.java', revision: 'head', startLine: 1, endLine: 1
    }));
    symbols.push({ id: `head:candidate:${'long-signature'.repeat(25)}`, name: 'Helper.method1', kind: 'method', path: 'src/Helper.java', revision: 'head', startLine: 1, endLine: 1 });
    const bundle: ReviewContextBundle = { version: 1, caseId: item.id, packetId: item.id, digest: 'c'.repeat(64), generatedAt: new Date().toISOString(),
      baseSha: item.context.baseSha, headSha: item.context.headSha, files: [
        { path: 'src/Service.java', revision: 'head', role: 'source', content: 'class Service {}' },
        { path: 'src/Helper.java', revision: 'head', role: 'source', content: 'class Helper {}' }
      ], symbols, relationships: [], limitations: [] };
    const brief = buildNeutralChangeBrief(item, bundle);
    expect(brief.evidence.length).toBe(163);
    expect(brief.evidence.every(evidence => evidence.id.length <= 300)).toBe(true);
    expect(brief.behaviorCards[0]!.after[0]!.evidenceIds).toHaveLength(100);
    expect(brief.coverage.limitations.join(' ')).toContain('摘要有截断');
    expect(bundle.symbols).toHaveLength(161);
    const investigations = retrieveReuseCandidates(brief, bundle);
    expect(investigations[0]!.candidates[0]!.evidenceIds.every(id => brief.evidence.some(evidence => evidence.id === id))).toBe(true);
  });
});
