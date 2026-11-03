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
import { getCtrlUrl, getSessionToken, setSessionCookie, extractUserProfile } from '#lib/server/auth.js';
import type { RequestHandler } from './$types.js';

/**
 * POST /api/auth/switch-org
 *
 * Receives { organizationId } from the client, forwards to the control
 * plane's /auth/switchOrganization/{organizationId} endpoint with the
 * current JWT as Bearer token.
 *
 * On success: replaces the JWT cookie and returns the updated user profile.
 * On failure: returns the upstream status code.
 */
export const POST: RequestHandler = async ({ request, cookies }) => {
  const { organizationId } = await request.json();

  if (!organizationId) {
    return json({ error: 'organizationId is required' }, { status: 400 });
  }

  const currentToken = getSessionToken(cookies);
  if (!currentToken) {
    return json({ error: 'Not authenticated' }, { status: 401 });
  }

  const ctrlUrl = getCtrlUrl();

  const res = await fetch(
    `${ctrlUrl}/auth/switchOrganization/${encodeURIComponent(organizationId)}`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${currentToken}`
      }
    }
  );

  if (!res.ok) {
    const body = await res.text();
    return json({ error: body || res.statusText }, { status: res.status });
  }

  // The control plane returns the new JWT as plain text.
  const newToken = await res.text();

  // Decode the new profile from the JWT payload.
  const profile = extractUserProfile(newToken);
  if (!profile) {
    return json({ error: 'Invalid token received from control plane' }, { status: 502 });
  }

  // Replace the session cookie with the new JWT.
  setSessionCookie(cookies, newToken);

  return json(profile);
};


