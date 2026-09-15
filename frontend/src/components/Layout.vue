<template>
  <el-container>
    <el-header class="header">
      <div class="logo" @click="$router.push('/')">⚡ 秒杀商城</div>
      <el-menu mode="horizontal" :default-active="activeMenu" router class="nav-menu">
        <el-menu-item index="/">商品</el-menu-item>
        <el-menu-item index="/seckill">秒杀</el-menu-item>
        <el-menu-item index="/orders">我的订单</el-menu-item>
      </el-menu>
      <div class="user-area">
        <el-dropdown @command="handleCommand">
          <span class="user-name">
            {{ auth.userInfo?.nickname || auth.userInfo?.username || '用户' }}
            <el-icon><arrow-down /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => route.path)

function handleCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
      .then(() => {
        auth.logout()
        router.push('/login')
      })
      .catch(() => {})
  }
}
</script>

<style scoped>
.header {
  display: flex;
  align-items: center;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  padding: 0 24px;
}
.logo {
  font-size: 20px;
  font-weight: bold;
  color: #409eff;
  cursor: pointer;
  margin-right: 40px;
}
.nav-menu {
  flex: 1;
  border-bottom: none;
}
.user-area {
  margin-left: auto;
}
.user-name {
  cursor: pointer;
  color: #606266;
  display: flex;
  align-items: center;
  gap: 4px;
}
.main {
  background: #f5f7fa;
  min-height: calc(100vh - 60px);
  padding: 24px;
}
</style>
