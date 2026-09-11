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

  let res: Response;
  try {
    res = await fetch(targetUrl, init);
  } catch (err) {
    // A streamed request body (large uploads) can fail here for two main reasons:
    // 1. The incoming body exceeded the adapter-node BODY_SIZE_LIMIT (default 512K),
    //    which errors the ReadableStream we forward and surfaces as "fetch failed".
    // 2. The control plane is unreachable.
    // The underlying reason is hidden in `cause`, so log it and return an explicit
    // error instead of an opaque 500.
    const cause = err instanceof Error ? err.cause : undefined;
    console.error(`Proxy request to ${targetUrl} failed:`, err, cause ? { cause } : '');

    const message = cause instanceof Error ? cause.message : String(cause ?? err);
    if (/exceed/i.test(message) && /(limit|body[_\s-]?size)/i.test(message)) {
      return json(
        {
          error: 'Payload Too Large',
          message:
            'The uploaded content exceeds the web UI body size limit. Increase BODY_SIZE_LIMIT for the web-ui server.'
        },
        { status: 413 }
      );
    }
    return json(
      { error: 'Bad Gateway', message: 'Unable to reach the reshapr control plane.' },
      { status: 502 }
    );
  }

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

