#!/usr/bin/env node
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

import { program } from 'commander';
import * as yaml from 'js-yaml';
import { loginCommand, infoCommand, logoutCommand, importCommand, attachCommand, quotasCommand, runCommand, statusCommand, stopCommand, switchOrgCommand, adminCommand } from './commands/index.js';
import { ConfigUtil } from './utils/config.js';
import { Logger } from './utils/logger.js';
import { Context } from './utils/context.js';
import { CLI_VERSION } from './version.js';
import { CLI_NAME, CLI_LABEL } from './constants.js';
import { configureShellCompletion } from './completion.js';

program
  .name(CLI_NAME)
  .description(CLI_LABEL + ' CLI - A command line interface for ' + CLI_LABEL)
  .version(CLI_VERSION)
  .hook('preAction', (thisCommand, actionCommand) => {
    ConfigUtil.readConfig();
    const noAuthCommands = ['login', 'logout', 'run', 'status', 'stop', 'completion', 'complete'];
    const isAdminCommand = belongsToCommand(actionCommand, 'admin');
    if (!noAuthCommands.includes(actionCommand.name()) && !isAdminCommand) {
      if (!ConfigUtil.config?.token) {
        Logger.warn(`You are not logged in. Please login first using the \`${CLI_NAME} login\` command.`);
        process.exit(1);
      }
      if (ConfigUtil.config.exp && ConfigUtil.config.exp < Date.now() / 1000) {
        Logger.warn(`Your token has expired. Please login again using the \`${CLI_NAME} login\` command.`);
        process.exit(1);
      }
    }
    
    if (actionCommand.opts().output) {
      Logger.mute();
    }
  })
  .hook('postAction', (thisCommand, actionCommand) => {
    if (actionCommand.opts().output) {
      Logger.unmute();
      // Check requested format.
      const format = actionCommand.opts().output.toLowerCase();
      if (format !== 'json' && format !== 'yaml') {
        Logger.error('Invalid output format. Supported formats are json and yaml.');
        process.exit(1);
      }

      if (!Context.isEmpty()) {
        if (Context.size() === 1) {
          const firstKey = Object.keys(Context.getAll())[0];
          if (format === 'json') {
            Logger.log(convertToJson(Context.get(firstKey)));
          } else if (format === 'yaml') {
            Logger.log(convertToYaml(Context.get(firstKey)));
          }
        } else {
          if (format === 'json') {
            Logger.log(convertToJson(Context.getAll()));
          } else if (format === 'yaml') {
            Logger.log(convertToYaml(Context.getAll()));
          }
        }
      }
    }
  });

function convertToJson(data: any) : string {
  return JSON.stringify(data, null, 2);
}
function convertToYaml(data: any): string {
  return yaml.dump(data);
}

function belongsToCommand(command: typeof program, name: string): boolean {
  let current = command.parent;
  while (current) {
    if (current.name() === name) {
      return true;
    }
    current = current.parent;
  }
  return false;
}


process.on('uncaughtException', (error) => {
  console.error('Uncaught Exception:', error);
  process.exit(1);
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled Rejection at:', promise, 'reason:', reason);
  process.exit(1);
});


// Load commands.
program.addCommand(loginCommand);
program.addCommand(infoCommand);
program.addCommand(logoutCommand);
program.addCommand(importCommand);
program.addCommand(attachCommand);
program.addCommand(quotasCommand);
program.addCommand(runCommand);
program.addCommand(statusCommand);
program.addCommand(stopCommand);
program.addCommand(switchOrgCommand);
program.addCommand(adminCommand);

configureShellCompletion(program);
program.parse(process.argv);