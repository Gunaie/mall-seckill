<template>
  <div class="seckill">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>⚡ 今日秒杀场次</span>
          <el-button :icon="Refresh" size="small" @click="loadSessions">刷新</el-button>
        </div>
      </template>
      <el-row :gutter="12">
        <el-col :span="6" v-for="s in sessions" :key="s.id">
          <div
            class="session-card"
            :class="{ active: selectedSessionId === s.id }"
            @click="selectSession(s)"
          >
            <div class="session-name">{{ s.name }}</div>
            <div class="session-time">{{ formatTime(s.startTime) }} - {{ formatTime(s.endTime) }}</div>
            <el-tag :type="sessionTagType(s.statusDesc)" size="small">{{ s.statusDesc }}</el-tag>
          </div>
        </el-col>
      </el-row>
      <el-empty v-if="sessions.length === 0" description="今日暂无秒杀场次" />
    </el-card>

    <el-card shadow="never" style="margin-top: 20px" v-if="selectedSession">
      <template #header>
        <span>🔥 {{ selectedSession.name }} - 秒杀商品</span>
      </template>
      <el-row :gutter="16">
        <el-col :span="8" v-for="sku in skus" :key="sku.id" style="margin-bottom: 16px">
          <el-card shadow="hover" class="sku-card">
            <div class="sku-image">
              <img :src="sku.image || placeholder" :alt="sku.spuName" @error="onImgError" />
            </div>
            <div class="sku-spu-name">{{ sku.spuName }}</div>
            <div class="sku-name">{{ sku.skuName }}</div>
            <div class="price-row">
              <span class="seckill-price">¥{{ sku.seckillPrice }}</span>
              <span class="original-price">¥{{ sku.originalPrice }}</span>
            </div>
            <div class="stock-row">
              <el-progress
                :percentage="stockPercent(sku)"
                :color="stockPercent(sku) > 0 ? '#f56c6c' : '#909399'"
                :stroke-width="10"
              />
              <span class="stock-text">剩余 {{ sku.stockRemaining }} 件</span>
            </div>
            <el-button
              type="danger"
              size="large"
              style="width: 100%; margin-top: 10px"
              :disabled="!canBuy(sku)"
              @click="openSeckillDialog(sku)"
            >
              {{ buyButtonText(sku) }}
            </el-button>
          </el-card>
        </el-col>
      </el-row>
      <el-empty v-if="skus.length === 0" description="该场次暂无秒杀商品" />
    </el-card>

    <!-- 秒杀下单弹窗 -->
    <el-dialog v-model="dialogVisible" title="秒杀下单" width="480px" :close-on-click-modal="false" @close="handleDialogClose">
      <el-steps :active="step" finish-status="success" align-center>
        <el-step title="验证码" />
        <el-step title="获取路径" />
        <el-step title="抢购下单" />
      </el-steps>

      <div v-if="step === 0" class="step-content">
        <div class="captcha-box">
          <img :src="captchaImg || placeholder" alt="验证码" @click="loadCaptcha" @error="onImgError" />
          <el-input
            v-model="captchaCode"
            placeholder="输入算术题答案"
            size="large"
            style="width: 160px; margin-left: 12px"
            @keyup.enter="getPath"
          />
        </div>
        <el-button type="primary" size="large" style="width: 100%; margin-top: 20px" :loading="loading" @click="getPath">
          获取秒杀路径
        </el-button>
      </div>

      <div v-if="step === 1" class="step-content">
        <el-alert title="动态路径已获取，正在下单..." type="success" :closable="false" show-icon />
      </div>

      <div v-if="step === 2" class="step-content">
        <div v-if="resultStatus === 'QUEUING'" class="result-queuing">
          <el-icon class="is-loading" :size="48"><Loading /></el-icon>
          <p>排队中，请稍候...</p>
        </div>
        <div v-else-if="resultStatus === 'SUCCESS'" class="result-success">
          <el-result icon="success" title="抢购成功" :sub-title="`订单号：${resultOrderId}`">
            <template #extra>
              <el-button type="primary" @click="goOrders">查看订单</el-button>
            </template>
          </el-result>
        </div>
        <div v-else-if="resultStatus === 'FAIL'" class="result-fail">
          <el-result icon="warning" title="抢购失败" sub-title="商品已抢光或您已抢购过">
            <template #extra>
              <el-button @click="reset">再试一次</el-button>
            </template>
          </el-result>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Loading } from '@element-plus/icons-vue'
import { getSessions, getSessionSkus, getCaptcha, getSeckillPath, executeSeckill, getSeckillResult } from '../api/seckill'

const router = useRouter()
const sessions = ref([])
const selectedSessionId = ref(null)
const selectedSession = ref(null)
const skus = ref([])

// 秒杀弹窗状态
const dialogVisible = ref(false)
const step = ref(0)
const loading = ref(false)
const currentSku = ref(null)
const captchaImg = ref('')
const captchaToken = ref('')
const captchaCode = ref('')
const resultStatus = ref('')
const resultOrderId = ref(null)
let pollTimer = null
let pollCount = 0
const MAX_POLL_COUNT = 40 // 1500ms * 40 = 60s 兜底，防止无限轮询

const placeholder = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyMDAiIGhlaWdodD0iMjAwIiB2aWV3Qm94PSIwIDAgMjAwIDIwMCI+PHJlY3Qgd2lkdGg9IjIwMCIgaGVpZ2h0PSIyMDAiIGZpbGw9IiNlNGU3ZWQiLz48dGV4dCB4PSIxMDAiIHk9IjEwMCIgZm9udC1mYW1pbHk9IkFyaWFsIiBmb250LXNpemU9IjE0IiBmaWxsPSIjOTBhNGFiIiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBkeT0iLjNlbSI+5Yqg5ZOB5Zu+54mHPC90ZXh0Pjwvc3ZnPg=='

function onImgError(e) {
  const img = e.target
  if (img.src !== placeholder) {
    img.src = placeholder
  }
}

async function loadSessions() {
  try {
    const data = await getSessions()
    sessions.value = data || []
    if (sessions.value.length > 0) {
      // 优先选进行中的场次，否则选第一个
      const active = sessions.value.find(s => s.statusDesc === '进行中')
      selectSession(active || sessions.value[0])
    }
  } catch (e) {
    sessions.value = []
  }
}

function selectSession(s) {
  selectedSessionId.value = s.id
  selectedSession.value = s
  loadSkus(s.id)
}

async function loadSkus(sessionId) {
  try {
    const data = await getSessionSkus(sessionId)
    skus.value = data || []
  } catch (e) {
    skus.value = []
  }
}

function formatTime(dt) {
  if (!dt) return ''
  return dt.substring(11, 16)
}

function sessionTagType(desc) {
  if (desc === '进行中') return 'danger'
  if (desc === '未开始') return 'warning'
  return 'info'
}

function stockPercent(sku) {
  if (!sku) return 0
  // 假设秒杀库存最多展示 100%，用剩余数 / (剩余+已售估算)，这里简单用 remaining 做进度条上限
  return Math.min(sku.stockRemaining || 0, 100)
}

function canBuy(sku) {
  return selectedSession.value?.statusDesc === '进行中' && (sku.stockRemaining || 0) > 0
}

function buyButtonText(sku) {
  if (selectedSession.value?.statusDesc === '未开始') return '未开始'
  if ((sku.stockRemaining || 0) <= 0) return '已抢光'
  return '立即抢购'
}

// ===== 秒杀三步流程 =====
function openSeckillDialog(sku) {
  if (!canBuy(sku)) return
  currentSku.value = sku
  dialogVisible.value = true
  step.value = 0
  captchaCode.value = ''
  resultStatus.value = ''
  loadCaptcha()
}

async function loadCaptcha() {
  try {
    const data = await getCaptcha()
    captchaImg.value = data.img
    captchaToken.value = data.captchaToken
  } catch (e) {
    captchaImg.value = ''
    captchaToken.value = ''
    ElMessage.error('验证码获取失败，请重试')
  }
}

async function getPath() {
  if (!captchaCode.value) {
    ElMessage.warning('请输入验证码')
    return
  }
  if (!currentSku.value) return
  loading.value = true
  try {
    const path = await getSeckillPath(currentSku.value.id, {
      captchaToken: captchaToken.value,
      captchaCode: captchaCode.value
    })
    step.value = 1
    // 立即执行抢购
    await doExecute(path)
  } catch (e) {
    // 路径获取失败（验证码错误/过期/限流），重新获取验证码
    ElMessage.warning('验证码错误或已过期，请重新输入')
    loadCaptcha()
    captchaCode.value = ''
  } finally {
    loading.value = false
  }
}

async function doExecute(path) {
  if (!currentSku.value) return
  try {
    // 收货信息（演示用固定值，真实场景应让用户填写）
    const result = await executeSeckill(currentSku.value.id, path, {
      receiverName: '测试用户',
      receiverPhone: '13800138000',
      receiverAddress: '北京市朝阳区测试地址',
      remark: ''
    })
    step.value = 2
    resultStatus.value = result.status
    resultOrderId.value = result.orderId
    if (result.status === 'QUEUING') {
      pollCount = 0
      startPolling()
    }
  } catch (e) {
    step.value = 2
    resultStatus.value = 'FAIL'
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!currentSku.value) {
      stopPolling()
      return
    }
    pollCount++
    if (pollCount > MAX_POLL_COUNT) {
      stopPolling()
      resultStatus.value = 'FAIL'
      ElMessage.warning('抢购结果查询超时，请稍后在订单页查看')
      return
    }
    try {
      const data = await getSeckillResult(currentSku.value.id)
      resultStatus.value = data.status
      resultOrderId.value = data.orderId
      if (data.status !== 'QUEUING') {
        stopPolling()
      }
    } catch (e) {
      // 轮询失败忽略，继续下一次
    }
  }, 1500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function handleDialogClose() {
  stopPolling()
}

function reset() {
  step.value = 0
  resultStatus.value = ''
  captchaCode.value = ''
  loadCaptcha()
}

function goOrders() {
  dialogVisible.value = false
  router.push('/orders')
}

onMounted(loadSessions)
onUnmounted(stopPolling)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.session-card {
  padding: 16px;
  border: 2px solid #ebeef5;
  border-radius: 8px;
  cursor: pointer;
  text-align: center;
  transition: all 0.2s;
}
.session-card:hover {
  border-color: #f56c6c;
}
.session-card.active {
  border-color: #f56c6c;
  background: #fef0f0;
}
.session-name {
  font-weight: 600;
  margin-bottom: 6px;
}
.session-time {
  color: #909399;
  font-size: 13px;
  margin-bottom: 8px;
}
.sku-card {
  height: 100%;
}
.sku-image {
  width: 100%;
  height: 160px;
  overflow: hidden;
  margin-bottom: 10px;
}
.sku-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.sku-spu-name {
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sku-name {
  color: #909399;
  font-size: 13px;
  margin-bottom: 8px;
}
.price-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
}
.seckill-price {
  color: #f56c6c;
  font-size: 20px;
  font-weight: bold;
}
.original-price {
  color: #c0c4cc;
  text-decoration: line-through;
  font-size: 13px;
}
.stock-row {
  margin-bottom: 4px;
}
.stock-text {
  font-size: 12px;
  color: #909399;
}
.step-content {
  margin-top: 30px;
}
.captcha-box {
  display: flex;
  align-items: center;
  justify-content: center;
}
.captcha-box img {
  height: 50px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: pointer;
}
.result-queuing {
  text-align: center;
  color: #409eff;
  padding: 20px;
}
</style>
