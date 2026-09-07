/**
 * 应用入口，createApp + pinia + router + 挂载，
 * NaiveUI 组件按需经 unplugin-vue-components 自动导入，无需全局注册，
 * 全局 css 变量/样式在 styles/index.css，variables.css 经 @import。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/index.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
