import request from './request'

// 上架商品分页
export function getSpuPage(params) {
  return request.get('/api/spu/page', { params })
}

// 商品搜索（ES + IK：关键词/分类/价格区间/排序/高亮）
export function searchSpu(params) {
  return request.get('/api/search/spu', { params })
}

// 商品详情
export function getSpuDetail(id) {
  return request.get(`/api/spu/${id}`)
}
