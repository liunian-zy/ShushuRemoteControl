import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import RemoteControl from './views/RemoteControl.vue'
import ErrorView from './views/Error.vue'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/remote/:deviceId', component: RemoteControl, props: true },
    { path: '/error', component: ErrorView },
    { path: '/:pathMatch(.*)*', redirect: '/error' }
  ]
})

createApp(App).use(router).mount('#app')
