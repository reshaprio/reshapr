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
import { createAdminEncryptionCommand } from './encryption.js';
import { createAdminMembershipCommand } from './membership.js';
import { createAdminOrganizationCommand } from './organization.js';
import { createAdminQuotaCommand } from './quota.js';
import { createAdminServiceAccountCommand } from './service-account.js';
import { createAdminUserCommand } from './user.js';

export function createAdminCommand(): Command {
  return new Command('admin')
    .description('Manage the reShapr control plane')
    .option('--admin-api-key <key>', 'Admin API key (overrides RESHAPR_ADMIN_API_KEY)')
    .option('-s, --server <url>', 'Control-plane server URL (overrides the saved login server)')
    .option('-k, --insecure', 'Skip SSL certificate validation')
    .addCommand(createAdminUserCommand())
    .addCommand(createAdminOrganizationCommand())
    .addCommand(createAdminQuotaCommand())
    .addCommand(createAdminMembershipCommand())
    .addCommand(createAdminServiceAccountCommand())
    .addCommand(createAdminEncryptionCommand());
}

export const adminCommand = createAdminCommand();
