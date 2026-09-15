import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import router from '../router'

// 统一响应结构：{ code, message, data, timestamp }
// code=200 成功，其它为业务错误
const service = axios.create({
  baseURL: '/',
  timeout: 15000,
  // 携带 httpOnly Cookie（refresh token），同源请求必需
  withCredentials: true
})

// 防止并发请求同时 401 时重复登出/跳转
let isLoggingOut = false

function handleUnauthorized() {
  if (isLoggingOut) return
  isLoggingOut = true
  const auth = useAuthStore()
  auth.logout()
  // 跳转登录页，路由守卫会清空状态；下一次进入页面时重置标记
  router.push('/login').finally(() => {
    setTimeout(() => { isLoggingOut = false }, 500)
  })
  ElMessage.error('登录已过期，请重新登录')
}

// 请求拦截：自动携带 JWT
service.interceptors.request.use(
  (config) => {
    const auth = useAuthStore()
    if (auth.accessToken) {
      config.headers['Authorization'] = `Bearer ${auth.accessToken}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截：统一拆包 + 401 自动刷新 token 重试
service.interceptors.response.use(
  (response) => {
    const res = response.data
    // 非 200 视为业务失败
    if (res.code !== 200) {
      // 401 未登录或 token 失效，尝试刷新 token 后重试
      if (res.code === 401) {
        return handleTokenRefresh(response.config)
      } else {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res.data
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      return handleTokenRefresh(error.config)
    } else if (status === 429) {
      ElMessage.error('请求过于频繁，请稍后再试')
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

/**
 * access token 过期时，用 httpOnly Cookie 中的 refresh token 续签，
 * 续签成功后用新 access token 重试原请求；续签失败则跳转登录。
 * 用 refreshing 标记防止并发 401 重复刷新。
 */
async function handleTokenRefresh(originalConfig) {
  const auth = useAuthStore()
  if (auth.refreshing) {
    // 已有刷新在进行，等待其完成后重试
    await waitForRefresh()
    if (auth.accessToken) {
      originalConfig.headers['Authorization'] = `Bearer ${auth.accessToken}`
      return service.request(originalConfig)
    }
    handleUnauthorized()
    return Promise.reject(new Error('Token 刷新失败'))
  }

  auth.refreshing = true
  try {
    const data = await auth.refresh()
    auth.accessToken = data.accessToken
    originalConfig.headers['Authorization'] = `Bearer ${data.accessToken}`
    return service.request(originalConfig)
  } catch (e) {
    handleUnauthorized()
    return Promise.reject(e)
  } finally {
    auth.refreshing = false
  }
}

function waitForRefresh() {
  return new Promise((resolve) => {
    const check = () => {
      const auth = useAuthStore()
      if (!auth.refreshing) resolve()
      else setTimeout(check, 100)
    }
    check()
  })
}

export default service
