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

import { goto } from '$app/navigation';
import { auth } from '#lib/stores/auth.svelte.js';

/**
 * Query-string flag appended to the login URL when a session is lost so the
 * login screen can display a clear "your session has expired" message.
 */
export const SESSION_EXPIRED_PARAM = 'session_expired';

/**
 * API prefixes whose `401` responses mean the current session is no longer
 * valid and the user must re-authenticate.
 */
const GUARDED_PREFIXES = ['/api/v1/', '/api/admin/'];

/** Guards against firing multiple concurrent redirects for in-flight 401s. */
let handling = false;

/** Extract a same-origin pathname from a fetch input, or null if not applicable. */
function toPathname(input: RequestInfo | URL): string | null {
  try {
    const raw =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.href
          : input instanceof Request
            ? input.url
            : String(input);
    // Resolve relative URLs against the current origin.
    return new URL(raw, window.location.origin).pathname;
  } catch {
    return null;
  }
}

/** True when a 401 on this path should trigger a global session-expired redirect. */
function isGuardedApiPath(pathname: string | null): boolean {
  if (!pathname) return false;
  return GUARDED_PREFIXES.some((prefix) => pathname.startsWith(prefix));
}

/**
 * Handle a lost session: clear the auth store and redirect to the login screen
 * with a flag so it can display an "expired session" message.
 *
 * No-op when already on the login page to avoid redirect loops.
 */
export function handleSessionExpired(): void {
  if (typeof window === 'undefined') return;
  if (handling) return;
  if (window.location.pathname === '/login') return;

  handling = true;
  auth.clear();
  void goto(`/login?error=${SESSION_EXPIRED_PARAM}`).finally(() => {
    handling = false;
  });
}

/**
 * Install a one-time `window.fetch` interceptor that watches for `401`
 * responses from guarded API endpoints and redirects the user to the login
 * screen. This centralizes session-expiry handling so individual pages don't
 * each have to detect and react to authentication loss.
 */
export function installSessionGuard(): void {
  if (typeof window === 'undefined') return;

  const current = window.fetch as typeof window.fetch & { __reshaprGuarded?: boolean };
  if (current.__reshaprGuarded) return;

  const original = window.fetch.bind(window);
  const guarded = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const res = await original(input, init);
    if (res.status === 401 && isGuardedApiPath(toPathname(input))) {
      handleSessionExpired();
    }
    return res;
  }) as typeof window.fetch & { __reshaprGuarded?: boolean };

  guarded.__reshaprGuarded = true;
  window.fetch = guarded;
}
