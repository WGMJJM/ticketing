<script setup lang="ts">
defineProps<{
  name: 'ticket' | 'search' | 'heart' | 'bookmark' | 'arrow' | 'chevron' | 'user' | 'calendar' | 'grid' | 'music' | 'stage' | 'art' | 'game' | 'clock' | 'check' | 'location'
}>()
const paths = {
  ticket: ['M4 5h16v5a2 2 0 0 0 0 4v5H4v-5a2 2 0 0 0 0-4V5Z', 'M15 5v2m0 3v4m0 3v2'],
  search: ['M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z', 'm15 15 6 6'],
  heart: ['M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z'],
  bookmark: ['M6 3h12v18l-6-4-6 4V3Z'],
  arrow: ['M4 12h16m-6-6 6 6-6 6'],
  chevron: ['m6 9 6 6 6-6'],
  user: ['M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z', 'M4 21v-2a8 8 0 0 1 16 0v2'],
  calendar: ['M4 5h16v16H4V5Z', 'M8 3v4m8-4v4M4 10h16m-11 5h1m4 0h1'],
  grid: ['M3 3h7v7H3V3Zm11 0h7v7h-7V3ZM3 14h7v7H3v-7Zm11 0h7v7h-7v-7Z'],
  music: ['M9 18V5l12-2v13M9 9l12-2', 'M9 18a3 3 0 1 1-3-3c1.7 0 3 1.3 3 3Zm12-2a3 3 0 1 1-3-3c1.7 0 3 1.3 3 3Z'],
  stage: ['M3 4h18v16H3V4Zm0 4h18', 'M9 8c0 6-3 8-6 9m12-9c0 6 3 8 6 9M8 20l4-5 4 5'],
  art: ['M4 3h16v18H4V3Z', 'm4 17 5-6 4 4 3-3 4 5M9 7h.01'],
  game: ['M7 7h10a4 4 0 0 1 4 3l1 7a3 3 0 0 1-5 2l-3-3h-4l-3 3a3 3 0 0 1-5-2l1-7a4 4 0 0 1 4-3Z', 'M6 11v4m-2-2h4m8-1h.01M19 14h.01'],
  clock: ['M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z', 'M12 7v5l3 2'],
  check: ['m5 12 4 4L19 6'],
  location: ['M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z', 'M15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z'],
}
</script>

<template>
  <svg class="site-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path v-for="(path, index) in paths[name]" :key="index" :d="path" />
  </svg>
</template>
