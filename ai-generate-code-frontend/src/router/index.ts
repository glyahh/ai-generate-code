import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../page/HomeView.vue'
import UserLogin from '../page/User/UserLogin.vue'
import UserSettings from '../page/User/UserSettings.vue'
import UserAppearanceSettings from '../page/User/UserAppearanceSettings.vue'
import UserChatHistory from '../page/User/UserChatHistory.vue'
import AdminHome from '@/page/Admin/AdminHome.vue'
import AdminAppManage from '@/page/Admin/AdminAppManage.vue'
import AdminApplyManage from '@/page/Admin/AdminApplyManage.vue'
import AdminChatManage from '@/page/Admin/AdminChatManage.vue'
import AppChatView from '@/page/App/AppChatView.vue'
import AppEditView from '@/page/App/AppEditView.vue'
import CodeGenerateEntry from '@/page/App/CodeGenerateEntry.vue'
import MainIndexView from '@/page/MainIndex/MainIndexView.vue'
import LoopMarketView from '@/page/Loop/LoopMarketView.vue'
import MyLoopView from '@/page/Loop/MyLoopView.vue'
import LoopCreateView from '@/page/Loop/LoopCreateView.vue'
import LoopEditView from '@/page/Loop/LoopEditView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // 首页
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/main_index',
      name: 'main-index',
      component: MainIndexView,
      meta: { noLayout: true },
    },
    // 用户登录界面
    {
      path: '/user/login',
      name: 'user-login',
      component: UserLogin,
    },
    // 用户设置界面
    {
      path: '/user/settings',
      name: 'user-settings',
      component: UserSettings,
    },
    // 用户外观设置界面
    {
      path: '/user/appearance',
      name: 'user-appearance',
      component: UserAppearanceSettings,
    },
    // 用户查看历史对话
    {
      path: '/user/chats',
      name: 'user-chats',
      component: UserChatHistory,
    },
    // 管理员管理用户界面
    {
      path: '/admin/users',
      name: 'admin-users',
      component: AdminHome,
    },
    // 管理员处理申请
    {
      path: '/admin/apply',
      name: 'admin-apply',
      component: AdminApplyManage,
    },
    // 管理员管理应用界面
    {
      path: '/admin/apps',
      name: 'admin-apps',
      component: AdminAppManage,
    },
    // 管理员对话管理界面
    {
      path: '/admin/chats',
      name: 'admin-chats',
      component: AdminChatManage,
    },
    // 应用ai交互界面 (用户查看创建的应用)
    {
      path: '/code/generate',
      name: 'code-generate',
      component: CodeGenerateEntry,
    },
    // 应用ai交互界面 (用户首次通过导航创建应用)
    {
      path: '/app/:id/chat',
      name: 'app-chat',
      component: AppChatView,
      props: true,
    },
    // 用户编辑应用界面
    {
      path: '/app/:id/edit',
      name: 'app-edit',
      component: AppEditView,
      props: true,
    },
    // Loop 技能市场
    {
      path: '/loop',
      name: 'loop-market',
      component: LoopMarketView,
    },
    {
      path: '/user/loops',
      name: 'my-loop',
      component: MyLoopView,
    },
    {
      path: '/loop/create',
      name: 'loop-create',
      component: LoopCreateView,
    },
    {
      path: '/loop/:id/edit',
      name: 'loop-edit',
      component: LoopEditView,
      props: true,
    },
  ],
})

export default router
