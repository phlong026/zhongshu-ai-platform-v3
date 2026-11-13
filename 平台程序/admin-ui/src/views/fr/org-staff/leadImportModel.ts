/**
 * 客资批量导入前端纯函数（/fr/customers）：CSV 解析、模板、失败行重提、上限。
 * CSV 解析复用 `@/utils/csvImport.ts` 共享模块（批3 提取，禁止复制粘贴）。
 */

import { parseCsvGeneric } from '@/utils/csvImport'

export {
  withinFileSizeLimit,
  createImportGenerationGuard,
  runGuardedImportRequest,
  CSV_MAX_FILE_BYTES
} from '@/utils/csvImport'
export type { ImportGenerationGuard } from '@/utils/csvImport'

export const LEAD_IMPORT_MAX_ROWS = 500

export const LEAD_IMPORT_HEADER = ['姓名', '手机', '需求', '来源'] as const

export interface LeadImportRow {
  line: number
  name: string
  phone: string
  need: string
  source: string
  error?: string
}

export interface LeadImportResultRow {
  line: number
  name: string
  phone: string
  need: string
  source: string
  status: string
  code?: number | null
  message?: string | null
  leadId?: number | string | null
  customerId?: number | string | null
  note?: string | null
}

export interface LeadImportResult {
  orgId: number | string
  total: number
  created: number
  skipped: number
  failed: number
  rows: LeadImportResultRow[]
}

export function withinLeadImportLimit(rows: unknown[]): boolean {
  return rows.length >= 1 && rows.length <= LEAD_IMPORT_MAX_ROWS
}

export function parseLeadImportCsv(text: string): { rows: LeadImportRow[]; errors: string[] } {
  return parseCsvGeneric<LeadImportRow>(
    text,
    LEAD_IMPORT_HEADER,
    (cells, line) => {
      const [name, phone, need, source] = cells
      return { line, name, phone, need, source }
    },
    (row) => `${row.phone}`
  )
}

export function buildLeadImportTemplateCsv(): string {
  return `\uFEFF${LEAD_IMPORT_HEADER.join(',')}\r\n`
}

export function failedLeadRowsForRetry(rows: LeadImportResultRow[]): LeadImportRow[] {
  return rows
    .filter((row) => row.status === 'FAILED')
    .map((row) => ({
      line: row.line,
      name: row.name,
      phone: row.phone,
      need: row.need,
      source: row.source
    }))
}

// re-exports from csvImport
export { parseCsvGeneric } from '@/utils/csvImport'

/**
 * 导入批次号（零 DDL 幂等键的一部分）：一次导入一个，重试失败行时保持不变，
 * 服务端键 = lead-import:{orgId}:{sha256(orgId:batchId)前16位}:{line}。
 */
export function newImportBatchId(): string {
  const cryptoObj = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (cryptoObj?.randomUUID) return cryptoObj.randomUUID().replace(/-/g, '').slice(0, 32)
  return `b${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}
