import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import router from './router'
import App from './App.vue'
import '@rms-discord/shared/style.css'

// Lazy load background image
const bgImg = new Image()
bgImg.onload = () => document.body.classList.add('bg-loaded')
bgImg.src = '/bg.webp'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(naive)

app.mount('#app')
