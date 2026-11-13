/**
 * 员工批量导入前端纯函数（/fr/org/staff）：CSV 解析、模板、失败行重提、上限。
 * CSV 解析已提取至 `@/utils/csvImport.ts` 共享模块（批3 重构，禁止复制粘贴）。
 */

import { parseCsvGeneric } from '@/utils/csvImport'
import type { CsvParseResult } from '@/utils/csvImport'

// re-export for backward compatibility
export {
  withinFileSizeLimit,
  createImportGenerationGuard,
  runGuardedImportRequest
} from '@/utils/csvImport'
export type { ImportGenerationGuard } from '@/utils/csvImport'
export { CSV_MAX_FILE_BYTES as STAFF_IMPORT_MAX_FILE_BYTES } from '@/utils/csvImport'

export const STAFF_IMPORT_MAX_ROWS = 500

export const STAFF_IMPORT_HEADER = ['姓名', '手机', '角色码', '团队名'] as const

export interface StaffImportRow {
  line: number
  name: string
  mobile: string
  roleCode: string
  teamName: string
  error?: string
}

export interface StaffImportResultRow {
  line: number
  name: string
  mobile: string
  roleCode: string
  teamName: string
  status: string
  code?: number | null
  message?: string | null
  postId?: number | string | null
  userId?: number | string | null
  note?: string | null
}

export interface StaffImportResult {
  orgId: number | string
  total: number
  created: number
  skipped: number
  failed: number
  rows: StaffImportResultRow[]
}

/** 解析结果（共享解析器的泛型实例）。 */
export type StaffImportParseResult = CsvParseResult<StaffImportRow>

/** 解析 CSV 文本为导入行（不调用后端）。 */
export function parseStaffImportCsv(text: string): StaffImportParseResult {
  const result = parseCsvGeneric<StaffImportRow>(
    text,
    STAFF_IMPORT_HEADER,
    (cells, line) => {
      const [name, mobile, roleCode, teamName] = cells
      return { line, name, mobile, roleCode, teamName }
    },
    (row) => `${row.mobile}|${row.roleCode}|${row.teamName}`
  )
  // 标注批内重复行 error
  const seen = new Map<string, number>()
  for (const row of result.rows) {
    const key = `${row.mobile}|${row.roleCode}|${row.teamName}`
    const firstLine = seen.get(key)
    if (firstLine !== undefined) {
      row.error = `第 ${row.line} 行：与第 ${firstLine} 行重复（手机号+角色码+团队名）`
    } else {
      seen.set(key, row.line)
    }
  }
  return result
}

/** 模板：UTF-8 BOM + 表头（CRLF，Excel 友好）。 */
export function buildStaffImportTemplateCsv(): string {
  return `\uFEFF${STAFF_IMPORT_HEADER.join(',')}\r\n`
}

/** 失败行回填（保留原始数据行号，重提同一编号）。 */
export function failedRowsForRetry(rows: StaffImportResultRow[]): StaffImportRow[] {
  return rows
    .filter((row) => row.status === 'FAILED')
    .map((row) => ({
      line: row.line,
      name: row.name,
      mobile: row.mobile,
      roleCode: row.roleCode,
      teamName: row.teamName
    }))
}

/** 服务端权威上限同口径（前端仅提示）。 */
export function withinImportLimit(rows: unknown[]): boolean {
  return rows.length >= 1 && rows.length <= STAFF_IMPORT_MAX_ROWS
}

// re-exports from csvImport
export { parseCsvGeneric } from '@/utils/csvImport'
export type { CsvParseResult as CsvGenericParseResult } from '@/utils/csvImport'
