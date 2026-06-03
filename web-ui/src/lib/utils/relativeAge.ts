/** Relative age from an ISO-8601 instant (e.g. control plane `LocalDateTime` JSON). */
export function formatRelativeAge(createdOn: string | undefined): string {
	if (!createdOn) return '—';
	const t = Date.parse(createdOn);
	if (Number.isNaN(t)) return '—';
	const diffMs = Date.now() - t;
	if (diffMs < 0) return '0s';
	const sec = Math.floor(diffMs / 1000);
	if (sec < 60) return `${sec}s`;
	const min = Math.floor(sec / 60);
	if (min < 60) return `${min}m`;
	const h = Math.floor(min / 60);
	if (h < 48) return `${h}h`;
	const d = Math.floor(h / 24);
	if (d < 90) return `${d}d`;
	const mo = Math.floor(d / 30);
	if (mo < 24) return `${mo}mo`;
	const y = Math.floor(d / 365);
	return `${y}y`;
}
