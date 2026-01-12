import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import DeviceList from './views/DeviceList.vue'
import RemoteControl from './views/RemoteControl.vue'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: DeviceList },
    { path: '/control/:deviceId', component: RemoteControl, props: true }
  ]
})

createApp(App).use(router).mount('#app')
