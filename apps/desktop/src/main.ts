import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import 'zhimo-ui/tokens.css'
import 'zhimo-ui'
import '@rms-discord/shared/style.css'
import { initI18n, installTelemetry } from '@rms-discord/shared'

initI18n()

const app = createApp(App)

app.use(createPinia())
app.use(router)
installTelemetry(app, router)

app.mount('#app')
