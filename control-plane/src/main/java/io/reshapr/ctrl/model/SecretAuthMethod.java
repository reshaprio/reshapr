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
package io.reshapr.ctrl.model;

/**
 * The authentication method a {@link Secret} carries to authenticate against a backend.
 * This is an explicit discriminator used to dispatch the security logic, as opposed to
 * inferring the mechanism from which fields happen to be populated.
 * It is orthogonal to {@link SecretType} (which qualifies where the secret is used).
 * @author laurent
 */
public enum SecretAuthMethod {
   /** HTTP Basic authentication using {@code username} and {@code password}. */
   BASIC,
   /** Bearer/token authentication using {@code token} and optional {@code tokenHeader}. */
   BEARER_TOKEN,
   /** OAuth2 Authorization Code flow (interactive, per-user elicitation). */
   OAUTH2_AUTHORIZATION_CODE,
   /** OAuth2 Client Credentials flow (machine-to-machine, no elicitation). */
   OAUTH2_CLIENT_CREDENTIALS
}
