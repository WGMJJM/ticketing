<script setup lang="ts">
import { ref, watch } from 'vue'
import { categoryInfo } from '../api/activities'
import SiteIcon from './SiteIcon.vue'
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
  <div class="activity-cover" :class="[categoryInfo(category).theme, { 'has-image': url && !failed }]">
    <img v-if="url && !failed" :src="url" :alt="title" loading="lazy" referrerpolicy="no-referrer" @error="failed = true" />
    <template v-else>
      <span class="cover-category">{{ categoryInfo(category).label }}</span>
      <strong class="cover-poster-title">{{ title }}</strong>
      <span class="cover-symbol"><SiteIcon :name="category === 'STAGE' ? 'stage' : category === 'EXHIBITION' ? 'art' : category === 'ESPORTS' ? 'game' : 'music'" /></span>
      <span class="cover-caption">相约现场</span>
    </template>
  </div>
</template>
