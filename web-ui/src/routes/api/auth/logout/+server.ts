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
import { clearSessionCookie } from '#lib/server/auth.js';
import type { RequestHandler } from './$types.js';

/**
 * POST /api/auth/logout
 *
 * Clears the httpOnly session cookie and returns 200.
 */
export const POST: RequestHandler = async ({ cookies }) => {
  clearSessionCookie(cookies);
  return json({ ok: true });
};

