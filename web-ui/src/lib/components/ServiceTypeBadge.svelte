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
	import { Badge } from '#lib/components/ui/badge/index.js';

	interface Props {
		type: string;
		class?: string;
	}

	let { type, class: className = '' }: Props = $props();

	function getBadgeStyle(serviceType: string): string {
		switch (serviceType?.toUpperCase()) {
			case 'REST':
				return 'background-color: #6BBD4F; color: white;';
			case 'GRAPHQL':
				return 'background-color: #E10098; color: white;';
			case 'GRPC':
				return 'background-color: #c0a16b; color: white;';
			default:
				return '';
		}
	}
</script>

<Badge class={className} style={getBadgeStyle(type)}>
	{type}
</Badge>

