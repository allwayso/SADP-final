<script setup lang="ts">
defineProps<{ label: string; tone?: string }>()
</script>

<template>
  <span class="pill" :class="`tone-${tone ?? 'neutral'}`">{{ label }}</span>
</template>
