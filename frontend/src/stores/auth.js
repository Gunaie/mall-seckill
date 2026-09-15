import { defineStore } from 'pinia'
import { login as loginApi, getUserInfo, logout as logoutApi, refreshToken as refreshTokenApi } from '../api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // access token 仅存内存（Pinia state），不写 localStorage，降低 XSS 窃取风险；
    // 刷新页面后 access token 丢失，可通过 refresh token（httpOnly Cookie）自动续签
    accessToken: '',
    // refresh token 由后端通过 httpOnly Cookie 下发，前端 JS 不可读，不存储
    userInfo: null,
    // 标记是否正在刷新 token，防止并发 401 重复刷新
    refreshing: false
  }),
  getters: {
    isLoggedIn: (state) => !!state.accessToken
  },
  actions: {
    async login(username, password) {
      const data = await loginApi({ username, password })
      this.accessToken = data.accessToken
      this.userInfo = data.userInfo
      return data
    },
    async fetchUserInfo() {
      const data = await getUserInfo()
      this.userInfo = data
      return data
    },
    async refresh() {
      // refresh token 通过 httpOnly Cookie 自动携带，调用无参 refresh 接口
      const data = await refreshTokenApi()
      this.accessToken = data.accessToken
      return data
    },
    async logout() {
      // 先通知后端将 accessToken 加入黑名单（即使失败也清本地状态，保证登出语义）
      try {
        await logoutApi()
      } catch (e) {
        // 忽略登出接口错误（如 token 已过期），本地状态仍需清理
      }
      this.accessToken = ''
      this.userInfo = null
    }
  }
})
