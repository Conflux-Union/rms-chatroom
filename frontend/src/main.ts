import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import './style.css'

// Lazy load background image
const bgImg = new Image()
bgImg.onload = () => document.body.classList.add('bg-loaded')
bgImg.src = '/img/bg.webp'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
