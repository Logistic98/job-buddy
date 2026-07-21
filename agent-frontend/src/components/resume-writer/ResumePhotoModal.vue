<template>
  <div class="resume-modal-mask" @click.self="$emit('close')">
    <section class="resume-modal-card photo-modal-card">
      <header>
        <h3>照片维护</h3>
        <button @click="$emit('close')">×</button>
      </header>
      <div class="photo-modal-body">
        <button type="button" class="photo-upload-card" @click="$emit('pick-local')">
          <b>{{ uploading ? '上传中' : '上传照片' }}</b>
          <small>支持多选，上传后保存到资源服务</small>
        </button>
        <article
          v-for="item in photos"
          :key="item.id || item.url"
          :class="['photo-card', { active: item.url === photoUrl }]"
        >
          <button type="button" class="photo-thumb" @click="$emit('selectPhoto', item)">
            <img :src="item.displayUrl || item.url" :alt="item.name || '照片'" />
          </button>
          <div class="photo-card-foot">
            <span :title="item.name || '照片'">{{ item.name || '照片' }}</span>
            <button type="button" @click="$emit('deletePhoto', item)">删除</button>
          </div>
        </article>
        <div v-if="!photos.length && !uploading" class="photo-empty-card">
          <b>暂无照片</b>
          <small>点击第一个卡片上传本地照片。</small>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
defineProps({
  photoUrl: { type: String, default: '' },
  photos: { type: Array, default: () => [] },
  uploading: { type: Boolean, default: false },
})
defineEmits(['close', 'pick-local', 'clear', 'selectPhoto', 'deletePhoto'])
</script>

<style scoped>
.resume-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
}
.resume-modal-card {
  width: min(840px, 92vw);
  max-height: 86vh;
  overflow: auto;
  background: #fff;
  border: 1px solid #e5ebf5;
  border-radius: 22px;
  box-shadow: 0 34px 110px rgba(0, 0, 0, 0.24);
  padding: 20px;
}
.resume-modal-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.resume-modal-card h3 {
  margin: 0;
  color: #172033;
}
.resume-modal-card header button {
  border: 0;
  background: transparent;
  font-size: 26px;
  color: #475467;
}
.photo-modal-body {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  grid-auto-rows: 246px;
  gap: 12px;
  height: 508px;
  box-sizing: border-box;
  overflow-y: auto;
  padding: 2px 4px 2px 2px;
  align-content: start;
}
.photo-upload-card,
.photo-empty-card,
.photo-card {
  width: 100%;
  height: 100%;
  border: 1px solid #e5ebf5;
  border-radius: 18px;
  background: #fff;
  box-sizing: border-box;
}
.photo-upload-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed #9db3d4;
  background: linear-gradient(180deg, #f9fbff, #eef4ff);
  color: #3157ff;
  cursor: pointer;
}
.photo-upload-card b,
.photo-empty-card b {
  font-size: 16px;
}
.photo-upload-card small,
.photo-empty-card small {
  max-width: 120px;
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
}
.photo-card {
  padding: 8px;
}
.photo-card.active {
  border-color: #3157ff;
  box-shadow: 0 0 0 3px rgba(49, 87, 255, 0.13);
}
.photo-thumb {
  width: 100%;
  height: auto;
  aspect-ratio: 5 / 7;
  border: 0;
  border-radius: 13px;
  background: #eef2f7;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
}
.photo-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.photo-card-foot {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 6px;
  margin-top: 6px;
}
.photo-card-foot span {
  min-width: 0;
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
  color: #172033;
  font-size: 12px;
  font-weight: 800;
  line-height: 1.35;
}
.photo-card-foot button {
  border: 0;
  background: transparent;
  color: #dc2626;
  font-size: 12px;
  font-weight: 900;
  line-height: 1.35;
  white-space: nowrap;
}
.photo-empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: #f8fafc;
  color: #475467;
}
@media (max-width: 640px) {
  .photo-modal-body {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-auto-rows: auto;
    height: min(508px, calc(86vh - 100px));
  }
  .photo-upload-card,
  .photo-empty-card {
    height: 220px;
  }
  .photo-card {
    height: auto;
  }
}
</style>
