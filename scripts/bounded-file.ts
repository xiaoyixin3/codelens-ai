import { constants } from 'node:fs';
import { open, lstat } from 'node:fs/promises';

export class FileTooLargeError extends Error {}

/** Check and read the same opened file, never a checked path reopened later. */
export async function readBoundedRegularFile(file: string, maximum: number): Promise<Buffer> {
  if (!Number.isSafeInteger(maximum) || maximum < 1 || maximum > 25 * 1024 * 1024) {
    throw new Error('Invalid bounded file limit.');
  }
  const handle = await open(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0));
  try {
    const info = await handle.stat();
    const pathInfo = await lstat(file);
    if (pathInfo.isSymbolicLink() || pathInfo.dev !== info.dev || pathInfo.ino !== info.ino) {
      throw new Error('File identity changed or is a symbolic link.');
    }
    if (!info.isFile()) throw new Error('Expected a regular file.');
    if (info.size > maximum) throw new FileTooLargeError('File exceeds the byte limit.');
    const buffer = Buffer.alloc(maximum + 1);
    let count = 0;
    while (count < buffer.length) {
      const result = await handle.read(buffer, count, buffer.length - count, count);
      if (!result.bytesRead) break;
      count += result.bytesRead;
    }
    if (count > maximum) throw new FileTooLargeError('File grew beyond the byte limit.');
    return buffer.subarray(0, count);
  } finally {
    await handle.close();
  }
}
