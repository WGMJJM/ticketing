<script setup lang="ts">
withDefaults(defineProps<{
  page: number
  total: number
  pageSize?: number
  disabled?: boolean
  unit?: string
}>(), {
  pageSize: 10,
  unit: '条',
})

const emit = defineEmits<{ change: [page: number] }>()
</script>

<template>
  <div class="pagination">
    <ElButton :disabled="disabled || page <= 1" @click="emit('change', page - 1)">上一页</ElButton>
    <span><slot>第 {{ page }} 页 · 共 {{ total }} {{ unit }}</slot></span>
    <ElButton :disabled="disabled || page * pageSize >= total" @click="emit('change', page + 1)">下一页</ElButton>
  </div>
</template>
