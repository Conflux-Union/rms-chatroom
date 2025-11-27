<script setup lang="ts">
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

function handleLogin() {
  window.location.href = auth.getLoginUrl()
}
</script>

<template>
  <div class="login-container">
    <div class="login-box">
      <h1>RMS Discord</h1>
      <p>Welcome! Please login with your RMS account.</p>
      <button class="login-btn" @click="handleLogin">Login with RMS SSO</button>
    </div>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background-color: #5865f2;
}

.login-box {
  background: #36393f;
  padding: 40px;
  border-radius: 8px;
  text-align: center;
  color: #fff;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.2);
}

.login-box h1 {
  margin: 0 0 16px;
  font-size: 24px;
}

.login-box p {
  margin: 0 0 24px;
  color: #b9bbbe;
}

.login-btn {
  background: #5865f2;
  color: #fff;
  border: none;
  padding: 12px 24px;
  font-size: 16px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.2s;
}

.login-btn:hover {
  background: #4752c4;
}
</style>
