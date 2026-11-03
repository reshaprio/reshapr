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
import { setSessionCookie, extractUserProfile } from '#lib/server/auth.js';
import type { RequestHandler } from './$types.js';

/**
 * GET /api/auth/callback/oidc?token=...
 *
 * Called by the control plane after a successful OIDC authentication.
 * The control plane redirects here with the JWT as a query parameter.
 *
 * This route:
 * 1. Extracts the JWT from the ?token= query parameter.
 * 2. Validates it can be decoded (basic sanity check).
 * 3. Sets it as an httpOnly cookie.
 * 4. Redirects the browser to the app root (/).
 */
export const GET: RequestHandler = async ({ url, cookies }) => {
  // If the control plane redirected back with an ?error= (e.g. access_denied from
  // the guard-access policy), forward it to the login page so it can be displayed.
  const forwardedError = url.searchParams.get('error');
  if (forwardedError) {
    return redirect(302, `/login?error=${encodeURIComponent(forwardedError)}`);
  }

  const token = url.searchParams.get('token');

  if (!token) {
    return redirect(302, '/login?error=missing_token');
  }

  // Sanity-check: ensure the token is decodable.
  const profile = extractUserProfile(token);
  if (!profile) {
    return redirect(302, '/login?error=invalid_token');
  }

  // Store the JWT in an httpOnly cookie.
  setSessionCookie(cookies, token);

  // Redirect to the application root.
  return redirect(302, '/');
};


