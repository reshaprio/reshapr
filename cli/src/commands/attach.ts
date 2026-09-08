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
import fs from 'node:fs';

import yoctoSpinner from 'yocto-spinner';
import { Command } from "commander";
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { Context } from "../utils/context.js";
import { readHttpErrorMessage } from "../utils/error.js";
import { CLI_LABEL } from '../constants.js';

export const attachCommand = new Command('attach')
  .description(`Attach an artifact to a ${CLI_LABEL} Service`)
  .option('-f, --file <file>', 'Path to the artifact file to atttach')
  .option('-u, --url <url>', 'URL of the artifact to atttach')
  .option('-s, --secret <artifactSecret>', 'Use a secret to authenticate the artifact to atttach')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (options) => {
    if (!options.file && !options.url) {
      Logger.error('You must provide either a file path or a URL to import.');
      process.exit(1);
    }

    let body: any;

    if (options.file) {
      if (!fs.existsSync(options.file)) {
        Logger.error(`File not found: ${options.file}`);
        process.exit(1);
      }
      // We should encode in multipart/form-data
      body = new FormData();
      body.append('file', new Blob([fs.readFileSync(options.file)]), options.file.split('/').pop());
    } else if (options.url) {
      // We should encode in application/x-www-form-urlencoded
      body = new URLSearchParams();
      body.append('url', options.url);
      if (options.secret) {
        body.append('secretName', options.secret);
      }
    }

    const spinner = yoctoSpinner({text: 'Attaching artifact...'}).start();

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/artifacts/attach`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`,
      },
      body: body
    });
    if (!response.ok) {
      spinner.stop();
      Logger.error('Attach failed: ' + await readHttpErrorMessage(response));
      process.exit(1);
    }

    const data = await response.json().catch(err => {
      Logger.error('Failed to parse response: ' + err.message);
      process.exit(1);
    });
    spinner.stop();

    Context.put('artifact', data);
    Logger.success('Attachment successful!');
    Logger.info(`Discovered Artifact ${data.name} with ID: ${data.id}`);
  });