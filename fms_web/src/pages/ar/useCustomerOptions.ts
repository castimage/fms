/**
 * 客户下拉选项加载，发票/收款筛选与表单共用，取前 N 条，N 读 um.params web.refdata.page.size
 * 经 /api/config 下发，缺省 200，公司切换自动重载
 */
import { onMounted, ref, watch } from 'vue'
import { arApi } from '@/api/ar'
import { useAppStore } from '@/stores/app'
import { refdataPageSize } from '@/utils/refdata'
import type { SelectOption } from 'naive-ui'

export function useCustomerOptions() {
  const appStore = useAppStore()
  const customerOptions = ref<SelectOption[]>([])

  async function loadCustomers() {
    const res = await arApi.pageCustomers({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId ?? undefined })
    customerOptions.value = res.items.map((c) => ({ label: `${c.code} ${c.name}`, value: c.id }))
  }

  watch(() => appStore.companyId, () => void loadCustomers())
  onMounted(() => void loadCustomers())
  return { customerOptions }
}
