import { describe, it, expect } from 'vitest';
import { mkdtemp, writeFile, rm, symlink } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { FileTooLargeError, readBoundedRegularFile } from '../scripts/bounded-file.js';

describe('descriptor-bound file reads', () => {
  it('reads a regular file without weakening the size limit or allowing directories', async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), 'codelens-bounded-test-'));
    try {
      const file = path.join(root, 'file.txt');
      await writeFile(file, 'bounded');
      expect((await readBoundedRegularFile(file, 7)).toString()).toBe('bounded');
      await expect(readBoundedRegularFile(file, 6)).rejects.toBeInstanceOf(FileTooLargeError);
      await expect(readBoundedRegularFile(root, 7)).rejects.toThrow();
      await expect(readBoundedRegularFile(file, 0)).rejects.toThrow('Invalid');
    } finally { await rm(root, { recursive: true, force: true }); }
  });
  it.skipIf(process.platform === 'win32')('rejects a final symbolic link on supported platforms', async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), 'codelens-bounded-link-test-'));
    try {
      const file = path.join(root, 'file.txt');
      const link = path.join(root, 'link.txt');
      await writeFile(file, 'bounded'); await symlink(file, link);
      await expect(readBoundedRegularFile(link, 100)).rejects.toThrow();
    } finally { await rm(root, { recursive: true, force: true }); }
  });
});
