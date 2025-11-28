import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('./views/Main.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('./views/Login.vue'),
  },
  {
    path: '/callback',
    name: 'Callback',
    component: () => import('./views/Callback.vue'),
  },
  {
    path: '/voice-invite/:token',
    name: 'VoiceInvite',
    component: () => import('./views/VoiceInvite.vue'),
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
