/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** Bootstrap configuration returned by the control plane `/api/config` endpoint. */
export interface BootstrapConfiguration {
  mode: string;
  version: string;
  buildTimestamp: string;
  oidcEnabled: boolean;
}

/** User profile information decoded from the JWT (never the token itself). */
export interface User {
  username: string;
  email: string;
  org: string;
  groups?: string[];
  roles?: string[];
  expiresAt?: string | null;
  expired?: boolean;
}

/** User profile returned by /api/v1/user/profile. */
export interface UserProfile {
  firstname:  string | null;
  lastname:  string | null;
  organizations: Organization[];
}

/** Organization membership for a User profile. */
export interface Organization {
  name: string;
  description: string | null;
  icon: string | null;
}

/** Authentication mode determined by the bootstrap configuration. */
export type AuthMode = 'reshapr' | 'oidc';

