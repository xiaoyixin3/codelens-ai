import { describe, expect, it } from 'vitest';
import { auditFacts } from '../scripts/performance-fact-audit.js';

describe('post-run semantic structural audit', () => {
  it('counts unchanged internal callers without counting unresolved or external calls', () => {
    const result = auditFacts({ symbols: [{ stableKey: 'a', path: 'Caller.java' }, { stableKey: 'b', path: 'Rule.java' }],
      relationships: [
        { fromStableKey: 'a', toStableKey: 'b', type: 'CALLS', typeResolved: true, sourcePath: 'Caller.java', sourceLine: 1 },
        { fromStableKey: 'a', toStableKey: 'b', type: 'CALLS', typeResolved: false, sourcePath: 'Caller.java', sourceLine: 2 },
        { fromStableKey: 'a', toStableKey: 'external', type: 'CALLS', typeResolved: true, sourcePath: 'Caller.java', sourceLine: 3 },
      ] }, ['Rule.java']);
    expect(result.internalResolvedCalls).toBe(1);
    expect(result.unchangedCallerEdgesIntoChangedFiles).toBe(1);
    expect(result.unchangedCallerFiles).toEqual(['Caller.java']);
  });
  it('flags annotation evidence located in another symbol owner file', () => {
    const result = auditFacts({ symbols: [{ stableKey: 'a', path: 'A.java' }], relationships: [
      { fromStableKey: 'a', toStableKey: 'Bean', type: 'ANNOTATED_WITH', typeResolved: true, sourcePath: 'B.java', sourceLine: 1 },
      { fromStableKey: 'a', toStableKey: 'Bean', type: 'ANNOTATED_WITH', typeResolved: true, sourcePath: 'A.java', sourceLine: 1 },
    ] }, []);
    expect(result.annotationEdges).toBe(2);
    expect(result.annotationSourceOwnerMismatches).toBe(1);
  });
});
