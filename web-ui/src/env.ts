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


import { defineEnvVars } from '@sveltejs/kit/env';

const optional = (fallback = '') => ({
  schema: (value: string | undefined) => value ?? fallback
});

export const variables = defineEnvVars({
  RESHAPR_CTRL_URL: optional('http://localhost:5555'),
  RESHAPR_CTRL_PUBLIC_URL: optional(),
  RESHAPR_ADMIN_API_KEY: optional(),
  RESHAPR_WEBUI_PUBLIC_URL: optional('http://localhost:5173')
});
