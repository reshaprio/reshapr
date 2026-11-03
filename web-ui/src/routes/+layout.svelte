<!--
  ~ Copyright The Reshapr Authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~ http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<script lang="ts">
  import '../app.css';
  import { installSessionGuard } from '#lib/api/session-guard.js';

  // Intercept 401s from API calls to redirect to the login screen when the
  // session is lost, instead of surfacing a raw "Not authenticated" error.
  installSessionGuard();

  let { children } = $props();
</script>

<svelte:head>
  <title>reShapr</title>
</svelte:head>

{@render children()}

