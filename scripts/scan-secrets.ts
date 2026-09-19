import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const excludedDirectories = new Set(['.git', 'node_modules', 'dist', 'coverage']);
const forbiddenExtensions = new Set(['.pem', '.key', '.p12', '.pfx']);
const allowedEnvironmentFiles = new Set(['.env.example']);
const allowedFixtureValues = new Set([
  'sk-live-super-secret-value',
  'github_pat_abcdefghijklmnopqrstuvwxyz123456'
]);
const secretPatterns = [
  { kind: 'private-key', pattern: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g },
  { kind: 'github-pat', pattern: /github_pat_[A-Za-z0-9_]{20,}/g },
  { kind: 'github-token', pattern: /ghp_[A-Za-z0-9]{20,}/g },
  { kind: 'model-key', pattern: /sk-[A-Za-z0-9_-]{20,}/g },
  { kind: 'aws-access-key', pattern: /AKIA[0-9A-Z]{16}/g },
  { kind: 'slack-token', pattern: /xox[baprs]-[A-Za-z0-9-]{10,}/g }
];

interface Finding {
  file: string;
  kind: string;
}

function collectFiles(): string[] {
  const output = execFileSync(
    'git',
    ['ls-files', '-z', '--cached', '--others', '--exclude-standard'],
    { cwd: root, encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 }
  );
  return output
    .split('\0')
    .filter(Boolean)
    .filter((relative) => !relative.split('/').some((part) => excludedDirectories.has(part)))
    .map((relative) => path.resolve(root, relative));
}

const findings: Finding[] = [];
// Scan everything Git could publish: tracked files plus untracked, non-ignored
// files. Deliberately ignored runtime secrets remain local, while a force-added
// .env or private key is still scanned and rejected.
const files = collectFiles();
for (const absolute of files) {
  const relative = path.relative(root, absolute).replaceAll('\\', '/');
  const basename = path.basename(absolute);
  const extension = path.extname(absolute).toLowerCase();
  if (forbiddenExtensions.has(extension)) {
    findings.push({ file: relative, kind: `forbidden-file:${extension}` });
    continue;
  }
  if (basename.startsWith('.env') && !allowedEnvironmentFiles.has(basename)) {
    findings.push({ file: relative, kind: 'environment-file' });
    continue;
  }

  let buffer: Buffer;
  try {
    buffer = await readFile(absolute);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') continue;
    throw error;
  }
  if (buffer.includes(0)) continue;
  const source = buffer.toString('utf8');
  for (const { kind, pattern } of secretPatterns) {
    for (const match of source.matchAll(pattern)) {
      if (!allowedFixtureValues.has(match[0])) findings.push({ file: relative, kind });
    }
  }
}

const uniqueFindings = [...new Map(
  findings.map((finding) => [`${finding.file}:${finding.kind}`, finding])
).values()];
console.log(JSON.stringify({
  clean: uniqueFindings.length === 0,
  scannedFiles: files.length,
  findings: uniqueFindings
}, null, 2));
if (uniqueFindings.length) process.exitCode = 1;
