/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Extract a human-readable error message from a failed HTTP response.
 *
 * The control-plane returns structured errors as `{ "message": "..." }`; this helper surfaces that
 * message when present, falling back to the raw response body (e.g. plain-text errors) and finally
 * to the HTTP status text so the caller always gets the most specific detail available.
 */
export async function readHttpErrorMessage(response: Response): Promise<string> {
  let text = '';
  try {
    text = await response.text();
  } catch {
    return response.statusText;
  }
  if (!text) {
    return response.statusText;
  }
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === 'object') {
      const message = parsed.message ?? parsed.error;
      if (typeof message === 'string' && message.trim()) {
        return message;
      }
    }
  } catch {
    // Not JSON — fall through to the raw text body.
  }
  return text;
}
