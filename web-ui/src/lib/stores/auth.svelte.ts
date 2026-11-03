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

import type { User, AuthMode, UserProfile } from '#lib/types.js';
import { SvelteSet } from 'svelte/reactivity';

/**
 * Reactive authentication store using Svelte 5 runes.
 *
 * The JWT is never stored here — it lives in an httpOnly cookie managed
 * by the SvelteKit server routes. Only the decoded user profile is kept.
 */
function createAuthStore() {
  let user = $state<User | null>(null);
  let authMode = $state<AuthMode>('reshapr');
  let loading = $state(true);
  let profile = $state<UserProfile | null>(null);

  const isAuthenticated = $derived(user !== null);
  const isAdmin = $derived(user?.org === 'reshapr');
  const currentOrg = $derived(user?.org ?? '');
  const hasMultipleOrgs = $derived(profile? profile.organizations.length > 1 : false);
  const isOwnerOfCurrentOrg = $derived(
    profile?.organizations.find(o => o.name === currentOrg)?.isOwner ?? false
  );
  const currentOrgIcon = $derived(
    profile?.organizations.find(o => o.name === currentOrg)?.icon ?? null
  );

  /** Compute user initials from username (first 2 chars uppercased). */
  const initials = $derived(
    user?.username
      ? user.username.substring(0, 2).toUpperCase()
      : '?'
  );

  return {
    get user() { return user; },
    get authMode() { return authMode; },
    get loading() { return loading; },
    get isAuthenticated() { return isAuthenticated; },
    get isAdmin() { return isAdmin; },
    get organizations() { return profile?.organizations; },
    get currentOrg() { return currentOrg; },
    get hasMultipleOrgs() { return hasMultipleOrgs; },
    get isOwnerOfCurrentOrg() { return isOwnerOfCurrentOrg; },
    get currentOrgIcon() { return currentOrgIcon; },
    get initials() { return initials; },

    setUser(profile: User | null) {
      user = profile;
    },

    setAuthMode(mode: AuthMode) {
      authMode = mode;
    },

    setLoading(value: boolean) {
      loading = value;
    },

    /** Clear the store on logout. */
    clear() {
      user = null;
      profile = null;
      loading = false;
    },

    /**
     * Initialize the store by fetching the current session from the server.
     * Returns `true` if a valid session exists, `false` otherwise.
     */
    async initSession(): Promise<boolean> {
      loading = true;
      try {
        const res = await fetch('/api/auth/session');
        if (res.ok) {
          const data: User = await res.json();
          user = data;
          // Fetch user profile in background.
          this.fetchUserProfile();
          return true;
        }
        user = null;
        return false;
      } catch {
        user = null;
        return false;
      } finally {
        loading = false;
      }
    },

    /** Fetch the user profile with the list of organizations the user belongs to. */
    async fetchUserProfile(): Promise<void> {
      try {
        const res = await fetch('/api/v1/user/profile');
        if (res.ok) {
          const data = await res.json();
          // Deduplicate organizations by name (backend may return duplicates
          // if user is both owner and member of an org).
          if (data.organizations) {
            const seen = new SvelteSet<string>();
            data.organizations = data.organizations.filter((org: { name: string }) => {
              if (seen.has(org.name)) return false;
              seen.add(org.name);
              return true;
            });
          }
          profile = data;
        }
      } catch {
        // Non-critical — org selector just won't show.
      }
    },

    /**
     * Login with username/password (reshapr internal auth).
     * On success the server sets the JWT cookie and returns the user profile.
     */
    async login(username: string, password: string): Promise<{ ok: boolean; error?: string }> {
      try {
        const res = await fetch('/api/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username, password })
        });
        if (res.ok) {
          const data: User = await res.json();
          user = data;
          return { ok: true };
        }
        return { ok: false, error: res.status === 401 ? 'Invalid credentials' : 'Login failed' };
      } catch {
        return { ok: false, error: 'Network error' };
      }
    },

    /** Logout — clears the httpOnly cookie via the server and resets the store. */
    async logout(): Promise<void> {
      try {
        await fetch('/api/auth/logout', { method: 'POST' });
      } finally {
        user = null;
        profile = null;
      }
    },

    /**
     * Switch to a different organization.
     * Calls the server-side route which exchanges the JWT via the control plane
     * and updates the httpOnly cookie.
     */
    async switchOrganization(organizationId: string): Promise<{ ok: boolean; error?: string }> {
      try {
        const res = await fetch('/api/auth/switch-org', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ organizationId })
        });
        if (res.ok) {
          const data: User = await res.json();
          user = data;
          return { ok: true };
        }
        const body = await res.json().catch(() => ({ error: 'Failed to switch organization' }));
        return { ok: false, error: body.error ?? 'Failed to switch organization' };
      } catch {
        return { ok: false, error: 'Network error' };
      }
    }
  };
}

export const auth = createAuthStore();

