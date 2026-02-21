import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../page/HomeView.vue'
import UserLogin from '../page/User/UserLogin.vue'
import UserSettings from '../page/User/UserSettings.vue'
import AdminHome from '@/page/Admin/AdminHome.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/user/login',
      name: 'user-login',
      component: UserLogin,
    },
    {
      path: '/user/settings',
      name: 'user-settings',
      component: UserSettings,
    },
    {
      path: '/admin/manage',
      name: 'admin-manage',
      component: AdminHome,
    },
  ],
})

export default router
