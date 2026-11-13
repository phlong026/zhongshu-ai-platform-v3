/**
 * 员工批量导入前端纯函数（/fr/org/staff）：CSV 解析、模板、失败行重提、上限。
 *
 * CSV 规则（手写，无第三方依赖）：
 * - UTF-8 BOM 可选；CRLF / LF / CR 均可；
 * - 支持双引号字段、逗号/换行内嵌、"" 转义；
 * - 表头必须严格为：姓名,手机,角色码,团队名（顺序不可变）；
 * - 空白行跳过；数据行号从 1 开始（表头不算），预检/提交/重提保持同一编号；
 * - 重复行（手机号+角色码+团队名）在解析阶段标记 error，由页面提示。
 *
 * 业务校验（手机号格式/角色是否可指派/团队是否存在）由服务端权威判定，前端不重复实现。
 */

export const STAFF_IMPORT_MAX_ROWS = 500

/** 上传文件大小上限：2MB（500 行 CSV 远小于该值，防误传大文件）。 */
export const STAFF_IMPORT_MAX_FILE_BYTES = 2 * 1024 * 1024

export const STAFF_IMPORT_HEADER = ['姓名', '手机', '角色码', '团队名'] as const

export interface StaffImportRow {
  line: number
  name: string
  mobile: string
  roleCode: string
  teamName: string
  error?: string
}

export interface StaffImportParseResult {
  rows: StaffImportRow[]
  errors: string[]
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

/** 解析 CSV 文本为导入行（不调用后端）。 */
export function parseStaffImportCsv(text: string): StaffImportParseResult {
  const normalized = text.replace(/^\uFEFF/, '')
  const parsedRecords = parseRecords(normalized)
  // 错误记录即使 cells 为空也不能当空行丢弃（否则错误被吞、后续行号前移）。
  const headerIndex = parsedRecords.findIndex(
    (record) => record.error !== undefined || !isEmptyRecord(record.cells)
  )
  if (headerIndex < 0) {
    return { rows: [], errors: ['文件为空或缺少表头'] }
  }
  const headerRecord = parsedRecords[headerIndex]
  if (headerRecord.error) {
    return { rows: [], errors: [`表头解析错误：${headerRecord.error}`] }
  }
  const header = headerRecord.cells.map((cell) => cell.trim())
  if (
    header.length !== STAFF_IMPORT_HEADER.length ||
    header.some((cell, index) => cell !== STAFF_IMPORT_HEADER[index])
  ) {
    return { rows: [], errors: ['表头必须为：姓名,手机,角色码,团队名（顺序不可变）'] }
  }

  const rows: StaffImportRow[] = []
  const errors: string[] = []
  const seen = new Map<string, number>()
  let dataLine = 0
  for (const record of parsedRecords.slice(headerIndex + 1)) {
    if (record.error === undefined && isEmptyRecord(record.cells)) {
      continue
    }
    dataLine += 1
    if (record.error) {
      errors.push(`第 ${dataLine} 行：${record.error}`)
      continue
    }
    if (record.cells.length !== STAFF_IMPORT_HEADER.length) {
      errors.push(`第 ${dataLine} 行：列数应为 4，实际 ${record.cells.length}`)
      continue
    }
    const [name, mobile, roleCode, teamName] = record.cells.map((cell) => cell.trim())
    const row: StaffImportRow = { line: dataLine, name, mobile, roleCode, teamName }
    const key = `${mobile}|${roleCode}|${teamName}`
    const firstLine = seen.get(key)
    if (firstLine !== undefined) {
      row.error = `第 ${dataLine} 行：与第 ${firstLine} 行重复（手机号+角色码+团队名）`
    } else {
      seen.set(key, dataLine)
    }
    rows.push(row)
  }
  return { rows, errors }
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

/** 上传文件大小校验（非空且不超过 2MB 上限）。 */
export function withinFileSizeLimit(bytes: number): boolean {
  return bytes > 0 && bytes <= STAFF_IMPORT_MAX_FILE_BYTES
}

/** 导入对话框请求代次守卫：关闭/重开/重新上传/新请求都会 next()，旧响应 isCurrent=false。 */
export interface ImportGenerationGuard {
  next(): number
  isCurrent(generation: number): boolean
}

export function createImportGenerationGuard(): ImportGenerationGuard {
  let current = 0
  return {
    next: () => {
      current += 1
      return current
    },
    isCurrent: (generation) => generation === current
  }
}

/**
 * 执行请求并在响应返回后校验代次：仅当前代次同步调用 apply（迟到响应返回 null）。
 * isCurrent 判定与 apply 之间没有任何 await/微任务，结构上消除写回窗口。
 */
export async function runGuardedImportRequest<T>(
  request: () => Promise<T>,
  generation: number,
  isCurrent: (generation: number) => boolean,
  apply: (result: T) => void
): Promise<T | null> {
  const result = await request()
  if (!isCurrent(generation)) return null
  apply(result)
  return result
}

function isEmptyRecord(record: string[]): boolean {
  return record.every((cell) => cell.trim() === '')
}

interface RawRecord {
  cells: string[]
  /** 严格 CSV 语义错误：引号未闭合 / 闭合后非法字符 / 字段中引号位置非法。 */
  error?: string
}

/**
 * 严格 CSV 解析：闭合引号后只允许 `,` / CRLF / EOF；不允许尾随空格等字符。
 * 未闭合引号到 EOF 也判错。错误记录仍保留 cells 以维持行号推进。
 */
function parseRecords(text: string): RawRecord[] {
  const records: RawRecord[] = []
  let record: string[] = []
  let field = ''
  let inQuotes = false
  let justClosedQuote = false
  let fieldIndex = 0
  let recordError: string | undefined
  let i = 0

  const markError = (message: string) => {
    if (!recordError) recordError = message
  }
  const pushField = () => {
    record.push(field)
    field = ''
    justClosedQuote = false
    fieldIndex += 1
  }
  const pushRecord = () => {
    pushField()
    records.push({ cells: record, error: recordError })
    record = []
    recordError = undefined
    fieldIndex = 0
  }

  while (i < text.length) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          field += '"'
          i += 2
          continue
        }
        inQuotes = false
        justClosedQuote = true
        i += 1
        continue
      }
      field += ch
      i += 1
      continue
    }
    if (justClosedQuote) {
      if (ch === ',') {
        pushField()
        i += 1
        continue
      }
      if (ch === '\r') {
        if (text[i + 1] === '\n') i += 1
        pushRecord()
        i += 1
        continue
      }
      if (ch === '\n') {
        pushRecord()
        i += 1
        continue
      }
      markError(`第 ${fieldIndex + 1} 列引号闭合后存在非法字符`)
      i += 1
      continue
    }
    if (ch === '"') {
      if (field.length === 0) {
        inQuotes = true
        i += 1
        continue
      }
      markError(`第 ${fieldIndex + 1} 列引号必须位于字段开头`)
      i += 1
      continue
    }
    if (ch === ',') {
      pushField()
      i += 1
      continue
    }
    if (ch === '\r') {
      if (text[i + 1] === '\n') i += 1
      pushRecord()
      i += 1
      continue
    }
    if (ch === '\n') {
      pushRecord()
      i += 1
      continue
    }
    field += ch
    i += 1
  }
  if (inQuotes) {
    markError(`第 ${fieldIndex + 1} 列引号未闭合`)
  }
  if (field.length > 0 || record.length > 0 || justClosedQuote || recordError) {
    pushRecord()
  }
  return records
}
