import { watch } from 'vue'
import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'

let installed = false

// logout() clears state but never navigates, and the router guard only checks
// auth when entering a protected route — so any logout (manual button, failed
// token refresh, rejected verifyToken) used to strand the user on Main.
// React to the auth state itself so every logout path lands on /login;
// replace() keeps the back button out of a logged-out session.
export function installAuthRedirect(router: Router) {
  if (installed) return
  installed = true

  const auth = useAuthStore()
  watch(
    () => auth.isLoggedIn,
    (loggedIn, wasLoggedIn) => {
      if (wasLoggedIn && !loggedIn && router.currentRoute.value.name !== 'Login') {
        router.replace('/login')
      }
    }
  )
}
