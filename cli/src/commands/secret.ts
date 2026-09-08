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
import { program } from "commander";
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { openUpdateEditor } from "../utils/editor.js";
import { Context } from '../utils/context.js';
import { CLI_LABEL } from '../constants.js';

export const secretCommand = program.command('secret')
  .description(`Manage secrets in ${CLI_LABEL}`);

/** The authMethod discriminator value for OAuth2 Client Credentials backend authentication. */
const OAUTH2_CLIENT_CREDENTIALS = 'OAUTH2_CLIENT_CREDENTIALS';

/* List all secrets */
secretCommand.command('list')
  .description('List all secrets')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async () => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/secrets/refs`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });
    
    if (!response.ok) {
      Logger.error('Fetching secrets failed: ' + response.statusText);
      process.exit(1);
    }
    const data = await response.json().catch(err => {
      Logger.error('Error parsing secrets response: ' + err.message);
    });

    if (data != null) {
      if (data.length === 0) {
        Logger.info('No secrets found.');
      } else {
        Context.put('secrets', data);
        const longestName = longestSecretName(data) + 1; // +1 for padding

        Logger.log(`${'ID'.padEnd(13, ' ')}  ${'NAME'.padEnd(longestName, ' ')} TYPE      DESCRIPTION`);
        data.forEach((secret: any) => {
          Logger.log(`${secret.id}  ${secret.name.padEnd(longestName, ' ')} ${secret.type}  ${secret.description || ''}`);
        });
      }
    }
  });

function longestSecretName(secrets: any[]) {
  return secrets.reduce((max, secret) => {
    return Math.max(max, secret.name.length);
  }, 0);
}

/* Get secret by ID */
secretCommand.command('get <id>')
  .description('Get details of a secret by ID')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/secrets/${id}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching secret failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json();
    Context.put('secret', data);

    Logger.info('Secret details');
    Logger.log(`ID          : ${data.id}`);
    Logger.log(`Name        : ${data.name}`);
    Logger.log(`Organization: ${data.organizationId}`);
    Logger.log(`Type        : ${data.type}`);
    if (data.authMethod) {
      Logger.log(`Auth Method : ${data.authMethod}`);
    }
    if (data.username) {
      Logger.log(`Username    : ${data.username}`);
    }
    if (data.password) {
      Logger.log(`Password    : ${data.password}`);
    }
    if (data.token) {
      Logger.log(`Token       : ${data.token}`);
    }
    if (!data.useElicitation && data.tokenHeader) {
      Logger.log(`Token Header: ${data.tokenHeader}`);
    }
    if (data.certPem) {
      Logger.log(`Certificate :`);
      Logger.log(data.certPem);
    }
    Logger.log(`Description : ${data.description || ''}`);
    if (data.authMethod === OAUTH2_CLIENT_CREDENTIALS && data.oauth2ClientConfiguration) {
      Logger.bold(`OAuth2 Client Credentials:`);
      Logger.log(`  OAuth2 Client ID      : ${data.oauth2ClientConfiguration.clientId}`);
      Logger.log(`  OAuth2 Token Endpoint : ${data.oauth2ClientConfiguration.tokenEndpoint}`);
      if (data.oauth2ClientConfiguration.scopes && data.oauth2ClientConfiguration.scopes.length > 0) {
        Logger.log(`  OAuth2 Scopes         : ${data.oauth2ClientConfiguration.scopes.join(', ')}`);
      }
    }
    if (data.useElicitation) {
      if (data.tokenHeader) {
        Logger.bold(`Elicited Token:`);
        Logger.log(`  Sensitive Header: ${data.tokenHeader}`);
      }
      if (data.oauth2ClientConfiguration) {
        Logger.bold(`Elicited OAuth2:`);
        Logger.log(`  OAuth2 Client ID             : ${data.oauth2ClientConfiguration.clientId}`);
        Logger.log(`  OAuth2 Authorization Endpoint: ${data.oauth2ClientConfiguration.authorizationEndpoint}`);
        Logger.log(`  OAuth2 Token Endpoint        : ${data.oauth2ClientConfiguration.tokenEndpoint}`);
      }
    }        
  });

/* Create a secret with name */
secretCommand.command('create <name>')
  .description('Create a new secret')
  .option('-A, --artifact', 'Secret of ARTIFACT type')
  .option('-B, --backend', 'Secret of BACKEND type')
  .option('-d, --description <description>', 'Description for the secret')
  .option('-u, --username <username>', 'Username for the secret (if applicable)')
  .option('-p, --password <password>', 'Password for the secret (if applicable)')
  .option('-t, --token <token>', 'Token for the secret (if applicable)')
  .option('-h, --tokenHeader <tokenHeader>', 'Token Header for the secret (if not Authorization Bearer)')
  .option('-c, --certificate <path>', 'Path to the certificate file in PEM format (if applicable)')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (name, options) => {
    // Initialize the secret object.
    let secret : any = {
      name: name,
      description: options.description || '',
    }
    // Populate the secret object based on the options provided.
    if (options.artifact) {
      secret.type = 'ARTIFACT';
    } else if (options.backend) {
      secret.type = 'ENDPOINT';
    }
    if (options.username) {
      secret.username = options.username;
    }
    if (options.password) {
      secret.password = options.password;
    }
    if (options.token) {
      secret.token = options.token;
    }
    if (options.tokenHeader) {
      secret.tokenHeader = options.tokenHeader;
    }
    if (options.certificate) {
      // Read the certificate file and put it into a string.
      if (!fs.existsSync(options.certificate)) {
        Logger.error(`Certificate file not found: ${options.certificate}`);
        process.exit(1);
      }
      secret.certPem = fs.readFileSync(options.certificate, 'utf8');
    }

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/secrets`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${ConfigUtil.config.token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(secret)
    });

    if (!response.ok) {
      if (response.status === 409) {
        Logger.error(`A secret with the name "${name}" already exists. Please choose a different name.`);
      } else {
        Logger.error('Creating secret failed: ' + response.statusText);
      }
      process.exit(1);
    }

    const data = await response.json();
    Context.put('secret', data);
    Logger.success(`Secret ${data.name} created successfully with ID: ${data.id}`);
});

/* Create an elicitation secret with name */
secretCommand.command('create-elicitation <name>')
  .description('Create a new Elicitation secret')
  .option('-d, --description <description>', 'Description for the Elicitation secret')
  .option('-t, --token <token>', 'Token for the Elicitation secret (if sensitive data access is needed)')
  .option('--oc, --oauth2ClientID <oauth2ClientID>', 'The ClientID for the backend Authorization service (if OAuth2 is used)')
  .option('--ocs, --oauth2ClientSecret <oauth2ClientSecret>', 'The ClientSecret for the backend Authorization service (if OAuth2 is used and if needed by the Authorization service)')
  .option('--oae, --oauth2AuthorizationEndpoint <authorizationEndpoint>', 'Authorization Endpoint for the backend authentication (including query parameters without clientID and redirect_uri)') 
  .option('--ote, --oauth2TokenEndpoint <tokenEndpoint>', 'Token exchange Endpoint for backend authentication (if OAuth2 is used)')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (name, options) => {
    // Initialize the secret object.
    let secret : any = {
      name: name,
      description: options.description || '',
      type: 'ENDPOINT',
      useElicitation: true,
    }
    if (options.token) {
      secret.tokenHeader = options.token;
    } else {
      // OAuth2 based elicitation.
      if (!options.oauth2ClientID || !options.oauth2AuthorizationEndpoint || !options.oauth2TokenEndpoint) {
        Logger.error('OAuth2 based elicitation requires oauth2ClientID, oauth2AuthorizationEndpoint and oauth2TokenEndpoint to be provided.');
        process.exit(1);
      }
      secret.oauth2ClientConfiguration = {
        clientId: options.oauth2ClientID,
        clientSecret: options.oauth2ClientSecret,
        authorizationEndpoint: options.oauth2AuthorizationEndpoint,
        tokenEndpoint: options.oauth2TokenEndpoint
      }
    }

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/secrets`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${ConfigUtil.config.token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(secret)
    });

    if (!response.ok) {
      Logger.error('Creating secret failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json();
    Context.put('secret', data);
    Logger.success(`Elicitation secret ${data.name} created successfully with ID: ${data.id}`);
  });

/* Create an OAuth2 Client Credentials secret with name */
secretCommand.command('create-client-credentials <name>')
  .description('Create a new OAuth2 Client Credentials secret (machine-to-machine backend authentication)')
  .option('-d, --description <description>', 'Description for the Client Credentials secret')
  .option('--oc, --oauth2ClientID <oauth2ClientID>', 'The ClientID for the backend Authorization service')
  .option('--ocs, --oauth2ClientSecret <oauth2ClientSecret>', 'The ClientSecret for the backend Authorization service (may reference an environment variable, e.g. ${env:MY_SECRET})')
  .option('--ote, --oauth2TokenEndpoint <tokenEndpoint>', 'Token exchange Endpoint for backend authentication')
  .option('--os, --oauth2Scopes <scopes>', 'Comma or space separated list of OAuth2 scopes to request (optional)')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (name, options) => {
    if (!options.oauth2ClientID || !options.oauth2TokenEndpoint) {
      Logger.error('OAuth2 Client Credentials requires oauth2ClientID and oauth2TokenEndpoint to be provided.');
      process.exit(1);
    }

    // Parse the optional scopes into an array (comma or whitespace separated).
    const scopes = (options.oauth2Scopes || '')
      .split(/[\s,]+/)
      .map((s: string) => s.trim())
      .filter((s: string) => s.length > 0);

    // Initialize the secret object.
    const secret: any = {
      name: name,
      description: options.description || '',
      type: 'ENDPOINT',
      authMethod: OAUTH2_CLIENT_CREDENTIALS,
      useElicitation: false,
      oauth2ClientConfiguration: {
        clientId: options.oauth2ClientID,
        clientSecret: options.oauth2ClientSecret,
        tokenEndpoint: options.oauth2TokenEndpoint,
        scopes: scopes.length > 0 ? scopes : undefined,
      },
    };

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/secrets`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${ConfigUtil.config.token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(secret)
    });

    if (!response.ok) {
      if (response.status === 409) {
        Logger.error(`A secret with the name "${name}" already exists. Please choose a different name.`);
      } else {
        Logger.error('Creating secret failed: ' + response.statusText);
      }
      process.exit(1);
    }

    const data = await response.json();
    Context.put('secret', data);
    Logger.success(`Client Credentials secret ${data.name} created successfully with ID: ${data.id}`);
  });

/** Update secret by ID */
secretCommand.command('update <id>')
  .description('Update a secret by ID')
  .action(async (id) => {
    try {
      // First, fetch the current secret
      const response = await fetch(`${ConfigUtil.config.server}/api/v1/secrets/${id}`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${ConfigUtil.config.token}`
        }
      });

      if (!response.ok) {
        Logger.error('Fetching secret failed: ' + response.statusText);
        process.exit(1);
      }

      const secret = await response.json();
      Logger.info(`Opening editor for secret: ${secret.name}`);

      await openUpdateEditor(secret, async (modifiedSecret: any) => {
        // Enforce properties that are immutable.
        modifiedSecret.id = secret.id; // Ensure the ID remains the same.
        modifiedSecret.organizationId = secret.organizationId; // Ensure the organization ID remains the same.

        const updateResponse = await fetch(`${ConfigUtil.config.server}/api/v1/secrets/${id}`, {
          method: 'PUT',
          headers: {
            'Authorization': `Bearer ${ConfigUtil.config.token}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify(modifiedSecret)
        });

        if (!updateResponse.ok) {
          Logger.error('Updating secret failed: ' + updateResponse.statusText);
          process.exit(1);
        }

        Logger.success(`Secret updated successfully: ${id}`);
      });

    } catch (error) {
      Logger.error('Updating secret failed: ' + (error as Error).message);
      process.exit(1);
    }
  });

/** Delete secret by ID */
secretCommand.command('delete <id>')
  .description('Delete a secret by ID')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/secrets/${id}`, {
        method: 'DELETE',
        headers: {
            'Authorization': `Bearer ${ConfigUtil.config.token}`
        }
    });
    if (!response.ok) {
      Logger.error('Deleting secret failed: ' + response.statusText);
      process.exit(1);
    }
    Logger.success(`Secret deleted successfully: ${id}`);
});