<script setup lang="ts">
import { ref, watch } from 'vue'
import { categoryInfo } from '../api/activities'
const props = defineProps<{
  url: string | null
  category: string
  title: string
}>()
const failed = ref(false)
watch(
  () => props.url,
  () => {
    failed.value = false
  },
)
</script>
<template>
  <div class="activity-cover" :class="categoryInfo(category).theme">
    <img
      v-if="url && !failed"
      :src="url"
      :alt="title"
      loading="lazy"
      referrerpolicy="no-referrer"
      @error="failed = true"
    />
    <template v-else
      ><span class="cover-symbol" aria-hidden="true">{{
        categoryInfo(category).symbol
      }}</span
      ><span class="cover-category"
        >{{ categoryInfo(category).label }} · 热爱在场</span
      ></template
    >
  </div>
</template>
