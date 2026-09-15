<template>
  <div class="orders">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>我的订单</span>
          <el-button :icon="Refresh" size="small" @click="loadData">刷新</el-button>
        </div>
      </template>
      <el-table :data="list" v-loading="loading" style="width: 100%">
        <el-table-column prop="orderSn" label="订单号" width="200" />
        <el-table-column label="商品" min-width="200">
          <template #default="{ row }">
            <div v-for="item in row.items" :key="item.id" class="order-item">
              <span>{{ item.spuName }} - {{ item.skuName }}</span>
              <span class="item-qty">x{{ item.quantity }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="金额" width="100">
          <template #default="{ row }">
            <span style="color: #f56c6c; font-weight: 600">¥{{ row.totalAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="statusDesc" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="下单时间" width="170" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" type="primary" size="small" @click="handlePay(row)">支付</el-button>
            <el-button v-if="row.status === 1" size="small" @click="handleCancel(row)">取消</el-button>
            <el-button v-if="row.status === 4" type="success" size="small" @click="handleConfirm(row)">确认收货</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="list.length === 0 && !loading" description="暂无订单" />
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
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getOrderPage, payOrder, cancelOrder, confirmOrder } from '../api/order'

const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)

async function loadData() {
  loading.value = true
  try {
    const data = await getOrderPage({ pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function statusTagType(status) {
  // 1待支付 2已支付 3已取消 4已发货 5已完成
  const map = { 1: 'warning', 2: 'primary', 3: 'info', 4: '', 5: 'success' }
  return map[status] || ''
}

async function handlePay(row) {
  try {
    await ElMessageBox.confirm(`确认支付订单 ${row.orderSn}？`, '模拟支付', { type: 'info' })
    await payOrder(row.id)
    ElMessage.success('支付成功')
    loadData()
  } catch (e) {
    if (e !== 'cancel') return
  }
}

async function handleCancel(row) {
  try {
    await ElMessageBox.confirm('确认取消该订单？', '取消订单', { type: 'warning' })
    await cancelOrder(row.id)
    ElMessage.success('订单已取消')
    loadData()
  } catch (e) {
    if (e !== 'cancel') return
  }
}

async function handleConfirm(row) {
  try {
    await ElMessageBox.confirm('确认已收到商品？', '确认收货', { type: 'warning' })
    await confirmOrder(row.id)
    ElMessage.success('已确认收货')
    loadData()
  } catch (e) {
    if (e !== 'cancel') return
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
.order-item {
  display: flex;
  justify-content: space-between;
  padding: 2px 0;
}
.item-qty {
  color: #909399;
}
.pagination {
  margin-top: 20px;
  text-align: center;
}
</style>
