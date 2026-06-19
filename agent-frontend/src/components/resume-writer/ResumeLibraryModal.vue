<template>
  <div class="resume-modal-mask" @click.self="$emit('close')">
    <section class="resume-modal-card">
      <header><h3>从简历库导入</h3><button @click="$emit('close')">×</button></header>
      <p v-if="!items.length" class="library-empty">简历库为空，请先在简历管理中上传或保存简历。</p>
      <ul v-else class="library-list">
        <li v-for="item in items" :key="item.resumeId">
          <div class="library-item-info">
            <b>{{ item.originalName || item.resumeId }}</b>
            <small>{{ String(item.suffix || '').toUpperCase() }} · {{ item.parseStatus === 'success' ? '已解析' : item.parseStatus }}</small>
          </div>
          <button class="secondary-btn" @click="$emit('import', item)">导入</button>
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup>
defineProps({ items: { type: Array, default: () => [] } })
defineEmits(['close', 'import'])
</script>

<style scoped>
.resume-modal-mask{position:fixed;inset:0;background:rgba(15,23,42,.42);z-index:100;display:grid;place-items:center;padding:24px}
.resume-modal-card{width:min(720px,92vw);max-height:86vh;overflow:auto;background:#fff;border:1px solid #e5ebf5;border-radius:22px;box-shadow:0 34px 110px rgba(0,0,0,.24);padding:20px}
.resume-modal-card header{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}
.resume-modal-card h3{margin:0;color:#172033}
.resume-modal-card header button{border:0;background:transparent;font-size:26px;color:#475467}
.library-empty{color:#7a8497;font-size:13px;padding:8px 2px}
.library-list{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:8px}
.library-list li{display:flex;align-items:center;justify-content:space-between;gap:12px;border:1px solid #e8eef7;border-radius:12px;padding:10px 12px}
.library-item-info{min-width:0}
.library-item-info b{display:block;color:#172033;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.library-item-info small{color:#7a8497;font-size:12px}
.library-list .secondary-btn{flex:none}
</style>
