<template><section class="chat-panel"><div v-for="msg in chat.messages" :key="msg.id">{{ msg.content }}</div></section></template>
<script setup>
import { useChatStore } from '../stores/chat'
const chat = useChatStore()
</script>
