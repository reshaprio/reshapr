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
import { Command } from "commander";

import { Logger } from "../utils/logger.js";
import { runDockerCompose, type ContainerEngine } from '../utils/containers.js';
import { appleContainerStatus } from '../utils/apple-container.js';
import { CLI_LABEL } from '../constants.js';
import { readRunState } from './run.js';

export const statusCommand = new Command('status')
  .description(`Show the status of locally running ${CLI_LABEL}`)
  .action(async () => {
    const state = readRunState();
    if (!state) {
      Logger.warn(`No ${CLI_LABEL} containers have been started. Use \`reshapr run\` to start them.`);
      process.exit(0);
    }

    const engine: ContainerEngine = (state.engine as ContainerEngine) ?? 'docker';
    Logger.info(`${CLI_LABEL} containers (release: ${state.release}, engine: ${engine}, started at: ${state.startedAt})`);
    if (engine === 'apple') {
      Logger.log(appleContainerStatus(state.composeFiles));
      return;
    }
    const exitCode = await runDockerCompose(['ps'], state.composeFiles, engine);

    if (exitCode !== 0) {
      Logger.error(`${engine} compose exited with code ${exitCode}.`);
      process.exit(exitCode);
    }
  });
