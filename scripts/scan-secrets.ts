import { readdir, readFile } from 'node:fs/promises';
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

async function collectFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    if (entry.isDirectory() && excludedDirectories.has(entry.name)) continue;
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await collectFiles(absolute));
    else if (entry.isFile()) files.push(absolute);
  }
  return files;
}

const findings: Finding[] = [];
const files = await collectFiles(root);
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

  const buffer = await readFile(absolute);
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
