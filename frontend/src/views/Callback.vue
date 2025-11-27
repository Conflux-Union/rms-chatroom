<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

onMounted(async () => {
  const token = route.query.token as string
  
  if (token) {
    auth.setToken(token)
    const valid = await auth.verifyToken()
    if (valid) {
      router.push('/')
      return
    }
  }
  
  // If we only got sso_user, redirect to login
  router.push('/login')
})
</script>

<template>
  <div class="callback-container">
    <div class="loading">
      <div class="spinner"></div>
      <p>正在处理登录...</p>
    </div>
  </div>
</template>

<style scoped>
.callback-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background-color: #36393f;
}

.loading {
  text-align: center;
  color: #fff;
}

.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid #5865f2;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 16px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
