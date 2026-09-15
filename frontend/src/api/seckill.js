import request from './request'

// 当日秒杀场次列表
export function getSessions(date) {
  return request.get('/api/seckill/sessions', { params: date ? { date } : {} })
}

// 场次秒杀商品列表
export function getSessionSkus(sessionId) {
  return request.get('/api/seckill/skus', { params: { sessionId } })
}

// 第①步：获取验证码
export function getCaptcha() {
  return request.get('/api/seckill/captcha')
}

// 第②步：验证码换取动态下单路径
export function getSeckillPath(seckillSkuId, data) {
  return request.post(`/api/seckill/${seckillSkuId}/path`, data)
}

// 第③步：携带动态路径抢购
export function executeSeckill(seckillSkuId, path, data) {
  return request.post(`/api/seckill/${seckillSkuId}/${path}/execute`, data)
}

// 轮询秒杀结果
export function getSeckillResult(seckillSkuId) {
  return request.get(`/api/seckill/${seckillSkuId}/result`)
}
