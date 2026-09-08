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
import { formatExpositionEndpoints, buildExpositionSlug } from "../utils/format.js";
import { Context } from "../utils/context.js";
import { readHttpErrorMessage } from "../utils/error.js";
import { CLI_LABEL } from '../constants.js';

// Kept in sync with GatewayGroup.DEFAULT_GATEWAY_GROUP_ID (control-plane) — provisioned by Flyway V1.0.0.
const DEFAULT_GATEWAY_GROUP_ID: string = '1';
const DEFAULT_PLAN_NAME: string = 'default'; // Name of the configuration plan created by `import --be`

export const importCommand = new Command('import')
  .description(`Import an artifact into ${CLI_LABEL}`)
  .option('-f, --file <file>', 'Path to the artifact file to import')
  .option('-u, --url <url>', 'URL of the artifact to import')
  .option('-s, --secret <artifactSecret>', 'Use a secret to authenticate the artifact to import')
  .option('--sn, --serviceName <name>', 'Set the service name (mandatory for GraphQL schema imports)')
  .option('--sv, --serviceVersion <version>', 'Set the service version (mandatory for GraphQL schema imports)')
  .option('--io, --includedOperations [<operation1>, <operation2>]', 'Include these operations when importing service artifact (JSON array). Takes precedence over excludedOperations.')
  .option('--eo, --excludedOperations [<operation1>, <operation2>]', 'Exclude these operations when importing service artifact (JSON array). Only considered if no includedOperations.')
  .option('--be, --backendEndpoint <backendEndpointURL>', 'Directly expose the artifact on a Gateway using a backend endpoint')
  .option('--bs, --backendSecret <backendSecretId>', 'ID of a secret to authenticate exposed MCP with backend endpoint')
  .option('--apiKey', 'Generate an API key for the configuration plan to secure the MCP endpoint')
  .option('--audit', 'Enable audit logging for the configuration plan')
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
      body.append('mainArtifact', 'true');
    } else if (options.url) {
      // We should encode in application/x-www-form-urlencoded
      body = new URLSearchParams();
      body.append('url', options.url);
      body.append('mainArtifact', 'true');
      if (options.secret) {
        body.append('secretName', options.secret);
      }
    }
    if (options.serviceName) {
      if (!options.serviceVersion || options.serviceName.trim() === '') {
        Logger.error('Service version cannot be empty when service name is provided.');
        process.exit(1);
      }
      body.append('serviceName', options.serviceName);
    }
    if (options.serviceVersion) {
      if (!options.serviceName || options.serviceName.trim() === '') {
        Logger.error('Service name cannot be empty when service version is provided.');
        process.exit(1);
      }
      body.append('serviceVersion', options.serviceVersion);
    }
    if (options.includedOperations) {
      let operations: string[] = getArrayOfStrings(options.includedOperations, 'includedOperations');
      for (const op of operations) {
        body.append('includedOperations', op);
      }
    }
    if (!options.includedOperations && options.excludedOperations) {
      let operations: string[] = getArrayOfStrings(options.excludedOperations, 'excludedOperations');
      for (const op of operations) {
        body.append('excludedOperations', op);
      }
    }

    const spinner = yoctoSpinner({text: 'Importing artifact...'}).start();

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/artifacts`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`,
      },
      body: body
    });
    if (!response.ok) {
      spinner.stop();
      Logger.error('Import failed: ' + await readHttpErrorMessage(response));
      process.exit(1);
    }

    const data = await response.json().catch(err => {
      Logger.error('Failed to parse response: ' + err.message);
      process.exit(1);
    });
    spinner.stop();

    Context.put('service', data);
    Logger.success('Import successful!');
    Logger.info(`Discovered Service ${data.name} with ID: ${data.id}`);

    if (options.backendEndpoint) {
      await exposeService(options, data);
    }
  });

async function exposeService(options: any, service: any) {
  if (!options.backendEndpoint) {
    return;
  }

  // Keep `import --be` idempotent: reuse the existing 'default' plan/exposition when present so that
  // re-importing the same spec only updates the service (which auto-propagates to the running gateway)
  // instead of creating duplicates — which would now fail on plan (service_id, name) and exposition
  // (organization_id, name) uniqueness constraints.
  const plan = await findOrCreateDefaultPlan(options, service);
  const exposition = await findOrCreateExposition(service, plan);

  await getActiveExposition(exposition);
}

function authHeaders(): Record<string, string> {
  return { 'Authorization': `Bearer ${ConfigUtil.config.token}` };
}

/** Return the existing 'default' configuration plan of the service, or undefined if none exists. */
async function findDefaultPlan(serviceId: string): Promise<any | undefined> {
  const response = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans?serviceId=${serviceId}`, {
    method: 'GET',
    headers: authHeaders()
  });
  if (!response.ok) {
    Logger.error('Failed to list configuration plans: ' + response.statusText);
    process.exit(1);
  }
  const plans = await response.json();
  return Array.isArray(plans) ? plans.find((plan: any) => plan.name === DEFAULT_PLAN_NAME) : undefined;
}

/** Reuse the existing 'default' plan of the service or create a new one. */
async function findOrCreateDefaultPlan(options: any, service: any): Promise<any> {
  const existing = await findDefaultPlan(service.id);
  if (existing) {
    Logger.info(`Reusing existing configuration plan '${existing.name}' (ID: ${existing.id}) for service ${service.name}.`);
    if (options.backendEndpoint && existing.backendEndpoint !== options.backendEndpoint) {
      Logger.warn(`The existing plan targets '${existing.backendEndpoint}'; the provided --backendEndpoint '${options.backendEndpoint}' is ignored. Use 'config update' to change it.`);
    }
    Context.put('configurationPlan', existing);
    return existing;
  }

  const planResponse = await fetch(`${ConfigUtil.config.server}/api/v1/configurationPlans`, {
    method: 'POST',
    headers: {
      ...authHeaders(),
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      serviceId: service.id,
      name: DEFAULT_PLAN_NAME,
      description: `Configuration plan for ${service.name} on ${options.backendEndpoint}`,
      backendEndpoint: options.backendEndpoint,
      backendSecretId: options.backendSecret || undefined,
      apiKey: (options.apiKey ? 'generate-me' : undefined),
      initialAccessToken: (options.internalOAuth2 ? 'generate-me' : undefined),
      audit: options.audit || false
    })
  });
  if (!planResponse.ok) {
    Logger.error('Failed to create a Config Plan for service: ' + planResponse.statusText);
    process.exit(1);
  }

  const planData = await planResponse.json();
  Context.put('configurationPlan', planData);

  if (options.apiKey) {
    Logger.warn(`The API Key to access future expositions is: ${planData.apiKey}`);
    Logger.warn('Make sure to store it securely, as it will not be shown again.');
  }
  return planData;
}

/** Return the existing exposition of the plan on the default gateway group, or undefined if none exists. */
async function findExpositionForPlan(serviceId: string, planId: string): Promise<any | undefined> {
  const response = await fetch(`${ConfigUtil.config.server}/api/v1/expositions?serviceId=${serviceId}`, {
    method: 'GET',
    headers: authHeaders()
  });
  if (!response.ok) {
    Logger.error('Failed to list expositions: ' + response.statusText);
    process.exit(1);
  }
  const expositions = await response.json();
  return Array.isArray(expositions)
    ? expositions.find((expo: any) => expo.configurationPlan?.id === planId && expo.gatewayGroup?.id === DEFAULT_GATEWAY_GROUP_ID)
    : undefined;
}

/** Reuse the existing exposition of the plan or create a new one on the default gateway group. */
async function findOrCreateExposition(service: any, plan: any): Promise<any> {
  const existing = await findExpositionForPlan(service.id, plan.id);
  if (existing) {
    Logger.info(`Reusing existing exposition '${existing.name || existing.id}'. The re-imported service update propagates to the gateway automatically.`);
    Context.put('exposition', existing);
    return existing;
  }

  // Propose a default exposition name (slug of service-version-plan) so the deterministic
  // /mcp/{org}/{exposition_name} endpoint is available out of the box (decision #4).
  const expositionName = buildExpositionSlug(service.name, service.version, plan.name);

  const exposeResponse = await fetch(`${ConfigUtil.config.server}/api/v1/expositions`, {
    method: 'POST',
    headers: {
      ...authHeaders(),
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      gatewayGroupId: DEFAULT_GATEWAY_GROUP_ID,
      configurationPlanId: plan.id,
      name: expositionName
    })
  });
  if (!exposeResponse.ok) {
    Logger.error('Failed to expose configuration: ' + exposeResponse.statusText);
    if (exposeResponse.status === 429) {
      Logger.error('Exposition creation quota exceeded. Check your quotas.');
    }
    process.exit(1);
  }

  const exposeData = await exposeResponse.json().catch(err => {
    Logger.error('Failed to parse exposition response: ' + err.message);
    process.exit(1);
  });
  Logger.success('Exposition done!');
  Context.put('exposition', exposeData);
  return exposeData;
}

async function getActiveExposition(exposition: any) {
  const activeResponse = await fetch(`${ConfigUtil.config.server}/api/v1/expositions/active/${exposition.id}`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${ConfigUtil.config.token}`
    }
  });
  if (activeResponse.status === 404) {
    Logger.warn(`No active exposition found for the Exposition ${exposition.id}. Maybe there's no running Gateway at the moment?`);
    process.exit(0);
  }
  if (!activeResponse.ok) {
    Logger.error('Failed to retrieve active exposition: ' + activeResponse.statusText);
    process.exit(1);
  }

  const data = await activeResponse.json().catch(err => {
    Logger.error('Failed to parse active exposition response: ' + err.message);
    process.exit(1);
  });
  Logger.success('Exposition is now active!');

  Logger.log(`Exposition ID  : ${data.id}`);
  Logger.log(`Exposition Name: ${exposition.name || '(unnamed)'}`);
  Logger.log(`Organization   : ${data.organizationId}`);
  Logger.log(`Created on     : ${data.createdOn}`);
  Logger.log(`Service ID     : ${data.service.id}`);
  Logger.log(`Service Name   : ${data.service.name}`);
  Logger.log(`Service Version: ${data.service.version}`);
  Logger.log(`Service Type   : ${data.service.type} -> ${data.configurationPlan.backendEndpoint}`);

  let allFqdns = uniqueFQDNs(data.gateways);
  Context.put('endpoints', allFqdns.flatMap(
    fqdn => formatExpositionEndpoints(fqdn, exposition)
  ));

  Logger.log(`Endpoints      : ${allFqdns.flatMap(
    (fqdn: string) => formatExpositionEndpoints(fqdn, exposition))
    .join(', ')}`);
}

function uniqueFQDNs(gateways: { fqdns: string[]; }[]): string[] {
  let allFqdns: string[] = [];
  gateways.forEach(gateway => {
    gateway.fqdns.filter(fqdn => !allFqdns.includes(fqdn)).forEach(fqdn => allFqdns.push(fqdn));
  });
  return allFqdns;
}

function getArrayOfStrings(input: any, name: string): string[] {
  if (Array.isArray(input)) {
    return input;
  } else {
    try {
      const parsed = JSON.parse(input);
      if (Array.isArray(parsed)) {
        return parsed;
      } else {
        throw new Error('Not an array');
      }
    } catch (err) {
      Logger.error(`Input must be a JSON array of strings for ${name}.`);
      process.exit(1);
    }
  }
  return [];
}