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
import { getCtrlUrl, setSessionCookie, extractUserProfile } from '#lib/server/auth.js';
import type { RequestHandler } from './$types.js';

/**
 * POST /api/auth/login
 *
 * Receives { username, password } from the client, forwards to the control
 * plane's /auth/login/reshapr endpoint.
 *
 * On success: sets the JWT in an httpOnly cookie and returns user profile.
 * On failure: returns 401.
 */
export const POST: RequestHandler = async ({ request, cookies }) => {
  const { username, password } = await request.json();

  if (!username || !password) {
    return json({ error: 'Username and password are required' }, { status: 400 });
  }

  const ctrlUrl = getCtrlUrl();

  const res = await fetch(`${ctrlUrl}/auth/login/reshapr`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });

  if (!res.ok) {
    return json({ error: 'Invalid credentials' }, { status: 401 });
  }

  // The control plane returns the JWT as plain text.
  const token = await res.text();

  // Decode profile from JWT payload.
  const profile = extractUserProfile(token);
  if (!profile) {
    return json({ error: 'Invalid token received from control plane' }, { status: 502 });
  }

  // Store JWT in httpOnly cookie — never sent to the browser JS.
  setSessionCookie(cookies, token);

  return json(profile);
};

