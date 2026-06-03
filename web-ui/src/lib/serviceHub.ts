import type { apiClient } from '$lib/api/client.js';
import { resolveMcpCustomToolsForService } from '$lib/mcpCustomTools.js';
import { resolveMcpPromptsForService } from '$lib/mcpPrompts.js';
import { collectMcpUrlsFromActiveExpositions } from '$lib/mcpEndpointUrls.js';

export type ServiceApi = ReturnType<typeof apiClient>;

export type ServiceRecord = {
	id: string;
	name: string;
	version: string;
	type: string;
	organizationId: string | null;
	operationsCount: number;
};

export type ServiceHubSummary = {
	service: ServiceRecord;
	artifactCount: number;
	planCount: number;
	expositionActiveCount: number;
	expositionAllCount: number;
	mcpCustomToolsCount: number | null;
	mcpPromptsCount: number | null;
	mcpUrlCount: number;
};

function asRecord(raw: unknown): Record<string, unknown> | null {
	return raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : null;
}

export function parseServiceRecord(raw: unknown): ServiceRecord | null {
	const o = asRecord(raw);
	if (!o || typeof o.id !== 'string') return null;
	const ops = o.operations;
	const operationsCount = Array.isArray(ops) ? ops.length : 0;
	return {
		id: o.id,
		name: typeof o.name === 'string' ? o.name : '—',
		version: typeof o.version === 'string' ? o.version : '—',
		type: o.type != null ? String(o.type) : '—',
		organizationId: typeof o.organizationId === 'string' ? o.organizationId : null,
		operationsCount
	};
}

export function expositionBelongsToService(raw: unknown, serviceId: string): boolean {
	const o = asRecord(raw);
	const svc = asRecord(o?.service);
	return svc?.id === serviceId;
}

export function planBelongsToService(raw: unknown, serviceId: string): boolean {
	const o = asRecord(raw);
	return o?.serviceId === serviceId;
}

export async function loadServiceHubSummary(
	serviceId: string,
	client: ServiceApi,
): Promise<ServiceHubSummary> {
	const raw = await client.getService(serviceId);
	const service = parseServiceRecord(raw);
	if (!service) {
		throw new Error(`Service not found: ${serviceId}`);
	}

	const [artifacts, plans, activeExpos, allExpos] = await Promise.all([
		client.listArtifactsByService(serviceId),
		client.listConfigurationPlans(),
		client.listExpositionsActive(),
		client.listExpositionsAll()
	]);

	const artifactList = Array.isArray(artifacts) ? artifacts : [];
	const planList = (Array.isArray(plans) ? plans : []).filter((p) =>
		planBelongsToService(p, serviceId),
	);
	const activeList = (Array.isArray(activeExpos) ? activeExpos : []).filter((e) =>
		expositionBelongsToService(e, serviceId),
	);
	const allList = (Array.isArray(allExpos) ? allExpos : []).filter((e) =>
		expositionBelongsToService(e, serviceId),
	);

	const mcpUrls = collectMcpUrlsFromActiveExpositions(activeList);

	let mcpCustomToolsCount: number | null = null;
	let mcpPromptsCount: number | null = null;
	try {
		const tools = await resolveMcpCustomToolsForService(serviceId, client);
		mcpCustomToolsCount = tools.tools.length;
	} catch {
		mcpCustomToolsCount = null;
	}
	const prompts = await resolveMcpPromptsForService(serviceId, client);
	mcpPromptsCount = prompts.prompts.length;

	return {
		service,
		artifactCount: artifactList.length,
		planCount: planList.length,
		expositionActiveCount: activeList.length,
		expositionAllCount: allList.length,
		mcpCustomToolsCount,
		mcpPromptsCount,
		mcpUrlCount: mcpUrls.length
	};
}
