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
import { getSessionToken, extractUserProfile, extractSessionClaims, decodeJwtPayload } from '$lib/server/auth.js';
import type { RequestHandler } from './$types.js';

/**
 * GET /api/auth/session
 *
 * Returns the current user profile decoded from the JWT cookie.
 * Called by the client auth store to initialize/verify the session.
 *
 * Returns 200 with { username, email, org, isAdmin } if a valid session exists.
 * Returns 401 if no cookie or the JWT cannot be decoded.
 */
export const GET: RequestHandler = async ({ cookies }) => {
  const token = getSessionToken(cookies);

  if (!token) {
    return json({ error: 'Not authenticated' }, { status: 401 });
  }

  const profile = extractUserProfile(token);
  if (!profile) {
    return json({ error: 'Invalid session' }, { status: 401 });
  }

  // Check JWT expiration.
  const payload = decodeJwtPayload(token);
  if (payload?.exp && typeof payload.exp === 'number') {
    const now = Math.floor(Date.now() / 1000);
    if (payload.exp < now) {
      return json({ error: 'Session expired' }, { status: 401 });
    }
  }

  return json({
    username: profile.username,
    email: profile.email,
    org: profile.org,
    isAdmin: profile.org === 'reshapr',
    ...extractSessionClaims(token)
  });
};

