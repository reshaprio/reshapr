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

import { env } from '$env/dynamic/private';
import type { Cookies } from '@sveltejs/kit';

/** Name of the httpOnly cookie that holds the JWT. */
export const SESSION_COOKIE = 'reshapr-session';

/** JWT expiry aligned with the control plane (2 hours). */
const SESSION_MAX_AGE_SECONDS = 2 * 60 * 60;

/** Resolved control plane base URL (no trailing slash). */
export function getCtrlUrl(): string {
  const url = env.RESHAPR_CTRL_URL ?? 'http://localhost:5555';
  return url.replace(/\/+$/, '');
}

/** Resolved admin API key for the control plane. */
export function getAdminApiKey(): string {
  return env.RESHAPR_ADMIN_API_KEY ?? '';
}

/** Public URL of this web-ui (used for OIDC redirect_uri). */
export function getPublicUrl(): string {
  const url = env.RESHAPR_WEBUI_PUBLIC_URL ?? 'http://localhost:5173';
  return url.replace(/\/+$/, '');
}

/**
 * Decode a JWT payload without verifying the signature.
 * This is sufficient because:
 * - The JWT comes from our own httpOnly cookie (set by this server only).
 * - The control plane will validate the JWT on every proxied call.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(payload);
  } catch {
    return null;
  }
}

/** Set the JWT as an httpOnly session cookie. */
export function setSessionCookie(cookies: Cookies, token: string): void {
  cookies.set(SESSION_COOKIE, token, {
    path: '/',
    httpOnly: true,
    secure: true,
    sameSite: 'strict',
    maxAge: SESSION_MAX_AGE_SECONDS
  });
}

/** Clear the session cookie. */
export function clearSessionCookie(cookies: Cookies): void {
  cookies.delete(SESSION_COOKIE, { path: '/' });
}

/** Read the JWT from the session cookie, or return null. */
export function getSessionToken(cookies: Cookies): string | null {
  return cookies.get(SESSION_COOKIE) ?? null;
}

/** Extract user profile from a JWT token string. */
export function extractUserProfile(token: string): { username: string; email: string; org: string } | null {
  const payload = decodeJwtPayload(token);
  if (!payload) return null;

  const username = (payload.sub ?? payload.upn) as string | undefined;
  const email = payload.email as string | undefined;
  const org = payload.org as string | undefined;

  if (!username || !org) return null;

  return { username, email: email ?? '', org };
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((g): g is string => typeof g === 'string');
}

function readRealmAccessRoles(payload: Record<string, unknown>): string[] {
  const realmAccess = payload.realm_access;
  if (!realmAccess || typeof realmAccess !== 'object') return [];
  return readStringArray((realmAccess as Record<string, unknown>).roles);
}

/** Extended session fields for the Account page (display only; no signature verification). */
export function extractSessionClaims(token: string): {
  groups: string[];
  roles: string[];
  expiresAt: string | null;
  expired: boolean;
} {
  const payload = decodeJwtPayload(token);
  if (!payload) {
    return { groups: [], roles: [], expiresAt: null, expired: false };
  }

  const groups = readStringArray(payload.groups);
  const roles = [
    ...readStringArray(payload.roles),
    ...readRealmAccessRoles(payload)
  ];

  let expiresAt: string | null = null;
  let expired = false;
  if (typeof payload.exp === 'number' && Number.isFinite(payload.exp)) {
    expiresAt = new Date(payload.exp * 1000).toISOString();
    expired = payload.exp * 1000 <= Date.now();
  }

  return { groups, roles, expiresAt, expired };
}

