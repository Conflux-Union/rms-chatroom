import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import 'zhimo-ui/tokens.css'
import 'zhimo-ui'
import '@rms-discord/shared/style.css'
import { initTheme, initI18n, installTelemetry, installAuthRedirect } from '@rms-discord/shared'

initTheme()
initI18n()

const app = createApp(App)

app.use(createPinia())
app.use(router)
installTelemetry(app, router)
installAuthRedirect(router)

app.mount('#app')
