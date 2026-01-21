<template>
  <div class="error-page">
    <div class="error-card">
      <h1>链接不可用</h1>
      <p>访问链接无效或已过期，请联系管理员获取新的控制链接。</p>
      <button class="btn" @click="goBack">返回</button>
    </div>
  </div>
</template>

<script setup lang="ts">
function goBack() {
  window.history.back()
}
</script>

<style scoped>
.error-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: radial-gradient(circle at top, #1a1a2e, #0a0a0a);
  color: #eee;
}

.error-card {
  max-width: 480px;
  width: 100%;
  padding: 32px;
  border-radius: 12px;
  border: 1px solid #2a2a4a;
  background: #12122a;
  text-align: center;
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.35);
}

.error-card h1 {
  margin: 0 0 12px 0;
  font-size: 22px;
}

.error-card p {
  margin: 0 0 20px 0;
  font-size: 14px;
  color: #9aa0b4;
}

.btn {
  padding: 10px 20px;
  border: none;
  border-radius: 8px;
  background: #3b82f6;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.btn:hover {
  background: #2563eb;
}
</style>
