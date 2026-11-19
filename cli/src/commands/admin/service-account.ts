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
import { Command } from 'commander';
import inquirer from 'inquirer';
import { Context } from '../../utils/context.js';
import { Logger } from '../../utils/logger.js';
import { adminOptions, parsePositiveInteger, runAdminAction } from './shared.js';
import { AdminCommandError, AdminConnectionOptions, adminDelete, adminRequest, parseStringArray } from './utils.js';

interface ServiceAccount {
  id: string;
  name: string;
  description?: string;
  k8sSubject?: string;
  allowedOrganizations?: string[];
  validUntil: string;
}

const DAY_IN_MS = 24 * 60 * 60 * 1000;

async function findServiceAccount(id: string, options: AdminConnectionOptions): Promise<ServiceAccount> {
  const serviceAccounts = await adminRequest<ServiceAccount[]>('serviceAccounts', options, 'GET');
  const serviceAccount = serviceAccounts.find(sa => sa.id === id);
  if (!serviceAccount) {
    throw new AdminCommandError(`Service account '${id}' not found.`);
  }
  return serviceAccount;
}

function remainingValidityDays(serviceAccount: ServiceAccount): number {
  const remainingMs = new Date(serviceAccount.validUntil).getTime() - Date.now();
  if (!(remainingMs > 0)) {
    throw new AdminCommandError(
      `Service account '${serviceAccount.name}' has expired. Use --validity-days to renew it.`
    );
  }
  return Math.ceil(remainingMs / DAY_IN_MS);
}

export function createAdminServiceAccountCommand(): Command {
  const serviceAccountCommand = new Command('service-account')
    .description('Manage service accounts');

  serviceAccountCommand.command('create <name>')
    .description('Create a service account')
    .requiredOption('--k8s-subject <subject>', 'Kubernetes subject in namespace:name format')
    .requiredOption('--allowed-organizations <json>', 'JSON array of organization names, or ["*"]')
    .requiredOption('--validity-days <days>', 'Number of days the service account remains valid')
    .option('-d, --description <description>', 'Service account description')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (name, options, command) => runAdminAction(async () => {
      const validityDays = parsePositiveInteger(options.validityDays, '--validity-days');
      const allowedOrganizations = parseStringArray(
        options.allowedOrganizations,
        '--allowed-organizations'
      );
      const serviceAccount = await adminRequest<ServiceAccount>(
        'serviceAccounts',
        adminOptions(command),
        'POST',
        {
          name,
          description: options.description,
          k8sSubject: options.k8sSubject,
          allowedOrganizations,
          validityDays
        }
      );
      Context.put('serviceAccount', serviceAccount);
      Logger.success(`Service account '${name}' created successfully.`);
    }));

  serviceAccountCommand.command('list')
    .description('List service accounts')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (options, command) => runAdminAction(async () => {
      const serviceAccounts = await adminRequest<ServiceAccount[]>(
        'serviceAccounts',
        adminOptions(command),
        'GET'
      );
      if (serviceAccounts.length === 0) {
        Logger.info('No service accounts found.');
        return;
      }
      Context.put('serviceAccounts', serviceAccounts);

      const longestName = Math.max(4, ...serviceAccounts.map(sa => sa.name.length)) + 1;
      const longestSubject = Math.max(11, ...serviceAccounts.map(sa => (sa.k8sSubject ?? '').length)) + 1;
      const longestOrgs = Math.max(13, ...serviceAccounts.map(sa => (sa.allowedOrganizations ?? []).join(',').length)) + 1;
      Logger.log(`${'ID'.padEnd(13, ' ')}  ${'NAME'.padEnd(longestName, ' ')} ${'K8S SUBJECT'.padEnd(longestSubject, ' ')} ${'ORGANIZATIONS'.padEnd(longestOrgs, ' ')} VALID UNTIL`);
      serviceAccounts.forEach(sa => {
        Logger.log(`${sa.id.padEnd(13, ' ')}  ${sa.name.padEnd(longestName, ' ')} ${(sa.k8sSubject ?? '').padEnd(longestSubject, ' ')} ${(sa.allowedOrganizations ?? []).join(',').padEnd(longestOrgs, ' ')} ${new Date(sa.validUntil).toUTCString()}`);
      });
    }));

  serviceAccountCommand.command('update <id>')
    .description('Update a service account by ID. Omitted options keep their current value.')
    .option('--k8s-subject <subject>', 'Kubernetes subject in namespace:name format')
    .option('--allowed-organizations <json>', 'JSON array of organization names, or ["*"]')
    .option('--validity-days <days>', 'Number of days from now the service account remains valid')
    .option('-d, --description <description>', 'Service account description')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (id: string, options, command) => runAdminAction(async () => {
      const connection = adminOptions(command);
      // No GET-by-id endpoint: current values are needed since PUT replaces every field.
      const existing = await findServiceAccount(id, connection);
      const validityDays = options.validityDays !== undefined
        ? parsePositiveInteger(options.validityDays, '--validity-days')
        : remainingValidityDays(existing);
      const allowedOrganizations = options.allowedOrganizations !== undefined
        ? parseStringArray(options.allowedOrganizations, '--allowed-organizations')
        : existing.allowedOrganizations;

      const serviceAccount = await adminRequest<ServiceAccount>(
        `serviceAccounts/${encodeURIComponent(existing.id)}`,
        connection,
        'PUT',
        {
          name: existing.name,
          description: options.description ?? existing.description,
          k8sSubject: options.k8sSubject ?? existing.k8sSubject,
          allowedOrganizations,
          validityDays
        }
      );
      Context.put('serviceAccount', serviceAccount);
      Logger.success(`Service account '${existing.name}' updated successfully.`);
    }));

  serviceAccountCommand.command('delete <serviceAccountId>')
    .description('Delete a service account by ID')
    .option('-f, --force', 'Skip the interactive confirmation prompt')
    .action(async (serviceAccountId: string, options, command) => runAdminAction(async () => {
      if (!options.force) {
        const answer = await inquirer.prompt({
          type: 'confirm',
          name: 'confirm',
          message: `This will permanently delete the service account '${serviceAccountId}'. Workloads using it will no longer be able to authenticate. Proceed?`,
          default: false
        });
        if (!answer.confirm) {
          Logger.info('Deletion cancelled.');
          return;
        }
      }

      await adminDelete(
        `serviceAccounts/${encodeURIComponent(serviceAccountId)}`,
        adminOptions(command)
      );
      Logger.success(`Service account '${serviceAccountId}' deleted successfully.`);
    }));

  return serviceAccountCommand;
}
