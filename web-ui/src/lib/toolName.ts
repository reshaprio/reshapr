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

/**
 * Mirrors the server-side `McpToolConverter.getToolName` implementations (proxy module):
 * REST/OpenAPI operations (e.g. `GET /v1/forecast`) are converted to `get_v1_forecast`, while
 * GraphQL, gRPC, and ASYNC operation names are used as-is.
 */
export function toolNameForOperation(serviceType: string | null | undefined, operationName: string): string {
	const type = (serviceType ?? '').toUpperCase();
	if (type === 'GRAPHQL' || type === 'GRPC' || type === 'ASYNC') return operationName;
	return operationName
		.replaceAll(' ', '_')
		.replaceAll('/', '_')
		.replaceAll('{', '')
		.replaceAll('}', '')
		.replaceAll('__', '_')
		.toLowerCase();
}
