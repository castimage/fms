<script setup lang="ts">
/**
 * 系统管理，模块信息预览，JVM/os/Spring Boot/数据库/Redis/关键运行配置摘要 + 运行时
 * 配置修改指引，系统参数页入口。数据来自 GET /api/admin/system-info，admin 专属，
 * 密钥/口令不外泄，运行时修改运行配置走「系统参数」页，um.params Web CRUD，实时生效。
 */
import { computed, onMounted, ref } from 'vue'
import { NAlert, NButton, NDescriptions, NDescriptionsItem, NTag } from 'naive-ui'
import { useRouter } from 'vue-router'
import PageCard from '@/components/common/PageCard.vue'
import { adminApi } from '@/api/admin'
import type { DbNodeInfo, SystemInfoView } from '@/types/admin'

const router = useRouter()
const info = ref<SystemInfoView | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    info.value = await adminApi.systemInfo()
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

/** 运行时长格式化，s 转天/时/分 */
function formatUptime(s: number): string {
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分钟`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时 ${m % 60} 分`
  return `${Math.floor(h / 24)} 天 ${h % 24} 小时`
}

const uptimeText = computed(() => formatUptime(info.value?.uptimeSecs ?? 0))

/** 配置摘要数组，键值对渲染 */
const configItems = computed(() =>
  Object.entries(info.value?.configSummary ?? {}).map(([label, value]) => ({ label, value })),
)

/** 集群后端实例列表、数据库节点与 Redis 节点列表 */
const backends = computed(() => info.value?.backends ?? [])
const dbNodes = computed(() => info.value?.dbNodes ?? [])
const redisNodes = computed(() => info.value?.redisNodes ?? [])

/** 字节数格式化，B/KB/MB/GB */
function formatBytes(b: number): string {
  if (b < 1024) return `${b} B`
  if (b < 1024 ** 2) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 ** 3) return `${(b / 1024 ** 2).toFixed(1)} MB`
  return `${(b / 1024 ** 3).toFixed(2)} GB`
}

/** 数据库节点状态行，主库为 primary，从库为复制状态 + 同步方式 + WAL 复制延迟 */
function dbStateText(n: DbNodeInfo): string {
  if (n.role === 'master') return n.state
  const parts = [n.state]
  if (n.syncState && n.syncState !== '-') parts.push(n.syncState)
  if (n.replayLagBytes >= 0) parts.push(`延迟 ${formatBytes(n.replayLagBytes)}`)
  return parts.join(' · ')
}

/** Redis 节点角色展示，master/replica/sentinel 映射标签 */
function nodeRoleText(role: string): string {
  return { master: '主', replica: '从', sentinel: '哨兵' }[role] ?? role
}

const nodeTagMap: Record<string, 'error' | 'warning' | 'info' | 'success'> = {
  master: 'error',
  replica: 'warning',
  sentinel: 'info',
}

function nodeTagType(role: string): 'error' | 'warning' | 'info' | 'success' {
  return nodeTagMap[role] ?? 'info'
}
</script>

<template>
  <div class="sysinfo">
    <!-- 运行时修改入口提示 -->
    <n-alert type="info" :bordered="false" class="sysinfo__tip">
      运行时修改运行配置请使用「系统参数」页（Web CRUD，保存后实时生效）；本页为模块运行状态与关键配置只读预览。
      <n-button size="small" type="primary" secondary style="margin-left: 12px" @click="router.push('/admin/params')">
        前往系统参数
      </n-button>
    </n-alert>

    <!-- 进程/框架信息 -->
    <PageCard title="运行时信息">
      <template #extra>
        <n-button text type="primary" size="small" :loading="loading" @click="load">刷新</n-button>
      </template>
      <n-descriptions v-if="info" :column="2" bordered size="small" label-placement="left">
        <n-descriptions-item label="Java 版本">{{ info.javaVersion }}</n-descriptions-item>
        <n-descriptions-item label="操作系统">{{ info.osName }}</n-descriptions-item>
        <n-descriptions-item label="Spring Boot">{{ info.springBootVersion }}</n-descriptions-item>
        <n-descriptions-item label="启动时间">{{ info.startedAt }}</n-descriptions-item>
        <n-descriptions-item label="运行时长">{{ uptimeText }}</n-descriptions-item>
      </n-descriptions>
    </PageCard>

    <!-- 基础设施状态 -->
    <PageCard title="基础设施">
      <n-descriptions v-if="info" :column="2" bordered size="small" label-placement="left">
        <n-descriptions-item label="数据库">
          <span>{{ info.db.reachable ? '可达' : '不可达' }}</span>
          <n-tag v-if="info.db.reachable" size="small" type="success" :bordered="false" style="margin-left: 8px">
            {{ info.db.host }}
          </n-tag>
          <div v-if="info.db.reachable" class="sysinfo__sub">{{ info.db.version }}</div>
        </n-descriptions-item>
        <n-descriptions-item label="Redis">
          <template v-if="info.redis.mode === 'redis'">
            <n-tag :type="info.redis.reachable ? 'success' : 'error'" size="small" :bordered="false">
              {{ info.redis.reachable ? '可达' : '不可达' }}
            </n-tag>
            <template v-if="info.redis.reachable">
              <div class="sysinfo__sub">版本 {{ info.redis.version }} · 键数 {{ info.redis.keys }}</div>
              <div class="sysinfo__sub">内存 {{ (info.redis.usedMemoryBytes ?? 0) / 1024 / 1024 > 1 ? ((info.redis.usedMemoryBytes ?? 0) / 1024 / 1024).toFixed(1) + ' MB' : (info.redis.usedMemoryBytes ?? 0) + ' B' }}（占用率 {{ info.redis.usedMemoryPct }}%）</div>
            </template>
          </template>
          <span v-else>未启用（会话存储 in-memory）</span>
        </n-descriptions-item>
      </n-descriptions>
    </PageCard>

    <!-- 集群状态，任意数量后端实例与 Redis 节点综合信息 -->
    <PageCard title="集群状态">
      <div class="sysinfo__cluster">
        <div class="sysinfo__group">
          <div class="sysinfo__group-title">后端实例（{{ backends.length }}）</div>
          <div class="sysinfo__cards">
            <div v-for="b in backends" :key="b.host" class="sysinfo__card" :class="{ 'sysinfo__card--down': !b.reachable }">
              <div class="sysinfo__card-head">
                <n-tag :type="b.reachable ? 'success' : 'error'" size="small" :bordered="false">
                  {{ b.reachable ? '在线' : '不可达' }}
                </n-tag>
                <span class="sysinfo__card-name">{{ b.instance }}</span>
              </div>
              <div class="sysinfo__sub">{{ b.host }}</div>
              <template v-if="b.reachable">
                <div class="sysinfo__sub">Java {{ b.javaVersion }} · Boot {{ b.springBootVersion }}</div>
                <div class="sysinfo__sub">运行 {{ formatUptime(b.uptimeSecs) }} · Redis {{ b.redisMode || 'in-memory' }}</div>
              </template>
            </div>
          </div>
        </div>
        <div class="sysinfo__group">
          <div class="sysinfo__group-title">数据库节点（{{ dbNodes.length }}）</div>
          <div class="sysinfo__cards">
            <div v-for="n in dbNodes" :key="n.name" class="sysinfo__card">
              <div class="sysinfo__card-head">
                <n-tag :type="nodeTagType(n.role)" size="small" :bordered="false">{{ nodeRoleText(n.role) }}</n-tag>
                <span class="sysinfo__card-name">{{ n.name }}</span>
              </div>
              <div class="sysinfo__sub">{{ n.host }}</div>
              <div class="sysinfo__sub">{{ dbStateText(n) }}</div>
            </div>
          </div>
        </div>
        <div class="sysinfo__group">
          <div class="sysinfo__group-title">Redis 节点（{{ redisNodes.length }}）</div>
          <div class="sysinfo__cards">
            <div v-for="(n, i) in redisNodes" :key="i" class="sysinfo__card">
              <div class="sysinfo__card-head">
                <n-tag :type="nodeTagType(n.role)" size="small" :bordered="false">{{ nodeRoleText(n.role) }}</n-tag>
                <span class="sysinfo__card-name">{{ n.name }}</span>
              </div>
              <div class="sysinfo__sub">{{ n.host }}:{{ n.port }}</div>
              <div class="sysinfo__sub">{{ n.status }}</div>
            </div>
          </div>
        </div>
      </div>
    </PageCard>

    <!-- 关键运行配置摘要 -->
    <PageCard title="关键运行配置">
      <n-descriptions v-if="configItems.length" :column="2" bordered size="small" label-placement="left">
        <n-descriptions-item v-for="c in configItems" :key="c.label" :label="c.label">{{ c.value }}</n-descriptions-item>
      </n-descriptions>
    </PageCard>
  </div>
</template>

<style scoped>
.sysinfo {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sysinfo__tip {
  margin-bottom: 0;
}

.sysinfo__sub {
  color: var(--fms-text-muted);
  font-size: 12px;
  margin-top: 2px;
}

.sysinfo__cluster {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sysinfo__group-title {
  font-size: 13px;
  color: var(--fms-text-muted);
  margin-bottom: 8px;
}

.sysinfo__cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
}

.sysinfo__card {
  border: 1px solid var(--fms-border-color);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--fms-bg-card);
}

.sysinfo__card--down {
  opacity: 0.6;
}

.sysinfo__card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.sysinfo__card-name {
  font-weight: 600;
  font-size: 13px;
}
</style>
