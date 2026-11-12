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
import * as os from 'node:os';
import * as fs from 'node:fs';
import * as path from 'node:path';

import { Command } from "commander";
import { Logger } from "../utils/logger.js";
import { runDockerCompose, resolveContainerEngine } from '../utils/containers.js';
import { startAppleContainerStack } from '../utils/apple-container.js';
import { CLI_NAME, CLI_LABEL } from '../constants.js';

const GITHUB_REPO = 'reshaprio/reshapr';
const GITHUB_RAW_BASE = `https://raw.githubusercontent.com/${GITHUB_REPO}`;
const GITHUB_API_BASE = `https://api.github.com/repos/${GITHUB_REPO}`;
const COMPOSE_FILE_NAME = 'docker-compose-all-in-one.yml';
const UI_ADDON_FILE_NAME = 'docker-compose-ui-addon.yml';
const COMPOSE_REMOTE_PATH = `install/${COMPOSE_FILE_NAME}`;
const UI_ADDON_REMOTE_PATH = `install/${UI_ADDON_FILE_NAME}`;
const RESHAPR_DIR = path.join(os.homedir(), `.${CLI_NAME}`);
const RUN_STATE_FILE = path.join(RESHAPR_DIR, 'run-state.json');

export const runCommand = new Command('run')
  .description(`Start ${CLI_LABEL} locally using Docker Compose`)
  .option('-r, --release <release>', 'Release of the containers to run', 'latest')
  .option('-e, --engine <engine>', 'Container engine to use (docker, podman or apple for apple/container)')
  .option('--ui', 'Enable and deploy the web ui addon')
  .action(async (options) => {
    let release: string = options.release;
    const engine = resolveContainerEngine(options.engine);

    // Resolve 'latest' to the actual latest GitHub release tag.
    if (release === 'latest') {
      release = await resolveLatestRelease();
      Logger.info(`Resolved 'latest' to release '${release}'.`);
    }

    if (engine === 'apple') {
      await runOnAppleContainer(release, options.ui === true);
      return;
    }

    const composeFile = getComposeFilePath(release);
    const composeFiles: string[] = [composeFile];

    // Download compose file if not already cached for this release.
    if (!fs.existsSync(composeFile)) {
      await downloadComposeFile(release, composeFile, COMPOSE_REMOTE_PATH);
    } else {
      Logger.info(`Using cached compose file for release '${release}'.`);
    }

    // Handle --ui addon
    if (options.ui) {
      const uiAddonFile = getUiAddonFilePath(release);
      if (!fs.existsSync(uiAddonFile)) {
        await downloadComposeFile(release, uiAddonFile, UI_ADDON_REMOTE_PATH);
      } else {
        Logger.info(`Using cached UI addon compose file for release '${release}'.`);
      }
      composeFiles.push(uiAddonFile);
    }

    Logger.info(`Starting ${CLI_LABEL} containers (release: ${release}, engine: ${engine})...`);
    const exitCode = await runDockerCompose(['up', '-d'], composeFiles, engine);

    if (exitCode !== 0) {
      Logger.error(`${engine} compose exited with code ${exitCode}.`);
      process.exit(exitCode);
    }

    saveRunState(release, composeFiles, engine);
    Logger.success(`${CLI_LABEL} containers started successfully.`);

    if (options.ui) {
      Logger.info(`The web UI should be available at http://localhost:3333 once the containers are ready.`);
    }
  });

// apple/container has no Compose: the stack described by the release compose files is started with `container run`.
async function runOnAppleContainer(release: string, ui: boolean): Promise<void> {
  const composeFiles = [getComposeFilePath(release)];
  if (ui) {
    composeFiles.push(getUiAddonFilePath(release));
  }
  const remotePaths = [COMPOSE_REMOTE_PATH, UI_ADDON_REMOTE_PATH];
  for (const [index, file] of composeFiles.entries()) {
    if (!fs.existsSync(file)) {
      await downloadComposeFile(release, file, remotePaths[index]);
    } else {
      Logger.info(`Using cached compose file ${file}.`);
    }
  }

  Logger.info(`Starting ${CLI_LABEL} containers (release: ${release}, engine: apple)...`);
  try {
    await startAppleContainerStack(composeFiles);
  } catch (err) {
    Logger.error((err as Error).message);
    process.exit(1);
  }

  saveRunState(release, composeFiles, 'apple');
  Logger.success(`${CLI_LABEL} containers started successfully.`);
  Logger.info('Control plane: http://localhost:5555, gateway: http://localhost:7777');
  if (ui) {
    Logger.info('The web UI is available at http://localhost:3333');
  }
}

function getComposeFilePath(release: string): string {
  return path.join(RESHAPR_DIR, `docker-compose-${release}.yml`);
}

function getUiAddonFilePath(release: string): string {
  return path.join(RESHAPR_DIR, `docker-compose-ui-addon-${release}.yml`);
}

async function resolveLatestRelease(): Promise<string> {
  const url = `${GITHUB_API_BASE}/releases/latest`;
  const response = await fetch(url, {
    headers: { 'Accept': 'application/vnd.github+json' },
  });

  if (!response.ok) {
    Logger.error(`Failed to fetch latest release from GitHub: ${response.status} ${response.statusText}`);
    process.exit(1);
  }

  const data = await response.json() as { tag_name: string };
  return data.tag_name;
}

function getGitHubRef(release: string): string {
  if (release === 'nightly') {
    return 'refs/heads/main';
  }
  return `refs/tags/${release}`;
}

async function downloadComposeFile(release: string, destPath: string, remotePath: string): Promise<void> {
  const ref = getGitHubRef(release);
  const url = `${GITHUB_RAW_BASE}/${ref}/${remotePath}`;

  Logger.info(`Downloading compose file from ${url}...`);
  const response = await fetch(url);

  if (!response.ok) {
    Logger.error(`Failed to download compose file: ${response.status} ${response.statusText}`);
    Logger.error(`Make sure the release '${release}' exists on the GitHub repository.`);
    process.exit(1);
  }

  let content = await response.text();

  // Replace image tags with the requested release.
  content = content.replace(/(registry\.reshapr\.io\/reshapr\/[^:]+):[\w.-]+/g, `$1:${release}`);

  fs.mkdirSync(RESHAPR_DIR, { recursive: true, mode: 0o700 });
  fs.writeFileSync(destPath, content, { encoding: 'utf-8', mode: 0o600 });
  Logger.success(`Compose file saved to ${destPath}`);
}

function saveRunState(release: string, composeFiles: string[], engine: string): void {
  const state = { release, composeFiles, engine, startedAt: new Date().toISOString() };
  fs.writeFileSync(RUN_STATE_FILE, JSON.stringify(state, null, 2), { encoding: 'utf-8', mode: 0o600 });
}

export function readRunState(): { release: string; composeFiles: string[]; composeFile?: string; engine?: string; startedAt: string } | null {
  if (!fs.existsSync(RUN_STATE_FILE)) {
    return null;
  }
  try {
    const state = JSON.parse(fs.readFileSync(RUN_STATE_FILE, 'utf-8'));
    // Backward compatibility: convert legacy single composeFile to composeFiles array.
    if (!state.composeFiles && state.composeFile) {
      state.composeFiles = [state.composeFile];
    }
    return state;
  } catch {
    return null;
  }
}

export function removeRunState(): void {
  if (fs.existsSync(RUN_STATE_FILE)) {
    fs.unlinkSync(RUN_STATE_FILE);
  }
}
