import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { installBrowserAutocompleteSuppression } from './utils/browserAutocomplete'
import 'markstream-vue/index.css'
import './styles/main.css'

installBrowserAutocompleteSuppression(document.querySelector('#app'))
createApp(App).use(createPinia()).use(router).mount('#app')
