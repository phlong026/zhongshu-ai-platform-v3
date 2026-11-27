import type { SubjectPrice } from '@/api/platform/orgPoints'
import { integerText } from '../org-points/int64'

export const categories = [
  { key: 'all', label: '全部能力' },
  { key: 'video', label: '视频生成' },
  { key: 'character', label: '数字人与声音' },
  { key: 'copy', label: '文案与语音' }
] as const

const details: Record<string, { category: string; label?: string; description: string }> = {
  digital: {
    category: 'video',
    label: '数字人口播视频',
    description: '使用数字人和声音合成口播视频，按视频秒数计费'
  },
  ai_video_768p: { category: 'video', description: 'AI 视频生成 · 768P，按生成时长计费' },
  ai_video_2k: { category: 'video', description: 'AI 视频生成 · 2K，按生成时长计费' },
  avatar_clone: {
    category: 'character',
    label: '数字人形象制作',
    description: '根据授权素材制作视频数字分身，每次制作单独计费'
  },
  voice_clone: {
    category: 'character',
    label: '语音克隆',
    description: '根据授权录音制作克隆声音，每次制作单独计费'
  },
  link_resolution: { category: 'copy', description: '从视频分享链接解析和提取文案' },
  copy: { category: 'copy', description: '文案改写与二次创作' },
  asr: { category: 'copy', description: '上传音视频的语音转写，当前按次计费' },
  note_polish: { category: 'copy', description: '笔记及营销文案润色' }
}

const retiredSubjects = new Set(['ai_flat', 'ai_elevation', 'budget_estimate', 'ai_prompt'])

export const isActivePricingSubject = (subject: string) => !retiredSubjects.has(subject)

export interface PricingItem extends SubjectPrice {
  category: string
  description: string
  available: boolean
}

// 未随服务发布的能力保留可见入口，不编造价格，也不向旧服务发布未知科目。
export function buildCatalog(rows: SubjectPrice[]): PricingItem[] {
  const result = rows
    .filter((row) => isActivePricingSubject(row.subject))
    .map((row) => ({
      ...row,
      label: details[row.subject]?.label || row.label,
      category: details[row.subject]?.category || 'other',
      description: details[row.subject]?.description || '使用服务端提供的计费规则',
      available: true
    }))
  for (const subject of ['avatar_clone', 'voice_clone']) {
    if (result.some((row) => row.subject === subject)) continue
    result.push({
      subject,
      label: details[subject].label!,
      unit: 'CALL',
      priceMicros: '0',
      minCharge: '1',
      versionText: '',
      configured: false,
      category: details[subject].category,
      description: details[subject].description,
      available: false
    })
  }
  return result
}

export function priceState(row: PricingItem) {
  if (!row.available) return '待服务发布'
  const micros = integerText(row.priceMicros)
  if (!micros || BigInt(micros) <= 0n || row.versionText === 'unconfigured') return '待定价'
  return row.configured ? '已配置' : '系统基准'
}

export function priceVersionLabel(version: string) {
  return version === 'unconfigured' ? '尚未发布' : version || '—'
}

export function estimateCredits(row: PricingItem, quantity: string): string {
  if (['待定价', '待服务发布'].includes(priceState(row))) return '—'
  if (!/^\d+$/.test(quantity) || BigInt(quantity) < 1n || BigInt(quantity) > 1_000_000_000n)
    return '—'
  const minimum = integerText(row.minCharge)
  if (!minimum || BigInt(minimum) < 1n) return '—'
  const rounded = (BigInt(quantity) * BigInt(integerText(row.priceMicros)) + 999_999n) / 1_000_000n
  return String(rounded > BigInt(minimum) ? rounded : BigInt(minimum))
}

export const unitLabel = (unit: string) => ({ CALL: '次', SECOND: '秒' })[unit] || unit

export const priceLinks = [
  {
    title: '充值套餐',
    description: '充值金额、基础点数、赠送点数和启用状态。适用于历史钱包充值。',
    path: '/platform-ops/plans',
    permission: 'platform:wallet:plan:query'
  },
  {
    title: '配置审计',
    description: '查询历史运营配置变更。当前能力的积分调价可在“调价记录”查看。',
    path: '/platform-ops/audit',
    permission: 'platform:ops:audit:query'
  }
]
