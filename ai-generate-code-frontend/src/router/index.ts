import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../page/HomeView.vue'
import UserLogin from '../page/User/UserLogin.vue'
import UserSettings from '../page/User/UserSettings.vue'
import AdminHome from '@/page/Admin/AdminHome.vue'
import AdminAppManage from '@/page/Admin/AdminAppManage.vue'
import AdminApplyManage from '@/page/Admin/AdminApplyManage.vue'
import AppChatView from '@/page/App/AppChatView.vue'
import AppEditView from '@/page/App/AppEditView.vue'
import CodeGenerateEntry from '@/page/App/CodeGenerateEntry.vue'

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
      path: '/admin/users',
      name: 'admin-users',
      component: AdminHome,
    },
    {
      path: '/admin/apply',
      name: 'admin-apply',
      component: AdminApplyManage,
    },
    {
      path: '/admin/apps',
      name: 'admin-apps',
      component: AdminAppManage,
    },
    {
      path: '/code/generate',
      name: 'code-generate',
      component: CodeGenerateEntry,
    },
    {
      path: '/app/:id/chat',
      name: 'app-chat',
      component: AppChatView,
      props: true,
    },
    {
      path: '/app/:id/edit',
      name: 'app-edit',
      component: AppEditView,
      props: true,
    },
  ],
})

export default router
