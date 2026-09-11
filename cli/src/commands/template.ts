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
import { program } from "commander";
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { CLI_LABEL } from '../constants.js';

export const templateCommand = program.command('template')
  .description(`Manage configuration templates in ${CLI_LABEL}`);

/** List all configuration templates */
templateCommand.command('list')
  .description('List all configuration templates')
  .action(async () => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationTemplates`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching configuration templates failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json().catch((err: Error) => {
      Logger.error('Error parsing configuration templates response: ' + err.message);
    });

    if (data != null) {
      if (data.length === 0) {
        Logger.info('No configuration templates found.');
      } else {
        const longestName = Math.max(...data.map((t: any) => t.name.length + 1), 'NAME'.length + 1);
        Logger.log(`${'ID'.padEnd(13, ' ')}  ${'NAME'.padEnd(longestName, ' ')} OAUTH2_CONFIG`);
        data.forEach((t: any) => {
          Logger.log(
            `${t.id}  ${t.name.padEnd(longestName, ' ')} ${t.oauth2Configuration != null ? 'Yes' : 'No'}`
          );
        });
      }
    }
  });

/** Get a configuration template by ID */
templateCommand.command('get <id>')
  .description('Get a configuration template by ID')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationTemplates/${id}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching configuration template failed: ' + response.statusText);
      process.exit(1);
    }

    const t = await response.json();
    Logger.info('Configuration template details');
    Logger.log(`ID          : ${t.id}`);
    Logger.log(`Name        : ${t.name}`);
    Logger.log(`Organization: ${t.organizationId}`);
    Logger.log(`Description : ${t.description ?? ''}`);
    if (t.oauth2Configuration) {
      Logger.bold('OAuth2:');
      Logger.log(`  Authorization Servers: ${t.oauth2Configuration.authorizationServers?.join(', ')}`);
      Logger.log(`  JWKS URI             : ${t.oauth2Configuration.jwksUri}`);
      if (t.oauth2Configuration.scopes) {
        Logger.log(`  Scopes               : ${t.oauth2Configuration.scopes.join(', ')}`);
      }
    } else {
      Logger.log(`OAuth2      : No`);
    }
  });

/** Create a new configuration template */
templateCommand.command('create <name>')
  .description('Create a new configuration template')
  .option('-d, --description <text>', 'Description of the template')
  .option('--oas, --oauth2AuthorizationServers <servers>', 'Comma-separated OAuth2 authorization server URLs')
  .option('--oju, --oauth2jwksUri <jwksUri>', 'JWKS URI to validate OAuth2 tokens')
  .option('--osc, --oauth2Scopes <scopes>', 'Comma-separated OAuth2 scopes')
  .action(async (name, options) => {
    const body: any = {
      name,
      description: options.description || undefined
    };

    if (options.oauth2AuthorizationServers) {
      body.oauth2Configuration = {
        authorizationServers: options.oauth2AuthorizationServers.split(',').map((s: string) => s.trim()).filter(Boolean),
        jwksUri: options.oauth2jwksUri || null,
        scopes: options.oauth2Scopes
          ? options.oauth2Scopes.split(',').map((s: string) => s.trim()).filter(Boolean)
          : null
      };
    }

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationTemplates`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(body)
    });

    if (!response.ok) {
      Logger.error('Creating configuration template failed: ' + response.statusText);
      process.exit(1);
    }

    const t = await response.json();
    Logger.success(`Configuration template '${t.name}' created successfully with ID: ${t.id}`);
  });

/** Delete a configuration template by ID */
templateCommand.command('delete <id>')
  .description('Delete a configuration template by ID')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationTemplates/${id}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Deleting configuration template failed: ' + response.statusText);
      process.exit(1);
    }

    Logger.success(`Configuration template '${id}' deleted successfully.`);
  });
