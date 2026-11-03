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

import { redirect } from '@sveltejs/kit';
import { getCtrlPublicUrl, getPublicUrl } from '#lib/server/auth.js';
import type { RequestHandler } from './$types.js';

/**
 * GET /api/auth/login/oidc
 *
 * Redirects the browser to the control plane's OIDC login endpoint.
 * The control plane will handle the full OAuth2 authorization code flow
 * and redirect back to /api/auth/callback/oidc with a ?token= parameter.
 */
export const GET: RequestHandler = async () => {
  const ctrlPublicUrl = getCtrlPublicUrl();
  const publicUrl = getPublicUrl();

  // The control plane's /auth/login/oidc expects a redirect_uri
  // where it will send the user back with the JWT token.
  const callbackUrl = `${publicUrl}/api/auth/callback/oidc`;
  const oidcUrl = `${ctrlPublicUrl}/auth/login/oidc?redirect_uri=${encodeURIComponent(callbackUrl)}`;

  redirect(302, oidcUrl);
};

