<script setup lang="ts">
/**
 * 外观设置弹窗，用户背景，全角色可见 + 登录页背景，管理员额外区，n-divider 分隔。
 * 打开时从 store 读值初始化本地编辑模型，保存写回，用户背景 saveUser 后即时刷新，
 * 管理员登录背景 saveLogin 后 loadLogin 重拉，分设备开关开启后按三断点档分别配置，缺省继承顶层。
 */
import { computed, ref, watch } from 'vue'
import { useMessage, type UploadCustomRequestOptions } from 'naive-ui'
import { uploadBackgroundFile, type BackgroundBusinessType } from '@/api/background'
import { useAuthStore } from '@/stores/auth'
import { useBackgroundStore } from '@/stores/background'
import { useAuthedImageUrl } from '@/utils/backgroundImage'
import type { BackgroundCfg, BreakpointKey, BreakpointOverride, Fit } from '@/types/background'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [value: boolean] }>()

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
})

const message = useMessage()
const auth = useAuthStore()
const background = useBackgroundStore()

/** 断点档键/标签，tab name 取键，与 store.resolveFor 入参一致 */
const BP_KEYS: BreakpointKey[] = ['desktop', 'tablet', 'mobile']
const BP_LABELS: Record<BreakpointKey, string> = { desktop: '桌面', tablet: '平板', mobile: '手机' }

/** 单档控件模型，darken 0-80、opacity 0-100 百分制，保存时 /100 还原 */
interface DeviceSection {
  imageUrl: string
  fit: Fit
  blur: number
  darken: number
  opacity: number
  uploading: boolean
}

/** 一个背景区，用户/登录页共用控件集的编辑模型 */
interface SectionModel {
  key: 'user' | 'login'
  title: string
  businessType: BackgroundBusinessType
  /** 上传后拼 url，用户级 /api/files/{id}/download，登录级 /api/public/files/{id} */
  urlPrefix: string
  urlSuffix: string
  enabled: boolean
  /** 图片来源，upload 上传新图 / url 外链 */
  imageSource: 'upload' | 'url'
  imageUrl: string
  fit: Fit
  blur: number
  darken: number
  opacity: number
  /** 内容区不透明度，百分制，全局属性不进断点档 */
  contentOpacity: number
  perDevice: boolean
  uploading: boolean
  devices: Record<BreakpointKey, DeviceSection>
}

/** 断点档覆盖转控件模型，缺省继承顶层，imageUrl 空串=继承全局图片 */
function overrideToDevice(top: BackgroundCfg, ov: BreakpointOverride | null): DeviceSection {
  return {
    imageUrl: ov?.imageUrl ?? top.imageUrl ?? '',
    fit: ov?.fit ?? top.fit,
    blur: ov?.blur ?? top.blur,
    darken: Math.round((ov?.darken ?? top.darken) * 100),
    // opacity ?? 1 兜底，存量配置无 opacity 字段，默认不透明，缺省不得 NaN
    opacity: Math.round((ov?.opacity ?? top.opacity ?? 1) * 100),
    uploading: false,
  }
}

/** store 配置转编辑模型，打开弹窗时初始化 */
function cfgToSection(cfg: BackgroundCfg, key: 'user' | 'login'): SectionModel {
  const isUser = key === 'user'
  return {
    key,
    title: isUser ? '我的背景' : '登录页背景设置',
    businessType: isUser ? 'USER_BACKGROUND' : 'LOGIN_BACKGROUND',
    urlPrefix: isUser ? '/api/files' : '/api/public/files',
    urlSuffix: isUser ? '/download' : '',
    enabled: cfg.enabled,
    // 内部上传形态（/api/...）与外部 url 区分，仅决定默认展示哪类控件
    imageSource: cfg.imageUrl && !cfg.imageUrl.startsWith('/api/') ? 'url' : 'upload',
    imageUrl: cfg.imageUrl ?? '',
    fit: cfg.fit,
    blur: cfg.blur,
    darken: Math.round(cfg.darken * 100),
    // opacity ?? 1 兜底，存量配置无 opacity 字段，默认不透明，不得 NaN
    opacity: Math.round((cfg.opacity ?? 1) * 100),
    // contentOpacity ?? 1 兜底，存量配置无该字段，内容区不透明，不得 NaN
    contentOpacity: Math.round((cfg.contentOpacity ?? 1) * 100),
    perDevice: !!(cfg.desktop || cfg.tablet || cfg.mobile),
    uploading: false,
    devices: {
      desktop: overrideToDevice(cfg, cfg.desktop),
      tablet: overrideToDevice(cfg, cfg.tablet),
      mobile: overrideToDevice(cfg, cfg.mobile),
    },
  }
}

/** 控件模型转断点档覆盖，全字段写入，imageUrl 空串=null 继承全局 */
function deviceToOverride(d: DeviceSection): BreakpointOverride {
  return { imageUrl: d.imageUrl || null, fit: d.fit, blur: d.blur, darken: d.darken / 100, opacity: d.opacity / 100 }
}

/** 编辑模型写回 store 配置，perDevice 关闭时断点档置 null=完全继承 */
function sectionToCfg(s: SectionModel): BackgroundCfg {
  const cfg: BackgroundCfg = {
    enabled: s.enabled,
    imageUrl: s.imageUrl || null,
    fit: s.fit,
    blur: s.blur,
    darken: s.darken / 100,
    opacity: s.opacity / 100,
    contentOpacity: s.contentOpacity / 100,
    desktop: null,
    tablet: null,
    mobile: null,
  }
  if (s.perDevice) {
    cfg.desktop = deviceToOverride(s.devices.desktop)
    cfg.tablet = deviceToOverride(s.devices.tablet)
    cfg.mobile = deviceToOverride(s.devices.mobile)
  }
  return cfg
}

/** 编辑模型数组，index 0=用户背景，全角色，index 1=登录页背景，仅管理员 */
const sections = ref<SectionModel[]>([])

/** 打开弹窗时从 store 读当前值初始化 */
watch(show, (v) => {
  if (v) {
    sections.value = [cfgToSection(background.userCfg, 'user'), cfgToSection(background.loginCfg, 'login')]
  }
})

/** 上传背景图，成功后按所属形态拼 url，businessId 传当前用户 id，后端强制本人 */
function buildUploadHandler(sec: SectionModel, target: SectionModel | DeviceSection, isSection: boolean) {
  return async (options: UploadCustomRequestOptions) => {
    const file = options.file.file
    if (!file) {
      message.warning('未获取到文件')
      options.onError()
      return
    }
    target.uploading = true
    try {
      const id = await uploadBackgroundFile(file, sec.businessType, auth.user?.id ?? '')
      target.imageUrl = `${sec.urlPrefix}/${id}${sec.urlSuffix}`
      if (isSection) sec.imageSource = 'upload'
      options.onFinish()
    } catch {
      // http.ts 已统一提示
      options.onError()
    } finally {
      target.uploading = false
    }
  }
}

/** 保存，saveUser 即时刷新，登录背景 saveLogin 后 loadLogin 重拉 */
async function onSave() {
  if (!sections.value[0]) return
  try {
    await background.saveUser(sectionToCfg(sections.value[0]))
    if (auth.isAdmin && sections.value[1]) {
      await background.saveLogin(sectionToCfg(sections.value[1]))
      void background.loadLogin()
    }
    message.success('外观设置已保存')
    show.value = false
  } catch {
    // 保存失败保留弹窗便于重试，http.ts 已统一提示
  }
}

const blurMarks = { 0: '0', 5: '5', 10: '10', 15: '15', 20: '20' }
const darkenMarks = { 0: '0%', 40: '40%', 80: '80%' }
const opacityMarks = { 0: '0%', 50: '50%', 100: '100%' }
const imageAccept = 'image/*'

/** 分设备预览当前 tab，与 n-tabs 绑定，未开启分设备时恒 desktop 档位 */
const previewBp = ref<BreakpointKey>('desktop')

/** 预览样式接口 */
interface PreviewStyle {
  url: string
  fit: Fit
  blur: number
  darken: number
  opacity: number
  hasImage: boolean
}

/**
 * 实时预览，把编辑中模型，含分设备覆盖，模拟 store.resolveFor 合并语义，转渲染样式，
 * 图片 url / 拉伸模式（object-fit）/ 模糊（filter blur）/ 暗度，蒙层 opacity / 图片透明度，
 * v-model 双向绑定使控件变化即时反映，保存前即可所见即所得
 */
function previewOf(s: SectionModel): PreviewStyle {
  const d = s.devices[previewBp.value]
  const url = (s.perDevice && d.imageUrl ? d.imageUrl : '') || s.imageUrl || ''
  const fit = s.perDevice ? d.fit : s.fit
  const blur = s.perDevice ? d.blur : s.blur
  const darken = (s.perDevice ? d.darken : s.darken) / 100
  const opacity = (s.perDevice ? d.opacity : s.opacity) / 100
  return { url, fit, blur, darken, opacity, hasImage: !!url }
}

/** 预览图源，/api/ 同源图经 axios blob 带 token，img 原生加载 401 不显示 */
const previewSrc0 = useAuthedImageUrl(
  computed(() => (sections.value[0] ? previewOf(sections.value[0]).url : null)),
)
const previewSrc1 = useAuthedImageUrl(
  computed(() => (sections.value[1] ? previewOf(sections.value[1]).url : null)),
)
function previewSrcOf(si: number) {
  return si === 0 ? previewSrc0.value : previewSrc1.value
}
</script>

<template>
  <n-modal v-model:show="show" preset="card" title="外观设置" :bordered="false" style="width: min(680px, calc(100vw - 32px))">
    <div class="bg-settings">
      <template v-for="(sec, si) in sections" :key="sec.key">
        <!-- 管理员额外区，与用户区 n-divider 分隔 -->
        <n-divider v-if="si === 1" title-placement="left" style="margin-top: 24px">管理员：登录页背景</n-divider>
        <div v-if="si === 0 || auth.isAdmin" class="bg-settings__section">
          <div class="bg-settings__head">
            <span class="bg-settings__title">{{ sec.title }}</span>
            <n-switch v-model:value="sec.enabled" size="small">
              <template #checked>启用</template>
              <template #unchecked>停用</template>
            </n-switch>
          </div>

          <template v-if="sec.enabled">
            <!-- 实时预览，图片/拉伸/模糊/暗度/透明度全效即时反映，分设备开启时跟随当前 tab -->
            <div class="bg-settings__row bg-settings__preview-row">
              <span class="bg-settings__label">预览效果</span>
              <div class="bg-preview" :class="{ 'has-img': previewOf(sec).hasImage }">
                <img
                  v-if="previewOf(sec).hasImage"
                  :src="previewSrcOf(si) ?? undefined"
                  :style="{
                    objectFit: previewOf(sec).fit,
                    filter: `blur(${previewOf(sec).blur}px)`,
                    opacity: previewOf(sec).opacity,
                  }"
                  alt="背景预览"
                />
                <div class="bg-preview__darken" :style="{ opacity: previewOf(sec).darken }" />
                <span class="bg-preview__hint">{{ previewOf(sec).hasImage ? '实时预览' : '未设置图片' }}</span>
              </div>
            </div>

            <!-- 图片来源，上传新图 / 外链 url -->
            <div class="bg-settings__row">
              <span class="bg-settings__label">图片来源</span>
              <n-radio-group v-model:value="sec.imageSource" size="small">
                <n-radio-button value="upload">上传新图</n-radio-button>
                <n-radio-button value="url">外链 URL</n-radio-button>
              </n-radio-group>
            </div>
            <div v-if="sec.imageSource === 'upload'" class="bg-settings__row">
              <span class="bg-settings__label">图片文件</span>
              <n-upload :show-file-list="false" :accept="imageAccept" :custom-request="buildUploadHandler(sec, sec, true)">
                <n-button size="small" :loading="sec.uploading">上传图片</n-button>
              </n-upload>
            </div>
            <div v-else class="bg-settings__row">
              <span class="bg-settings__label">图片 URL</span>
              <n-input v-model:value="sec.imageUrl" placeholder="https://... 或 /api/..." clearable size="small" style="width: 320px" />
            </div>

            <!-- 拉伸模式 -->
            <div class="bg-settings__row">
              <span class="bg-settings__label">拉伸模式</span>
              <n-radio-group v-model:value="sec.fit" size="small">
                <n-radio-button value="cover">铺满</n-radio-button>
                <n-radio-button value="contain">完整</n-radio-button>
                <n-radio-button value="fill">拉伸</n-radio-button>
              </n-radio-group>
            </div>

            <!-- 模糊级别 / 黑暗程度 / 图片透明度 -->
            <div class="bg-settings__row">
              <span class="bg-settings__label">模糊级别</span>
              <n-slider v-model:value="sec.blur" :min="0" :max="20" :step="1" :marks="blurMarks" class="bg-settings__slider" />
            </div>
            <div class="bg-settings__row">
              <span class="bg-settings__label">黑暗程度</span>
              <n-slider v-model:value="sec.darken" :min="0" :max="80" :step="5" :marks="darkenMarks" class="bg-settings__slider" />
              <span class="bg-settings__value">{{ sec.darken }}%</span>
            </div>
            <div class="bg-settings__row">
              <span class="bg-settings__label">图片透明度</span>
              <n-slider v-model:value="sec.opacity" :min="0" :max="100" :step="5" :marks="opacityMarks" class="bg-settings__slider" />
              <span class="bg-settings__value">{{ sec.opacity }}%</span>
            </div>

            <!-- 内容区组件不透明度，全局属性，<100% 时卡片/表格等背景半透明，露出背景图 -->
            <div class="bg-settings__row">
              <span class="bg-settings__label">内容区透明度</span>
              <n-slider v-model:value="sec.contentOpacity" :min="50" :max="100" :step="5" :marks="{ 50: '50%', 75: '75%', 100: '100%' }" class="bg-settings__slider" />
              <span class="bg-settings__value">{{ sec.contentOpacity }}%</span>
              <span class="bg-settings__hint">低于 100% 时内容组件半透明露出背景</span>
            </div>

            <!-- 分设备配置，开启后按桌面/平板/手机三 tab 独立配置 -->
            <div class="bg-settings__row">
              <span class="bg-settings__label">分设备配置</span>
              <n-switch v-model:value="sec.perDevice" size="small" />
              <span class="bg-settings__hint">按桌面 / 平板 / 手机分别设置，未设置项继承全局</span>
            </div>
            <n-tabs v-if="sec.perDevice" v-model:value="previewBp" type="line" size="small" class="bg-settings__tabs">
              <n-tab-pane v-for="bp in BP_KEYS" :key="bp" :name="bp" :tab="BP_LABELS[bp]">
                <div class="bg-settings__row">
                  <span class="bg-settings__label">图片</span>
                  <n-upload
                    :show-file-list="false"
                    :accept="imageAccept"
                    :custom-request="buildUploadHandler(sec, sec.devices[bp], false)"
                  >
                    <n-button size="small" :loading="sec.devices[bp].uploading">上传</n-button>
                  </n-upload>
                  <n-input
                    v-model:value="sec.devices[bp].imageUrl"
                    placeholder="留空继承全局图片"
                    clearable
                    size="small"
                    style="width: 260px"
                  />
                </div>
                <div class="bg-settings__row">
                  <span class="bg-settings__label">拉伸模式</span>
                  <n-radio-group v-model:value="sec.devices[bp].fit" size="small">
                    <n-radio-button value="cover">铺满</n-radio-button>
                    <n-radio-button value="contain">完整</n-radio-button>
                    <n-radio-button value="fill">拉伸</n-radio-button>
                  </n-radio-group>
                </div>
                <div class="bg-settings__row">
                  <span class="bg-settings__label">模糊级别</span>
                  <n-slider v-model:value="sec.devices[bp].blur" :min="0" :max="20" :step="1" :marks="blurMarks" class="bg-settings__slider" />
                </div>
                <div class="bg-settings__row">
                  <span class="bg-settings__label">黑暗程度</span>
                  <n-slider v-model:value="sec.devices[bp].darken" :min="0" :max="80" :step="5" :marks="darkenMarks" class="bg-settings__slider" />
                  <span class="bg-settings__value">{{ sec.devices[bp].darken }}%</span>
                </div>
                <div class="bg-settings__row">
                  <span class="bg-settings__label">图片透明度</span>
                  <n-slider v-model:value="sec.devices[bp].opacity" :min="0" :max="100" :step="5" :marks="opacityMarks" class="bg-settings__slider" />
                  <span class="bg-settings__value">{{ sec.devices[bp].opacity }}%</span>
                </div>
              </n-tab-pane>
            </n-tabs>
          </template>
        </div>
      </template>
    </div>

    <template #footer>
      <div class="bg-settings__footer">
        <n-button size="small" @click="show = false">取消</n-button>
        <n-button type="primary" size="small" @click="onSave">保存</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.bg-settings {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.bg-settings__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.bg-settings__title {
  font-size: 14px;
  font-weight: 600;
}

.bg-settings__row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 32px;
}

.bg-settings__label {
  width: 76px;
  flex-shrink: 0;
  color: var(--fms-text-secondary);
  font-size: 13px;
}

.bg-settings__slider {
  width: 260px;
}

.bg-settings__value {
  width: 40px;
  color: var(--fms-text-secondary);
  font-size: 13px;
}

.bg-settings__hint {
  color: var(--fms-text-muted);
  font-size: 12px;
}

/* 预览容器，模拟登录页渐变底之上的图片效果，fit/blur/darken/opacity 全效 */
.bg-settings__preview-row {
  align-items: flex-start;
}

.bg-preview {
  position: relative;
  width: 320px;
  height: 150px;
  border-radius: var(--fms-radius);
  overflow: hidden;
  background: radial-gradient(400px 200px at 20% -10%, color-mix(in srgb, var(--fms-primary) 22%, transparent), transparent 60%),
    radial-gradient(300px 160px at 90% 110%, color-mix(in srgb, var(--fms-primary) 14%, transparent), transparent 55%);
  border: 1px solid var(--fms-border);
}

.bg-preview img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.bg-preview__darken {
  position: absolute;
  inset: 0;
  background: #000;
  pointer-events: none;
}

.bg-preview__hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fms-text-muted);
  font-size: 12px;
  pointer-events: none;
}

.bg-settings__tabs {
  margin-top: 4px;
}

.bg-settings__footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
