import { describe, it, expect } from 'vitest';
import { mavenJavaArgs, windowsMavenArgs } from '../scripts/maven-launcher.js';

describe('shell-free Windows Maven launch', () => {
  it('keeps a supplied path as one Java argument, never a shell command', () => {
    const args = mavenJavaArgs('C:/Maven & quoted space', 'C:/Maven & quoted space/boot/plexus.jar', 'C:/source with spaces');
    expect(args[1]).toBe('C:/Maven & quoted space/boot/plexus.jar');
    expect(args).toContain('-Dmaven.home=C:/Maven & quoted space');
    expect(args).toContain('-Dmaven.multiModuleProjectDirectory=C:/source with spaces');
    expect(args).not.toContain('/c');
    expect(args.at(-1)).toBe('package');
  });
  it('rejects a relative or non-Maven operator override', async () => {
    await expect(windowsMavenArgs('/project', { CODELENS_MAVEN: 'mvn.cmd & unsafe' })).rejects.toThrow('Maven');
  });
});
