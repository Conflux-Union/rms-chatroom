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
    // Deep links to a channel, and to a specific message inside it.
    // IDs are global, so the channel group level of the hierarchy is not part
    // of the path (a channel moving between groups must not break the link).
    path: '/:serverId(\\d+)/:channelId(\\d+)',
    name: 'ChannelPath',
    component: () => import('@rms-discord/shared/views/Main.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/:serverId(\\d+)/:channelId(\\d+)/:messageId(\\d+)',
    name: 'MessagePath',
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
    path: '/voice/invite/:token',
    alias: '/voice-invite/:token',
    name: 'VoiceInvite',
    component: () => import('@rms-discord/shared/views/VoiceInvite.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@rms-discord/shared/views/NotFound.vue'),
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
