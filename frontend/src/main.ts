import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElButton, ElConfigProvider, ElDialog } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/dialog/style/css'
import App from './App.vue'
import router from './router'
import './style.css'
import './event.css'
import './ticket.css'
import './auth.css'
import './order.css'
import './checkin.css'

createApp(App)
  .use(createPinia())
  .use(router)
  .component('ElButton', ElButton)
  .component('ElConfigProvider', ElConfigProvider)
  .component('ElDialog', ElDialog)
  .mount('#app')
