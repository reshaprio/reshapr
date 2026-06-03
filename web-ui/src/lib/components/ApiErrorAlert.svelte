<script lang="ts">
	import * as Alert from '$lib/components/ui/alert/index.js';

	let { message }: { message: string } = $props();
</script>

<Alert.Root variant="destructive">
	<Alert.Title>Error</Alert.Title>
	<Alert.Description class="whitespace-pre-wrap font-mono text-xs">{message}</Alert.Description>
</Alert.Root>
