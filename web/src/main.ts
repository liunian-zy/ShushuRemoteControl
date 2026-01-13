import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Login from './views/Login.vue'
import DeviceList from './views/DeviceList.vue'
import RemoteControl from './views/RemoteControl.vue'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login, meta: { public: true } },
    { path: '/', component: DeviceList },
    { path: '/control/:deviceId', component: RemoteControl, props: true }
  ]
})

// 路由守卫 - 检查登录状态
router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('auth_token')

  if (to.meta.public) {
    // 公开页面，直接放行
    next()
  } else if (!token) {
    // 未登录，跳转到登录页
    next('/login')
  } else {
    // 已登录，放行
    next()
  }
})

createApp(App).use(router).mount('#app')
