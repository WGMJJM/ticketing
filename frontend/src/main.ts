import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElButton, ElConfigProvider } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import App from './App.vue'
import router from './router'
import './style.css'

createApp(App)
  .use(createPinia())
  .use(router)
  .component('ElButton', ElButton)
  .component('ElConfigProvider', ElConfigProvider)
  .mount('#app')
