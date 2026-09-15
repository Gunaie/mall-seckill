import request from './request'

// 上架商品分页
export function getSpuPage(params) {
  return request.get('/api/spu/page', { params })
}

// 商品详情
export function getSpuDetail(id) {
  return request.get(`/api/spu/${id}`)
}
