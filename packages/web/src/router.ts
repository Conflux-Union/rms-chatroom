import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@rms-discord/shared'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@rms-discord/shared/views/Main.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@rms-discord/shared/views/Login.vue'),
  },
  {
    path: '/callback',
    name: 'Callback',
    component: () => import('@rms-discord/shared/views/Callback.vue'),
  },
  {
    path: '/voice-invite/:token',
    name: 'VoiceInvite',
    component: () => import('@rms-discord/shared/views/VoiceInvite.vue'),
    meta: { requiresAuth: false },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()

  if (to.meta.requiresAuth) {
    if (!auth.token) {
      next('/login')
      return
    }
    if (!auth.user) {
      const valid = await auth.verifyToken()
      if (!valid) {
        next('/login')
        return
      }
    }
  }

  next()
})

export default router
