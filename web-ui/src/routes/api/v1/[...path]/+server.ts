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
import { getCtrlUrl, getSessionToken } from '#lib/server/auth.js';
import { sanitizePath, forbiddenPathResponse, proxyRequest } from '#lib/server/proxy.js';
import type { RequestHandler } from './$types.js';

/**
 * Catch-all proxy for public API calls (v1).
 *
 * Security:
 * 1. Requires a valid JWT session cookie.
 * 2. Injects the JWT as Authorization: Bearer header — the control plane validates it.
 * 3. Sanitizes the path to prevent traversal attacks.
 */
const handler: RequestHandler = async ({ params, request, cookies, url }) => {
  // 1. Authenticate: read JWT from cookie.
  const token = getSessionToken(cookies);
  if (!token) {
    return json({ error: 'Not authenticated' }, { status: 401 });
  }

  // 2. Sanitize the proxy path.
  const rawPath = params.path;
  const safePath = sanitizePath(rawPath);
  if (safePath === null) {
    return forbiddenPathResponse();
  }

  // 3. Build target URL (preserve query string).
  const ctrlUrl = getCtrlUrl();
  const queryString = url.search;
  const targetUrl = `${ctrlUrl}/api/v1/${safePath}${queryString}`;

  // 4. Forward with JWT Bearer token.
  return proxyRequest(request, targetUrl, {
    'Authorization': `Bearer ${token}`
  });
};

// Support all HTTP methods via the same handler.
export const GET = handler;
export const POST = handler;
export const PUT = handler;
export const PATCH = handler;
export const DELETE = handler;

