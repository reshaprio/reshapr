/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { spawn, execFileSync } from 'node:child_process';

import { Logger } from "./logger.js";

export type ContainerEngine = 'docker' | 'podman' | 'container';

/**
 * Check whether a given binary is available on the system PATH.
 */
function isCommandAvailable(command: string): boolean {
  try {
    execFileSync(command, ['--version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

/**
 * Detect the container engine to use.
 *  - If the user explicitly chose one via `--engine`, validate it is available and return it.
 *  - Otherwise, try `docker` first, then `podman`, then `container` (apple/container).
 *  - Exit with an error if no engine is found.
 */
export function resolveContainerEngine(explicit?: string): ContainerEngine {
  if (explicit) {
    const engine = explicit as ContainerEngine;
    if (!isCommandAvailable(engine)) {
      Logger.error(`Container engine '${engine}' was explicitly requested but is not available on your system.`);
      process.exit(1);
    }
    Logger.info(`Using explicitly selected container engine: ${engine}`);
    return engine;
  }

  if (isCommandAvailable('docker')) {
    Logger.info('Detected container engine: docker');
    return 'docker';
  }

  if (isCommandAvailable('podman')) {
    Logger.info('Detected container engine: podman');
    return 'podman';
  }

  if (isCommandAvailable('container')) {
    Logger.info('Detected container engine: container (apple/container)');
    return 'container';
  }

  Logger.error('No container engine found. Please install docker, podman or apple/container.');
  process.exit(1);
}

export function runDockerCompose(args: string[], composeFile: string | string[], engine: ContainerEngine = 'docker'): Promise<number> {
  const files = Array.isArray(composeFile) ? composeFile : [composeFile];
  const composeArgs = ['compose', ...files.flatMap(f => ['-f', f]), ...args];

  return new Promise((resolve, reject) => {
    const proc = spawn(engine, composeArgs, {
      stdio: 'inherit',
      shell: false,
    });
    proc.on('close', (code) => resolve(code ?? 1));
    proc.on('error', (err) => {
      Logger.error(`Failed to execute ${engine} compose: ${err.message}`);
      reject(err);
    });
  });
}