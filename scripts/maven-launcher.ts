import { access, readdir } from 'node:fs/promises';
import path from 'node:path';

export function mavenJavaArgs(home: string, bootJar: string, project: string): string[] {
  return ['-classpath', bootJar, `-Dmaven.home=${home}`,
    `-Dclassworlds.conf=${path.join(home, 'bin/m2.conf')}`,
    `-Dmaven.multiModuleProjectDirectory=${project}`,
    'org.codehaus.plexus.classworlds.launcher.Launcher', '-q', '-DskipTests', 'package'];
}

/** Use Maven's own Java launcher on Windows, not a shell-built batch command. */
export async function windowsMavenArgs(project: string, env: NodeJS.ProcessEnv = process.env): Promise<string[]> {
  const downloaded = path.join(env.USERPROFILE ?? '', 'Downloads/apache-maven-3.9.14-bin/apache-maven-3.9.14/bin/mvn.cmd');
  let batch = env.CODELENS_MAVEN;
  if (!batch) {
    batch = await access(downloaded).then(() => downloaded).catch(() => undefined);
    if (!batch) {
      for (const directory of (env.Path ?? env.PATH ?? '').split(path.delimiter).filter(Boolean)) {
        const candidate = path.join(directory, 'mvn.cmd');
        if (await access(candidate).then(() => true).catch(() => false)) { batch = candidate; break; }
      }
    }
  }
  if (!batch || !path.isAbsolute(batch) || path.basename(batch).toLowerCase() !== 'mvn.cmd') {
    throw new Error('Maven 3.9+ was not found. Set CODELENS_MAVEN to an absolute mvn.cmd path.');
  }
  await access(batch);
  const home = path.resolve(path.dirname(batch), '..');
  await access(path.join(home, 'bin/m2.conf'));
  const jars = (await readdir(path.join(home, 'boot'))).filter(name => /^plexus-classworlds-[A-Za-z0-9_.-]+\.jar$/.test(name));
  if (jars.length !== 1) throw new Error('Expected one Maven classworlds boot JAR.');
  return mavenJavaArgs(home, path.join(home, 'boot', jars[0]!), project);
}
