import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { installBrowserAutocompleteSuppression } from './utils/browserAutocomplete'
import { registerAssistantMarkdownFeatures } from './utils/markdownFeatures'
import 'markstream-vue/index.css'
import 'katex/dist/katex.min.css'
import './styles/main.css'

installBrowserAutocompleteSuppression(document.querySelector('#app'))
registerAssistantMarkdownFeatures()
createApp(App).use(createPinia()).use(router).mount('#app')
