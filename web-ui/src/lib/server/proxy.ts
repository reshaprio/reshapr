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

/**
 * Sanitize a proxy path to prevent path traversal attacks.
 * Rejects paths containing `..`, double-encoded sequences, or null bytes.
 * Returns the sanitized path or null if the path is malicious.
 */
export function sanitizePath(path: string): string | null {
  // Decode once to catch double-encoded sequences.
  let decoded: string;
  try {
    decoded = decodeURIComponent(path);
  } catch {
    return null;
  }

  // Reject directory traversal patterns and null bytes.
  if (decoded.includes('..') || decoded.includes('\0')) {
    return null;
  }

  // Also reject the raw path if it contains obvious traversal.
  if (path.includes('..') || path.includes('\0')) {
    return null;
  }

  return path;
}

/** Standard response for a bad proxy path. */
export function forbiddenPathResponse() {
  return json({ error: 'Invalid path' }, { status: 400 });
}

/**
 * Forward a request to the control plane and return the response.
 * Copies method, body, content-type, and query string.
 */
export async function proxyRequest(
  request: Request,
  targetUrl: string,
  extraHeaders: Record<string, string>
): Promise<Response> {
  const headers: Record<string, string> = { ...extraHeaders };

  // Forward content-type if present (for POST/PUT/PATCH).
  const contentType = request.headers.get('content-type');
  if (contentType) {
    headers['Content-Type'] = contentType;
  }

  const init: RequestInit = {
    method: request.method,
    headers,
    // Preserve the original body stream (required for multipart FormData uploads).
    ...(request.method !== 'GET' && request.method !== 'HEAD' ? { body: request.body, duplex: 'half' as const } : {})
  };

  const res = await fetch(targetUrl, init);

  // Stream the response back with original status and content-type.
  const responseHeaders: Record<string, string> = {};
  const resContentType = res.headers.get('content-type');
  if (resContentType) {
    responseHeaders['Content-Type'] = resContentType;
  }

  return new Response(res.body, {
    status: res.status,
    statusText: res.statusText,
    headers: responseHeaders
  });
}

