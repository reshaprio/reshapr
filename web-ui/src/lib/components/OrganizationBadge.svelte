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
import { Badge } from "#lib/components/ui/badge/index.js";
import { HugeiconsIcon } from "@hugeicons/svelte";
import { Building01Icon } from "@hugeicons/core-free-icons";

interface Props {
  organizationName: string;
}

let { organizationName }: Props = $props();
</script>

<Badge variant="outline">
  <HugeiconsIcon icon={Building01Icon} size={12} class="mr-1" /> {organizationName}
</Badge>