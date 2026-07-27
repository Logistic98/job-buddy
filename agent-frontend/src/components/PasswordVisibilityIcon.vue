<template>
  <svg
    v-if="!visible"
    aria-hidden="true"
    data-icon="eye-closed"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <path d="m3 3 18 18" />
    <path d="M10.6 10.7a2 2 0 0 0 2.7 2.7" />
    <path d="M9.9 4.2A10.8 10.8 0 0 1 12 4c5 0 8.7 4.2 9.7 6.2a4 4 0 0 1 0 3.6 14.3 14.3 0 0 1-2 2.8" />
    <path d="M6.6 6.7a14.2 14.2 0 0 0-4.3 5.1 4 4 0 0 0 0 3.6C3.3 17.8 7 20 12 20a10.7 10.7 0 0 0 3.4-.5" />
  </svg>
  <svg
    v-else
    aria-hidden="true"
    data-icon="eye-open"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <path
      d="M2.3 10.2a4 4 0 0 0 0 3.6C3.3 15.8 7 20 12 20s8.7-4.2 9.7-6.2a4 4 0 0 0 0-3.6C20.7 8.2 17 4 12 4S3.3 8.2 2.3 10.2Z"
    />
    <circle cx="12" cy="12" r="3" />
  </svg>
</template>

<script setup>
defineProps({
  visible: { type: Boolean, required: true },
})
</script>
