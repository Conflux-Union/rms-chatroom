import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import 'zhimo-ui/tokens.css'
import 'zhimo-ui'
import '@rms-discord/shared/style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
