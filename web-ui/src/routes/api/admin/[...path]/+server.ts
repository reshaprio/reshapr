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

import { json } from '@sveltejs/kit';
import { getCtrlUrl, getAdminApiKey, getSessionToken, extractUserProfile } from '#lib/server/auth.js';
import { sanitizePath, forbiddenPathResponse, proxyRequest } from '#lib/server/proxy.js';
import type { RequestHandler } from './$types.js';

/**
 * Catch-all proxy for admin API calls.
 *
 * Security:
 * 1. Requires a valid JWT session cookie with org === 'reshapr' (admin check).
 * 2. Injects the admin API key (x-reshapr-api-key) server-side — never exposed to the client.
 * 3. Sanitizes the path to prevent traversal attacks.
 */
const handler: RequestHandler = async ({ params, request, cookies, url }) => {
  // 1. Authenticate: read JWT from cookie.
  const token = getSessionToken(cookies);
  if (!token) {
    return json({ error: 'Not authenticated' }, { status: 401 });
  }

  // 2. Authorize: verify admin claim (org === 'reshapr').
  const profile = extractUserProfile(token);
  if (!profile || profile.org !== 'reshapr') {
    return json({ error: 'Forbidden: admin access required' }, { status: 403 });
  }

  // 3. Sanitize the proxy path.
  const rawPath = params.path;
  const safePath = sanitizePath(rawPath);
  if (safePath === null) {
    return forbiddenPathResponse();
  }

  // 4. Build target URL (preserve query string).
  const ctrlUrl = getCtrlUrl();
  const queryString = url.search; // includes leading '?' if present
  const targetUrl = `${ctrlUrl}/api/admin/${safePath}${queryString}`;

  // 5. Forward with admin API key.
  const adminApiKey = getAdminApiKey();
  return proxyRequest(request, targetUrl, {
    'x-reshapr-api-key': adminApiKey
  });
};

// Support all HTTP methods via the same handler.
export const GET = handler;
export const POST = handler;
export const PUT = handler;
export const PATCH = handler;
export const DELETE = handler;

