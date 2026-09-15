import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('../components/Layout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('../views/Home.vue')
      },
      {
        path: 'seckill',
        name: 'Seckill',
        component: () => import('../views/Seckill.vue')
      },
      {
        path: 'orders',
        name: 'Orders',
        component: () => import('../views/Orders.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫：未登录跳转登录页
// 页面刷新时 access token（内存）丢失，尝试用 httpOnly Cookie 中的 refresh token 续签
router.beforeEach(async (to, from, next) => {
  const auth = useAuthStore()
  const requiresAuth = to.meta.requiresAuth !== false

  if (!requiresAuth) {
    // 登录页已登录则跳首页
    if (to.path === '/login' && auth.isLoggedIn) {
      next('/')
    } else {
      next()
    }
    return
  }

  // 需要登录的路由
  if (auth.isLoggedIn) {
    next()
    return
  }

  // access token 不在内存（页面刷新），尝试 refresh 续签
  try {
    await auth.refresh()
    next()
  } catch (e) {
    // refresh 失败（refresh token 过期或不存在），跳转登录
    next('/login')
  }
})

export default router
