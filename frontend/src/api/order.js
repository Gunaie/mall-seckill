import request from './request'

// 我的订单分页
export function getOrderPage(params) {
  return request.get('/api/orders/page', { params })
}

// 订单详情
export function getOrderDetail(id) {
  return request.get(`/api/orders/${id}`)
}

// 模拟支付
export function payOrder(id) {
  return request.post(`/api/orders/${id}/pay`)
}

// 取消订单
export function cancelOrder(id) {
  return request.post(`/api/orders/${id}/cancel`)
}

// 确认收货
export function confirmOrder(id) {
  return request.post(`/api/orders/${id}/confirm`)
}
