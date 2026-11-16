<template>
  <div class="zs-page pricing-page">
    <div class="zs-page-header">
      <div
        ><h1 class="zs-page-title">定价管理</h1
        ><p class="zs-page-subtitle">统一管理 AI 能力收费，按积分结算</p></div
      >
      <el-button :loading="loading" @click="load">刷新价格</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-tabs v-model="tab" class="zs-table-card" @tab-change="onTabChange">
      <el-tab-pane label="AI 能力定价" name="abilities">
        <div class="pricing-toolbar">
          <el-radio-group v-model="category" aria-label="能力分类">
            <el-radio-button v-for="item in categories" :key="item.key" :value="item.key">{{
              item.label
            }}</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="keyword"
            aria-label="搜索能力"
            placeholder="搜索能力名称"
            clearable
            class="pricing-search"
          />
          <el-checkbox v-model="onlyMissing">仅看待配置</el-checkbox>
        </div>
        <p class="pricing-count"
          >共 {{ filtered.length }} 项能力<span v-if="missing">，{{ missing }} 项待配置</span></p
        >
        <div class="pricing-cards">
          <article v-for="row in filtered" :key="row.subject" class="ability-card">
            <div class="ability-card-heading"
              ><h2>{{ row.label }}</h2
              ><el-tag :type="isPriced(row) ? 'success' : 'warning'">{{
                priceState(row)
              }}</el-tag></div
            >
            <p class="ability-description">{{ row.description }}</p>
            <dl>
              <dt>单价</dt
              ><dd>{{
                isPriced(row)
                  ? `${formatMicros(row.priceMicros)} 积分 / ${unitLabel(row.unit)}`
                  : '待配置'
              }}</dd>
              <dt>最低收费</dt
              ><dd>{{ row.available ? `${formatInteger(row.minCharge)} 积分 / 笔` : '—' }}</dd>
              <dt>价格版本</dt><dd>{{ priceVersionLabel(row.versionText) }}</dd>
            </dl>
            <div class="ability-card-actions"
              ><el-button
                v-if="canWrite"
                type="primary"
                :disabled="!row.available"
                @click="editor?.open(row)"
                >{{ isPriced(row) ? '调整价格' : '设置价格' }}</el-button
              ><el-button @click="openEstimate(row)">费用试算</el-button></div
            >
          </article>
          <el-empty v-if="!filtered.length && !loading" description="暂无符合条件的能力" />
        </div>
        <el-table
          native-scrollbar
          class="pricing-table"
          v-loading="loading"
          :data="filtered"
          row-key="subject"
          empty-text="暂无符合条件的能力"
          scrollbar-always-on
        >
          <el-table-column label="业务能力" min-width="240">
            <template #default="{ row }"
              ><strong>{{ row.label }}</strong
              ><p class="ability-description">{{ row.description }}</p></template
            >
          </el-table-column>
          <el-table-column label="单价" min-width="145"
            ><template #default="{ row }"
              ><span v-if="isPriced(row)"
                >{{ formatMicros(row.priceMicros) }} 积分 / {{ unitLabel(row.unit) }}</span
              ><span v-else>待配置</span></template
            ></el-table-column
          >
          <el-table-column label="最低收费" min-width="120"
            ><template #default="{ row }">{{
              row.available ? `${formatInteger(row.minCharge)} 积分 / 笔` : '—'
            }}</template></el-table-column
          >
          <el-table-column label="状态" min-width="130"
            ><template #default="{ row }"
              ><el-tag :type="isPriced(row) ? 'success' : 'warning'">{{
                priceState(row)
              }}</el-tag></template
            ></el-table-column
          >
          <el-table-column label="价格版本" min-width="110">
            <template #default="{ row }">{{ priceVersionLabel(row.versionText) }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="160" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canWrite"
                type="primary"
                link
                :disabled="!row.available"
                @click="editor?.open(row)"
                >{{ isPriced(row) ? '调整价格' : '设置价格' }}</el-button
              >
              <el-button type="primary" link @click="openEstimate(row)">试算</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="items.some((row) => !row.available)" class="pricing-note"
          >数字人形象和语音克隆的配置入口已列出，待对应服务发布后可设置价格。</p
        >
      </el-tab-pane>
      <el-tab-pane label="调价记录" name="history">
        <p class="pricing-note">显示最近 200 条价格版本记录。</p>
        <el-form inline @submit.prevent="loadHistory">
          <el-form-item label="业务能力"
            ><el-select
              v-model="historySubject"
              clearable
              placeholder="全部能力"
              style="width: 230px"
              ><el-option
                v-for="item in items.filter((row) => row.available)"
                :key="item.subject"
                :value="item.subject"
                :label="item.label" /></el-select
          ></el-form-item>
          <el-form-item
            ><el-button :loading="historyLoading" @click="loadHistory"
              >查询记录</el-button
            ></el-form-item
          >
        </el-form>
        <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" />
        <el-table
          native-scrollbar
          v-else
          v-loading="historyLoading"
          :data="history"
          empty-text="暂无调价记录，当前使用系统基准价格"
          scrollbar-always-on
        >
          <el-table-column prop="label" label="能力" min-width="180" />
          <el-table-column label="单价" min-width="150"
            ><template #default="{ row }"
              >{{ formatMicros(row.priceMicros) }} 积分 / {{ unitLabel(row.unit) }}</template
            ></el-table-column
          >
          <el-table-column prop="minCharge" label="最低收费" min-width="110" />
          <el-table-column label="版本" min-width="100"
            ><template #default="{ row }">v{{ row.version }}</template></el-table-column
          >
          <el-table-column label="状态" min-width="110"
            ><template #default="{ row }">{{
              row.status === 'ACTIVE' ? '生效中' : '已替换'
            }}</template></el-table-column
          >
          <el-table-column prop="operatorName" label="操作人" min-width="130" />
          <el-table-column label="发布时间" min-width="180"
            ><template #default="{ row }">{{
              displayTime(row.effectiveAt)
            }}</template></el-table-column
          >
          <el-table-column prop="reason" label="变更原因" min-width="220" />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="其他价格与成本" name="other">
        <div class="price-links"
          ><article v-for="link in allowedLinks" :key="link.path"
            ><h2>{{ link.title }}</h2
            ><p>{{ link.description }}</p
            ><el-button @click="router.push(link.path)">进入设置</el-button></article
          ></div
        >
        <el-empty v-if="!allowedLinks.length" description="暂无可访问的其他价格配置" />
        <p class="pricing-note"
          >设计委托服务费由总部设计岗位维护；预算材料与施工单价属于设计服务报价，区别于本页的 AI
          调用积分。</p
        >
      </el-tab-pane>
    </el-tabs>
    <PriceEditor ref="editor" @saved="onSaved" />
    <el-dialog v-model="estimateVisible" title="费用试算" width="540px">
      <template v-if="estimateRow">
        <p>{{ estimateRow.label }}</p>
        <el-form label-position="top"
          ><el-form-item :label="`使用数量（${unitLabel(estimateRow.unit)}）`"
            ><el-input
              v-model="quantity"
              inputmode="numeric"
              placeholder="填写正整数" /></el-form-item
        ></el-form>
        <el-alert
          v-if="!isPriced(estimateRow)"
          title="尚未配置有效价格，暂不能试算。"
          type="warning"
          :closable="false"
        />
        <template v-else
          ><p
            >单价 {{ formatMicros(estimateRow.priceMicros) }} 积分 /
            {{ unitLabel(estimateRow.unit) }}，每笔最低
            {{ formatInteger(estimateRow.minCharge) }} 积分</p
          ><p class="estimate-result">预计 {{ estimateCredits(estimateRow, quantity) }} 积分</p
          ><p class="pricing-note"
            >按单价乘数量向上取整，再与最低收费比较。实际扣分以任务确认为准。</p
          ></template
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { checkPermi } from '@/utils/permission'
import { adminDateTime } from '@/utils/adminDisplay'
import { getSubjectPrices } from '@/api/platform/orgPoints'
import { getPriceHistory, type PriceHistory } from '@/api/platform/pricing'
import { formatInteger, formatMicros } from '../org-points/int64'
import {
  buildCatalog,
  categories,
  estimateCredits,
  priceLinks,
  priceState,
  priceVersionLabel,
  unitLabel,
  type PricingItem
} from './catalog'
import PriceEditor from './PriceEditor.vue'

defineOptions({ name: 'PlatformPricing' })
const router = useRouter()
const items = ref<PricingItem[]>([])
const loading = ref(false)
const error = ref('')
const tab = ref('abilities')
const category = ref('all')
const keyword = ref('')
const onlyMissing = ref(false)
const editor = ref<InstanceType<typeof PriceEditor>>()
const history = ref<PriceHistory[]>([])
const historySubject = ref('')
const historyLoading = ref(false)
const historyError = ref('')
const estimateVisible = ref(false)
const estimateRow = ref<PricingItem>()
const quantity = ref('1')
const canWrite = computed(() => checkPermi(['platform:org-point:price:update']))
const allowedLinks = computed(() => priceLinks.filter((link) => checkPermi([link.permission])))
const isPriced = (row: PricingItem) => ['已配置', '系统基准'].includes(priceState(row))
const missing = computed(() => filtered.value.filter((row) => !isPriced(row)).length)
const filtered = computed(() =>
  items.value.filter(
    (row) =>
      (category.value === 'all' || row.category === category.value) &&
      (!onlyMissing.value || !isPriced(row)) &&
      `${row.label} ${row.subject} ${row.description}`
        .toLowerCase()
        .includes(keyword.value.trim().toLowerCase())
  )
)
const displayTime = adminDateTime
const failureText = (failure: unknown, fallback: string) => {
  if (!(failure instanceof Error)) return fallback
  if (/status code 50\d|Network Error/.test(failure.message)) return '服务暂不可用，请稍后刷新重试'
  return failure.message
}
async function load() {
  if (!checkPermi(['platform:org-point:price:query'])) {
    error.value = '当前账号没有定价查询权限'
    return
  }
  loading.value = true
  error.value = ''
  try {
    items.value = buildCatalog(await getSubjectPrices())
  } catch (failure) {
    items.value = []
    error.value = failureText(failure, '价格加载失败，请重试')
  } finally {
    loading.value = false
  }
}
async function loadHistory() {
  if (historyLoading.value || !checkPermi(['platform:org-point:price:query'])) return
  historyLoading.value = true
  historyError.value = ''
  try {
    history.value = await getPriceHistory(historySubject.value || undefined)
  } catch (failure) {
    history.value = []
    historyError.value = failureText(failure, '调价记录加载失败，请重试')
  } finally {
    historyLoading.value = false
  }
}
function onTabChange() {
  if (tab.value === 'history') void loadHistory()
}
async function onSaved() {
  await load()
  if (tab.value === 'history') await loadHistory()
}
function openEstimate(row: PricingItem) {
  estimateRow.value = row
  quantity.value = row.unit === 'SECOND' ? '30' : '1'
  estimateVisible.value = true
}
onMounted(load)
</script>

<style scoped>
.pricing-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 14px 0;
}

.pricing-toolbar :deep(.el-radio-group) {
  flex-wrap: wrap;
  gap: 6px;
}

.pricing-toolbar :deep(.el-radio-button__inner) {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  box-shadow: none;
}

.pricing-search {
  width: 220px;
  max-width: 100%;
}

.pricing-cards {
  display: none;
}

.ability-card {
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
}

.ability-card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.ability-card h2 {
  margin: 0;
  font-size: 17px;
}

.ability-card dl {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 10px;
  margin: 18px 0;
}

.ability-card dt {
  color: var(--el-text-color-secondary);
}

.ability-card dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.ability-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ability-card-actions .el-button {
  margin-left: 0;
}

@container (max-width: 900px) {
  .pricing-table {
    display: none;
  }

  .pricing-cards {
    display: grid;
    gap: 14px;
    grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr));
  }
}

.pricing-count,
.pricing-note,
.ability-description {
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.ability-description {
  margin: 6px 0 0;
}

.pricing-note {
  margin-top: 18px;
}

.price-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr));
  gap: 16px;
  margin: 16px 0;
}

.price-links article {
  padding: 20px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
}

.price-links h2 {
  margin: 0 0 12px;
  font-size: 17px;
}

.price-links p {
  min-height: 48px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.estimate-result {
  font-size: 26px;
  font-weight: 700;
  color: var(--el-color-primary);
}
</style>
