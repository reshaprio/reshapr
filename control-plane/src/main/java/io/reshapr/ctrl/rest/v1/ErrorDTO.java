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
package io.reshapr.ctrl.rest.v1;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A minimal structured error payload returned by the public API when a request fails, so callers
 * (CLI, web UI) can surface a meaningful message rather than a generic status text.
 * @param message Human-readable description of the error (e.g. an artifact validation failure).
 */
@RegisterForReflection
public record ErrorDTO(String message) {
}
