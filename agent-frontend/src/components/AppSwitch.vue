<template>
  <label class="app-switch" :class="{ 'is-disabled': disabled }">
    <input
      type="checkbox"
      role="switch"
      :checked="modelValue === true"
      :disabled="disabled"
      :aria-label="ariaLabel"
      :aria-required="required || undefined"
      @change="$emit('update:modelValue', $event.target.checked)"
    />
    <span class="app-switch-track" aria-hidden="true"></span>
    <slot />
  </label>
</template>

<script setup>
defineProps({
  modelValue: {
    type: Boolean,
    default: false,
  },
  ariaLabel: {
    type: String,
    required: true,
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  required: {
    type: Boolean,
    default: false,
  },
})

defineEmits(['update:modelValue'])
</script>
