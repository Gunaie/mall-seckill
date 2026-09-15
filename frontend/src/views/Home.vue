<template>
  <div class="home">
    <el-row :gutter="20">
      <el-col :span="18">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>商品列表</span>
              <el-input v-model="keyword" placeholder="搜索商品" style="width: 240px" clearable @keyup.enter="loadData" />
            </div>
          </template>
          <el-row :gutter="16">
            <el-col :span="8" v-for="spu in list" :key="spu.id" style="margin-bottom: 16px">
              <el-card shadow="hover" class="spu-card" @click="$router.push(`/seckill`)">
                <div class="spu-image">
                  <img :src="spu.mainImage || placeholder" :alt="spu.name" @error="onImgError" />
                </div>
                <div class="spu-name">{{ spu.name }}</div>
                <div class="spu-subtitle">{{ spu.subtitle }}</div>
                <div class="spu-price">¥{{ spu.minPrice }}</div>
              </el-card>
            </el-col>
          </el-row>
          <el-empty v-if="list.length === 0 && !loading" description="暂无商品" />
        </el-card>
        <el-pagination
          v-if="total > 0"
          class="pagination"
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="pageSize"
          v-model:current-page="pageNum"
          @current-change="loadData"
        />
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <template #header><span>秒杀入口</span></template>
          <p style="color: #606266; line-height: 1.8">
            每日限时秒杀，超低价格抢购热门商品。
          </p>
          <el-button type="danger" size="large" style="width: 100%" @click="$router.push('/seckill')">
            立即抢购 →
          </el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getSpuPage } from '../api/spu'

const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(12)
const keyword = ref('')
const loading = ref(false)
const placeholder = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyMDAiIGhlaWdodD0iMjAwIiB2aWV3Qm94PSIwIDAgMjAwIDIwMCI+PHJlY3Qgd2lkdGg9IjIwMCIgaGVpZ2h0PSIyMDAiIGZpbGw9IiNlNGU3ZWQiLz48dGV4dCB4PSIxMDAiIHk9IjEwMCIgZm9udC1mYW1pbHk9IkFyaWFsIiBmb250LXNpemU9IjE0IiBmaWxsPSIjOTBhNGFiIiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBkeT0iLjNlbSI+5Yqg5ZOB5Zu+54mHPC90ZXh0Pjwvc3ZnPg=='

async function loadData() {
  loading.value = true
  try {
    const data = await getSpuPage({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value })
    list.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function onImgError(e) {
  const img = e.target
  if (img.src !== placeholder) {
    img.src = placeholder
  }
}

onMounted(loadData)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.spu-card {
  cursor: pointer;
  height: 100%;
}
.spu-image {
  width: 100%;
  height: 180px;
  overflow: hidden;
  margin-bottom: 10px;
}
.spu-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.spu-name {
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.spu-subtitle {
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.spu-price {
  color: #f56c6c;
  font-size: 18px;
  font-weight: bold;
}
.pagination {
  margin-top: 20px;
  text-align: center;
}
</style>
