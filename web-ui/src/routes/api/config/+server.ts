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
import { getCtrlUrl } from '#lib/server/auth.js';
import type { RequestHandler } from './$types.js';

/**
 * Proxy GET /api/config → control plane /api/config/.
 * No authentication required.
 */
export const GET: RequestHandler = async () => {
  const ctrlUrl = getCtrlUrl();

  const res = await fetch(`${ctrlUrl}/api/config/`);
  if (!res.ok) {
    return json({ error: 'Failed to fetch configuration from control plane' }, { status: 502 });
  }

  const data = await res.json();
  return json(data);
};

