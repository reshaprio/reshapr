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

import type { ReshaprArtifactKind, ServiceRef } from './types.js';

const API_VERSION = 'reshapr.io/v1alpha1';

function serviceBlock(service: ServiceRef): string {
	return `service:
  name: ${service.name}
  version: '${service.version}'`;
}

const PAYLOAD_BY_KIND: Record<ReshaprArtifactKind, string> = {
	Prompts: 'prompts: {}',
	CustomTools: 'customTools: {}',
	Resources: 'resources: {}',
	ToolsOutputFilters: 'filters: {}',
	Skills: 'skills: {}'
};

/** Minimal YAML skeleton for creating a new custom artifact (used in release 4). */
export function buildTemplate(kind: ReshaprArtifactKind, service: ServiceRef): string {
	return `apiVersion: ${API_VERSION}
kind: ${kind}
${serviceBlock(service)}
${PAYLOAD_BY_KIND[kind]}
`;
}
