import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

import './styles/tokens.css'
import './styles/base.css'
import './styles/layout.css'
import './styles/actions.css'
import './styles/actions-states.css'
import './styles/forms.css'
import './styles/chips.css'
import './styles/dialog.css'
import './styles/alerts.css'
import './styles/states.css'
import './styles/motion.css'

createApp(App).use(router).mount('#app')
