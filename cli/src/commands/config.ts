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
import inquirer from 'inquirer';
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { openUpdateEditor, readInlineDefinition, applyMergePatch } from "../utils/editor.js";
import { Context } from "../utils/context.js";
import { CLI_LABEL } from '../constants.js';
import type { ConfigurationPlan, HeaderPolicy, HeaderRename, HeaderRules, ToolExposureMode } from '../types/models.js';

export const configCommand = program.command('config')
  .description(`Manage configuration plans in ${CLI_LABEL}`);

/** List all configuration plans */
configCommand.command('list')
  .description('List all configuration plans')
  .option('-s, --serviceId <id>', 'Filter by service ID')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async () => {
    
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching configuration plans failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json().catch(err => {
      Logger.error('Error parsing configuration plans response: ' + err.message);
    });
    
    if (data != null ) {
      if (data.length === 0) {
        Logger.info('No configuration plans found.');
      } else {
        Context.put('configurationPlans', data);
        const longestName = longestCPName(data); // +1 for padding
        const longestEndpoint = Math.max(...data.map((config: {backendEndpoint: string}) => config.backendEndpoint.length)) + 1; // +1 for padding

        Logger.log(`${'ID'.padEnd(13, ' ')}  ${'NAME'.padEnd(longestName, ' ')} ${'SERVICE'.padEnd(14, ' ')} ${'BACKEND'.padEnd(longestEndpoint, ' ')} API_KEY  OAUTH2_CONFIG  AUDIT`);
        data.forEach((config: ConfigurationPlan) => {
          Logger.log(`${config.id}  ${config.name.padEnd(longestName, ' ')} ${config.serviceId.padEnd(14, ' ')} ${config.backendEndpoint.padEnd(longestEndpoint, ' ')} ${(config.apiKey != undefined ? 'Yes' : 'No').padEnd(8, ' ')} ${(config.oauth2Configuration != undefined ? 'Yes' : 'No').padEnd(14, ' ')} ${config.audit ? 'Yes' : 'No'}`);
        });
      }
    }
  });

function longestCPName(expos: ConfigurationPlan[]) {
  return expos.reduce((max, config) => {
    return Math.max(max, config.name.length + 1);
  }, 0);
}

/** Get configuration plan by ID */
configCommand.command('get <id>')
  .description('Get configuration plan by ID')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans/${id}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching configuration plan failed: ' + response.statusText);
      process.exit(1);
    }

    const config: ConfigurationPlan = await response.json();
    Context.put('configurationPlan', config);

    Logger.info('Configuration plan details');
    Logger.log(`ID              : ${config.id}`);
    Logger.log(`Name            : ${config.name}`);
    Logger.log(`Organization    : ${config.organizationId}`);
    Logger.log(`Description     : ${config.description}`);
    Logger.log(`Service ID      : ${config.serviceId}`);
    Logger.log(`Backend Endpoint: ${config.backendEndpoint}`);
    if (config.backendTimeout) {
      Logger.log(`Backend Timeout : ${config.backendTimeout} ms`);
    }
    Logger.log(`Included Ops.   : ${JSON.stringify(config.includedOperations || [])}`);
    Logger.log(`Excluded Ops.   : ${JSON.stringify(config.excludedOperations || [])}`);
    Logger.log(`Included Artif. : ${(config.includedArtifacts && config.includedArtifacts.length > 0) ? JSON.stringify(config.includedArtifacts) : 'all attached artifacts'}`);
    Logger.log(`Backend Secret  : ${config.backendSecretId != undefined ? config.backendSecretId : 'No'}`);
    Logger.log(`API Key         : ${config.apiKey != undefined ? config.apiKey : 'No'}`);
    if (config.oauth2Configuration) {
      Logger.bold('OAuth2:');
      Logger.log(`  Authorization Servers: ${config.oauth2Configuration.authorizationServers.join(', ')}`);
      Logger.log(`  JKWS URI             : ${config.oauth2Configuration.jwksUri}`);
      if (config.oauth2Configuration.scopes) {
        Logger.log(`  Scopes               : ${config.oauth2Configuration.scopes.join(', ')}`);
      }
      if (config.oauth2Configuration.disableAudienceValidation) {
        Logger.log(`  Disable Audience Val.: Yes`);
      } else {
        if (config.oauth2Configuration.staticAudiences) {
          Logger.log(`  Static Audiences     : ${config.oauth2Configuration.staticAudiences.join(', ')}`);
        }
      }
    } else {
      Logger.log(`OAuth2          : No`);
    }
    Logger.log(`Audit           : ${config.audit ? 'Yes' : 'No'}`);
    Logger.log(`Tool Exposure   : ${config.toolExposureMode || 'TOOLS'}`);
    printCachePolicy(config);
    printHeaderPolicy(config);
  });

/** Create a new configuration plan */
configCommand.command('create <name>')
  .description('Create a new configuration plan')
  .requiredOption('-s, --serviceId <serviceId>', 'ID of the service')
  .option('-d, --description <text>', 'Description of the configuration plan')
  .requiredOption('--be, --backendEndpoint <backendEndpointURL>', 'Backend endpoint URL')
  .option('--bs, --backendSecret <backendSecretId>', 'ID of the secret to authenticate with the backend endpoint')
  .option('--bt, --backendTimeout <backendTimeout>', 'Timeout in milliseconds for requests to the backend endpoint', (value) => {
    const timeoutMs = parseInt(value, 10);
    if (isNaN(timeoutMs) || timeoutMs < 0) {
      Logger.error('backendTimeout must be a positive number representing milliseconds');
      process.exit(1);
    }
    return value;
  })
  .option('--filter', 'Filter operations to include or exclude in the configuration plan')
  .option('--io, --includedOperations [<operation1>, <operation2>]', 'Include these operations when importing service artifact (JSON array). Takes precedence over excludedOperations.')
  .option('--eo, --excludedOperations [<operation1>, <operation2>]', 'Exclude these operations when importing service artifact (JSON array). Only considered if no includedOperations.')
  .option('--ia, --includedArtifacts [<artifact1>, <artifact2>]', 'Include only these attached artifact names in the plan (JSON array). Empty/absent means all the attached artifacts of the service apply.')
  .option('--apiKey', 'Generate an API key for this configuration plan to secure the MCP endpoint')
  .option('--audit', 'Enable audit logging for this configuration plan')
  .option('--ct, --cacheTtl <cacheTtlMs>', 'Cache TTL in milliseconds', '30000')
  .option('--cs, --cacheScope <cacheScope>', 'Cache scope (e.g. public, private)', 'public')
  .option('--reqhp, --requestHeaderPolicy <json>', 'Request header propagation policy as a JSON object with optional allow, deny and rename fields (e.g. \'{"allow":["X-Trace-Id"],"deny":["Cookie"],"rename":["X-Authorization:Authorization"]}\'). Mutually exclusive with --passthrough.')
  .option('--reshp, --responseHeaderPolicy <json>', 'Response header propagation policy as a JSON object with optional allow, deny and rename fields. Reserved for future use (not enforced by the gateway yet).')
  .option('--passthrough', 'Forward the incoming Authorization header to the backend (shortcut adding Authorization to the request header allow-list). Mutually exclusive with --requestHeaderPolicy. Not recommended outside development or debugging.')
  .option('--tem, --toolExposureMode <mode>', 'How tools are advertised to MCP clients: TOOLS (one tool per operation, default), CODE (only the Code Mode meta-tools) or HYBRID (both).')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (name, options) => {
    if (!options.serviceId) {
      Logger.error('Service ID is required to create a configuration plan.');
      process.exit(1);
    }
    // Manage filter, included and excluded operations.
    await manageInclusionsAndExclusions(options);

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name: name,
        serviceId: options.serviceId,
        description: options.description,
        backendEndpoint: options.backendEndpoint,
        backendSecretId: options.backendSecret || undefined,
        backendTimeout: options.backendTimeout || undefined,
        includedOperations: options.includedOps || undefined,
        excludedOperations: options.excludedOps || undefined,
        includedArtifacts: options.includedArtifacts ? getArrayOfStrings(options.includedArtifacts, 'includedArtifacts') : undefined,
        apiKey: (options.apiKey ? 'generate-me' : undefined),
        initialAccessToken: (options.internalOAuth2 ? 'generate-me' : undefined),
        cachePolicy: {
          ttlMs: parseInt(options.cacheTtl, 10),
          cacheScope: options.cacheScope
        },
        headerPolicy: buildHeaderPolicy(options),
        toolExposureMode: parseToolExposureMode(options.toolExposureMode),
        audit: options.audit || false
      })
    });
    if (!response.ok) {
      Logger.error('Creating configuration plan failed: ' + response.statusText);
      process.exit(1);
    }

    const config: ConfigurationPlan = await response.json();
    Logger.success(`Configuration plan '${config.name}' created successfully with ID: ${config.id}`);
    Context.put('configurationPlan', config);

    if (options.apiKey) {
      Logger.warn(`The API Key to access future expositions is: ${config.apiKey}`);
      Logger.warn('Make sure to store it securely, as it will not be shown again.');
    }
  });

/** Create a new configuration plan with oauth */
configCommand.command('create-oauth <name>')
  .description('Create a new configuration plan with custom OAuth2 authorization')
  .requiredOption('-s, --serviceId <serviceId>', 'ID of the service')
  .option('-d, --description <text>', 'Description of the configuration plan')
  .requiredOption('--be, --backendEndpoint <backendEndpointURL>', 'Backend endpoint URL')
  .option('--bs, --backendSecret <backendSecretId>', 'ID of the secret to authenticate with the backend endpoint')
  .option('--bt, --backendTimeout <backendTimeout>', 'Timeout in milliseconds for requests to the backend endpoint', (value) => {
    const timeoutMs = parseInt(value, 10);
    if (isNaN(timeoutMs) || timeoutMs < 0) {
      Logger.error('backendTimeout must be a positive number representing milliseconds');
      process.exit(1);
    }
    return value;
  })
  .option('--filter', 'Filter operations to include or exclude in the configuration plan')
  .option('--io, --includedOperations [<operation1>, <operation2>]', 'Include these operations when importing service artifact (JSON array). Takes precedence over excludedOperations.')
  .option('--eo, --excludedOperations [<operation1>, <operation2>]', 'Exclude these operations when importing service artifact (JSON array). Only considered if no includedOperations.')
  .option('--ia, --includedArtifacts [<artifact1>, <artifact2>]', 'Include only these attached artifact names in the plan (JSON array). Empty/absent means all the attached artifacts of the service apply.')
  .requiredOption('--oas, --oauth2AuthorizationServers [<authorizationServer1>, <authorizationServer2>]', 'A list of OAuth2 authorization server URLs to accept tokens from')
  .requiredOption('--oju, --oauth2jwksUri <jwksUri>', 'The JWKS URI to validate OAuth2 tokens')
  .option('--osc, --oauth2Scopes [<scope1>, <scope2>]', 'A list of OAuth2 scopes to enforce presence in the access token')
  .option('--odav, --oauth2DisableAudienceValidation', 'Disable audience validation for OAuth2 tokens')
  .option('--osa, --oauth2StaticAudiences [<audience1>, <audience2>]', 'A list of static audiences to validate against (JSON array)')
  .option('--audit', 'Enable audit logging for this configuration plan')
  .option('--ct, --cacheTtl <cacheTtlMs>', 'Cache TTL in milliseconds', '30000')
  .option('--cs, --cacheScope <cacheScope>', 'Cache scope (e.g. public, private)', 'public')
  .option('--reqhp, --requestHeaderPolicy <json>', 'Request header propagation policy as a JSON object with optional allow, deny and rename fields (e.g. \'{"allow":["X-Trace-Id"],"deny":["Cookie"],"rename":["X-Authorization:Authorization"]}\'). Mutually exclusive with --passthrough.')
  .option('--reshp, --responseHeaderPolicy <json>', 'Response header propagation policy as a JSON object with optional allow, deny and rename fields. Reserved for future use (not enforced by the gateway yet).')
  .option('--passthrough', 'Forward the incoming Authorization header to the backend (shortcut adding Authorization to the request header allow-list). Mutually exclusive with --requestHeaderPolicy. Not recommended outside development or debugging.')
    .option('--tem, --toolExposureMode <mode>', 'How tools are advertised to MCP clients: TOOLS (one tool per operation, default), CODE (only the Code Mode meta-tools) or HYBRID (both).')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (name, options) => {
    if (!options.serviceId) {
      Logger.error('Service ID is required to create a configuration plan.');
      process.exit(1);
    }
    // Manage filter, included and excluded operations.
    await manageInclusionsAndExclusions(options);

    options.oauth2Configuration = {
      authorizationServers: getArrayOfStrings(options.oauth2AuthorizationServers, 'oauth2AuthorizationServers'),
      jwksUri: options.oauth2jwksUri,
      scopes: options.oauth2Scopes ? getArrayOfStrings(options.oauth2Scopes, 'oauth2Scopes') : undefined,
      disableAudienceValidation: options.oauth2DisableAudienceValidation || false,
      staticAudiences: options.oauth2StaticAudiences ? getArrayOfStrings(options.oauth2StaticAudiences, 'oauth2StaticAudiences') : undefined
    };
    
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name: name,
        serviceId: options.serviceId,
        description: options.description,
        backendEndpoint: options.backendEndpoint,
        backendSecretId: options.backendSecret || undefined,
        backendTimeout: options.backendTimeout || undefined,
        includedOperations: options.includedOps || undefined,
        excludedOperations: options.excludedOps || undefined,
        includedArtifacts: options.includedArtifacts ? getArrayOfStrings(options.includedArtifacts, 'includedArtifacts') : undefined,
        oauth2Configuration: options.oauth2Configuration,
        cachePolicy: {
          ttlMs: parseInt(options.cacheTtl, 10),
          cacheScope: options.cacheScope
        },
        headerPolicy: buildHeaderPolicy(options),
        toolExposureMode: parseToolExposureMode(options.toolExposureMode),
        audit: options.audit || false
      })
    });
    if (!response.ok) {
      Logger.error('Creating configuration plan failed: ' + response.statusText);
      process.exit(1);
    }

    const config: ConfigurationPlan = await response.json();
    Logger.success(`Configuration plan '${config.name}' created successfully with ID: ${config.id}`);
    Context.put('configurationPlan', config);

    if (options.apiKey) {
      Logger.warn(`The API Key to access future expositions is: ${config.apiKey}`);
      Logger.warn('Make sure to store it securely, as it will not be shown again.');
    }
  });

configCommand.command('update <id>')
  .description('Update configuration plan by ID (opens an editor unless --patch, --file or --data is provided)')
  .option('-f, --file <path>', 'Path to a JSON file with the complete definition (\'-\' for stdin)')
  .option('-d, --data <json>', 'Inline JSON with the complete definition')
  .option('-p, --patch <json>', 'Inline JSON merge patch (RFC 7396) applied on the current definition; null removes a property')
  .action(async (id: string, options: { file?: string, data?: string, patch?: string }) => {
    try {
      // First, fetch the current configuration plan
      const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans/${id}`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${ConfigUtil.config.token}`
        }
      });

      if (!response.ok) {
        Logger.error('Fetching configuration plan failed: ' + response.statusText);
        process.exit(1);
      }

      const config: ConfigurationPlan = await response.json();

      const applyUpdate = async (modifiedConfig: ConfigurationPlan) => {
        // Enforce properties that are immutable.
        modifiedConfig.id = config.id; // Ensure the ID remains the same.
        modifiedConfig.organizationId = config.organizationId; // Ensure the organization ID remains the same.
        
        const updateResponse = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans/${id}`, {
          method: 'PUT',
          headers: {
            'Authorization': `Bearer ${ConfigUtil.config.token}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify(modifiedConfig)
        });
        
        if (!updateResponse.ok) {
          Logger.error('Updating configuration plan failed: ' + updateResponse.statusText);
          process.exit(1);
        }

        Logger.success(`Configuration plan ${id} updated successfully.`);
      };

      if (options.patch && (options.file || options.data)) {
        throw new Error('Option --patch cannot be combined with --file or --data');
      }
      if (options.patch) {
        await applyUpdate(applyMergePatch(config, options.patch));
      } else if (options.file || options.data) {
        await applyUpdate(await readInlineDefinition<ConfigurationPlan>(options));
      } else {
        Logger.info(`Opening editor for configuration plan: ${config.name}`);
        await openUpdateEditor(config, applyUpdate);
      }
    } catch (error) {
      Logger.error('Updating configuration plan failed: ' + (error as Error).message);
      process.exit(1);
    }
  });

/** Renew or add ApiKey on configuration plan by ID */
configCommand.command('renew-api-key <id>')
  .description('Renew or add ApiKey on configuration plan by ID')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (id) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans/${id}/renewApiKey`, {
      method: 'PUT',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Renewing API key failed: ' + response.statusText);
      process.exit(1);
    }

    const config: ConfigurationPlan = await response.json();
    Logger.warn(`The API Key to access future expositions is: ${config.apiKey}`);
    Logger.warn('Make sure to store it securely, as it will not be shown again.');
    Context.put('configurationPlan', config);
  });

/** Delete configuration plan by ID */
configCommand.command('delete <id>')
  .option('-f, --force', 'Skip confirmation prompt')
  .description('Delete configuration plan by ID')
  .action(async (id, options) => {
    if (!options.force) {
      const confirm = await inquirer.prompt({
        type: 'confirm',
        name: 'confirm',
        message: 'Deleting this config plan may also remove associated expositions. Are you sure you want to proceed?',
        default: false
      });
      if (!confirm.confirm) {
        Logger.info('Deletion cancelled.');
        return;
      }
    }

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans/${id}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Deleting configuration plan failed: ' + response.statusText);
      process.exit(1);
    }

    Logger.success(`Configuration plan ${id} deleted successfully.`);
  });

/** Duplicate configuration plan by ID */
configCommand.command('duplicate <id>')
  .description('Duplicate configuration plan by ID')
  .requiredOption('-n, --name <newName>', 'Name for the duplicated configuration plan')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (id, options) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans/${id}/duplicate?name=${encodeURIComponent(options.name)}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Duplicating configuration plan failed: ' + response.statusText);
      process.exit(1);
    }

    const config: ConfigurationPlan = await response.json();
    Logger.success(`Configuration plan '${config.name}' duplicated successfully with ID: ${config.id}`);
    Context.put('configurationPlan', config);

    if (config.apiKey) {
      Logger.warn(`The API Key to access future expositions is: ${config.apiKey}`);
      Logger.warn('Make sure to store it securely, as it will not be shown again.');
    }
    
    if (config.initialAccessToken) {
      Logger.warn(`The initial access token for OAuth2 clients is: ${config.initialAccessToken}`);
      Logger.warn('Make sure to store it securely, as it will not be shown again.');
    }
  });


async function manageInclusionsAndExclusions(options: {filter?: boolean; serviceId: string; includedOps?: string[]; excludedOps?: string[]; includedOperations?: unknown; excludedOperations?: unknown}) {
  if (options.filter) {
    // We must retrieve the available operations to filter
    const opsResponse = await fetch(`${ConfigUtil.config.server}/api/v1/services/${options.serviceId}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!opsResponse.ok) {
      Logger.error('Fetching service operations failed: ' + opsResponse.statusText);
      process.exit(1);
    }
    const service = await opsResponse.json();
    console.log(`The service ${service.name} has ${service.operations.length} operation(s) available. You can filter them to include or exclude specific operations.`);
    const includeOrExclude = await inquirer.prompt({
      type: 'list',
      name: 'includeOrExclude',
      message: 'Do you want to include or exclude operations?',
      choices: [
        { name: 'No', value: 'no' },
        { name: 'Include operations', value: 'include' },
        { name: 'Exclude operations', value: 'exclude' }
      ]
    });

    if (includeOrExclude.includeOrExclude === 'no') {
      options.includedOps = [];
      options.excludedOps = [];
    } else {
      const opsChoices = service.operations.map((op: {name: string}) => ({
        name: op.name,
        value: op.name
      }));
      // Sort by operation path if OpenAPI.
      if (service.type === 'REST') {
        opsChoices.sort(function(x: { value: string; }, y: { value: string; }) {
          const pathX = x.value.split('/')[1];
          const pathY = y.value.split('/')[1];
          return pathX.localeCompare(pathY);
        });
      } else {
        // Sort alphabetically for other types.
        opsChoices.sort(); 
      }
      const selectedOps = await inquirer.prompt({
        type: 'checkbox',
        name: includeOrExclude.includeOrExclude === 'include' ? 'includedOps' : 'excludedOps',
        message: `Select operations to ${includeOrExclude.includeOrExclude}:`,
        choices: opsChoices,
        loop: false,
        pageSize: 10
      });
      options[includeOrExclude.includeOrExclude === 'include' ? 'includedOps' : 'excludedOps'] = 
          selectedOps[includeOrExclude.includeOrExclude === 'include' ? 'includedOps' : 'excludedOps'];
    }
  } else {
    if (options.includedOperations) {
      const operations: string[] = getArrayOfStrings(options.includedOperations, 'includedOperations');
      options.includedOps = operations;
    }
    if (!options.includedOperations && options.excludedOperations) {
      const operations: string[] = getArrayOfStrings(options.excludedOperations, 'excludedOperations');
      options.excludedOps = operations;
    }
  }
} 

function getArrayOfStrings(input: unknown, name: string): string[] {
  if (Array.isArray(input)) {
    return input;
  }
  if (typeof input === 'string') {
    try {
      const parsed = JSON.parse(input);
      if (Array.isArray(parsed)) {
        return parsed;
      }
    } catch {
      // Falls through to the error below.
    }
  }
  Logger.error(`Input must be a JSON array of strings for ${name}.`);
  process.exit(1);
  return [];
}

/**
 * Build the header propagation policy from CLI options. The `--passthrough` shortcut adds the
 * `Authorization` header to the request allow-list so the incoming Authorization header is
 * forwarded to the backend. The `--requestHeaderPolicy`/`--responseHeaderPolicy` flags each accept
 * a JSON object ({ allow, deny, rename }) mapped 1:1 to the API contract. `--passthrough` is
 * mutually exclusive with `--requestHeaderPolicy`. Returns undefined when no directive applies.
 */
function buildHeaderPolicy(options: {passthrough?: boolean; requestHeaderPolicy?: unknown; responseHeaderPolicy?: unknown}): HeaderPolicy | undefined {
  if (options.passthrough && options.requestHeaderPolicy !== undefined) {
    Logger.error('The --passthrough flag is mutually exclusive with --requestHeaderPolicy. Use either the shortcut or the explicit request header policy.');
    process.exit(1);
  }

  const policy: HeaderPolicy = {};
  if (options.passthrough) {
    policy.request = { allow: ['Authorization'] };
  } else if (options.requestHeaderPolicy !== undefined) {
    policy.request = parseHeaderRules(options.requestHeaderPolicy, 'requestHeaderPolicy');
  }
  if (options.responseHeaderPolicy !== undefined) {
    policy.response = parseHeaderRules(options.responseHeaderPolicy, 'responseHeaderPolicy');
  }

  return Object.keys(policy).length > 0 ? policy : undefined;
}

/** Parse a JSON object into a set of allow/deny/rename directives for a single direction. */
function parseHeaderRules(input: unknown, name: string): HeaderRules {
  let parsed: unknown;
  try {
    parsed = typeof input === 'string' ? JSON.parse(input) : input;
  } catch {
    Logger.error(`Input for --${name} must be a JSON object with optional allow, deny and rename fields.`);
    process.exit(1);
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    Logger.error(`Input for --${name} must be a JSON object with optional allow, deny and rename fields.`);
    process.exit(1);
  }
  const parsedRules = parsed as { allow?: unknown; deny?: unknown; rename?: unknown };
  const rules: HeaderRules = {};
  if (parsedRules.allow !== undefined) {
    rules.allow = getArrayOfStrings(parsedRules.allow, `${name}.allow`);
  }
  if (parsedRules.deny !== undefined) {
    rules.deny = getArrayOfStrings(parsedRules.deny, `${name}.deny`);
  }
  if (parsedRules.rename !== undefined) {
    rules.rename = parseHeaderRenames(parsedRules.rename, name);
  }
  return rules;
}

/**
 * Parse rename directives accepting either 'From-Header:To-Header' strings or
 * { from, to } objects, normalizing them to { from, to }.
 */
function parseHeaderRenames(input: unknown, name: string): HeaderRename[] {
  if (!Array.isArray(input)) {
    Logger.error(`The rename field of --${name} must be a JSON array.`);
    process.exit(1);
  }
  return input.map((entry: unknown) => {
    if (typeof entry === 'string') {
      const idx = entry.indexOf(':');
      if (idx <= 0 || idx === entry.length - 1) {
        Logger.error(`Invalid header rename rule '${entry}'. Expected format 'From-Header:To-Header'.`);
        process.exit(1);
      }
      return { from: entry.slice(0, idx).trim(), to: entry.slice(idx + 1).trim() };
    }
    if (entry && typeof entry === 'object' && typeof (entry as HeaderRename).from === 'string' && typeof (entry as HeaderRename).to === 'string') {
      return { from: (entry as HeaderRename).from, to: (entry as HeaderRename).to };
    }
    Logger.error(`Invalid header rename rule '${JSON.stringify(entry)}'. Expected 'From-Header:To-Header' or { "from": ..., "to": ... }.`);
    process.exit(1);
    return { from: '', to: '' };
  });
}

/** Validate and normalize the --toolExposureMode option, returning undefined when it is not set. */
function parseToolExposureMode(input: unknown): ToolExposureMode | undefined {
  if (input === undefined || input === null || input === '') {
    return undefined;
  }
  const mode = String(input).toUpperCase();
  if (mode !== 'TOOLS' && mode !== 'CODE' && mode !== 'HYBRID') {
    Logger.error(`Invalid --toolExposureMode '${input}'. Expected one of TOOLS, CODE or HYBRID.`);
    process.exit(1);
  }
  return mode as ToolExposureMode;
}

/** Print the header propagation policy of a configuration plan, when present. */
/** Print the caching configuration of a plan, when present. */
function printCachePolicy(config: ConfigurationPlan) {
  const policy = config.cachePolicy;
  if (!policy || (policy.ttlMs == undefined && !policy.cacheScope)) {
    return;
  }
  Logger.bold('Cache Policy:');
  if (policy.ttlMs != undefined) {
    Logger.log(`  TTL             : ${policy.ttlMs} ms`);
  }
  if (policy.cacheScope) {
    Logger.log(`  Scope           : ${policy.cacheScope}`);
  }
}

function printHeaderPolicy(config: ConfigurationPlan) {
  const policy = config.headerPolicy;
  if (!policy) {
    return;
  }
  printHeaderRules('Header Policy (request)', policy.request);
  printHeaderRules('Header Policy (response)', policy.response);
}

/** Print a single direction of allow/deny/rename directives, when present. */
function printHeaderRules(title: string, rules: HeaderRules | undefined) {
  if (!rules || (!rules.allow?.length && !rules.deny?.length && !rules.rename?.length)) {
    return;
  }
  Logger.bold(`${title}:`);
  if (rules.allow && rules.allow.length > 0) {
    Logger.log(`  Allow           : ${rules.allow.join(', ')}`);
  }
  if (rules.deny && rules.deny.length > 0) {
    Logger.log(`  Deny            : ${rules.deny.join(', ')}`);
  }
  if (rules.rename && rules.rename.length > 0) {
    Logger.log(`  Rename          : ${rules.rename.map((r: { from: string; to: string }) => `${r.from} -> ${r.to}`).join(', ')}`);
  }
}