import request from './request'

// 登录
export function login(data) {
  return request.post('/api/auth/login', data)
}

// 注册
export function register(data) {
  return request.post('/api/auth/register', data)
}

// 刷新令牌（refresh token 通过 httpOnly Cookie 自动携带，无需传参）
export function refreshToken() {
  return request.post('/api/auth/refresh')
}

// 当前用户信息
export function getUserInfo() {
  return request.get('/api/auth/info')
}

// 登出（通知后端将当前 accessToken 加入黑名单，立即失效，并清除 refresh Cookie）
export function logout() {
  return request.post('/api/auth/logout')
}
