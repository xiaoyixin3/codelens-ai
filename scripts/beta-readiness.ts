import postgres from 'postgres';
import { loadConfig } from '@codelens/config';
import {
  evaluateBetaReadiness,
  type BetaRolloutCounts
} from '@codelens/evaluation';

function readNumber(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new Error(`${name} must be numeric.`);
  return value;
}

const config = loadConfig();
const thresholds = {
  windowDays: readNumber('BETA_OBSERVATION_DAYS', 7),
  minEligibleReviews: readNumber('BETA_MIN_ELIGIBLE_REVIEWS', 20),
  minSuccessRate: readNumber('BETA_MIN_SUCCESS_RATE', 0.95)
};
const cutoff = new Date(Date.now() - thresholds.windowDays * 86_400_000);
const sql = postgres(config.DATABASE_URL, { max: 1 });

try {
  const rows = await sql<{ status: keyof BetaRolloutCounts; count: number }[]>`
    SELECT
      CASE status
        WHEN 'in_progress' THEN 'inProgress'
        ELSE status
      END AS status,
      count(*)::int AS count
    FROM review_runs
    WHERE created_at >= ${cutoff}
    GROUP BY status
  `;
  const counts: BetaRolloutCounts = {
    completed: 0,
    failed: 0,
    stale: 0,
    skipped: 0,
    queued: 0,
    inProgress: 0
  };
  for (const row of rows) counts[row.status] = row.count;

  const report = evaluateBetaReadiness(counts, thresholds);
  console.log(JSON.stringify({
    observedSince: cutoff.toISOString(),
    ...report
  }, null, 2));
  if (!report.ready) process.exitCode = 1;
} finally {
  await sql.end();
}
