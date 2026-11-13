/**
 * 通用 CSV 解析纯函数（批2/批3 共享，无第三方依赖）。
 * 从 importModel.ts 提取，staff 与 lead 两处导入引用，禁止复制粘贴。
 *
 * CSV 规则：
 * - UTF-8 BOM 可选；CRLF / LF / CR 均可；
 * - 支持双引号字段、逗号/换行内嵌、"" 转义；
 * - 表头顺序由调用方通过 `expectedHeader` 参数传入；
 * - 空白行跳过；数据行号从 1 开始（表头不算），预检/提交/重提保持同一编号；
 * - 严格 CSV：闭合引号后只允许 `,` / CRLF / EOF；未闭合引号到 EOF 也判错；
 * - 重复行由调用方通过 `duplicateKey` 回调定义（默认 null = 不查重）。
 */

export interface CsvRow {
  line: number
  cells: string[]
  error?: string
}

export interface CsvParseResult<T> {
  rows: T[]
  errors: string[]
}

export const CSV_MAX_FILE_BYTES = 2 * 1024 * 1024

export function withinFileSizeLimit(bytes: number): boolean {
  return bytes > 0 && bytes <= CSV_MAX_FILE_BYTES
}

export function withinImportLimit(rows: unknown[], max: number): boolean {
  return rows.length >= 1 && rows.length <= max
}

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
  error?: string
}

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

/**
 * 通用 CSV 解析入口：校验表头 + 提取数据行 + 重复键检测。
 * `expectedHeader`：严格表头（顺序不可变）；`columnCount`：每行列数；`duplicateKeyFn`：null 不查重。
 */
export function parseCsvGeneric<T>(
  text: string,
  expectedHeader: readonly string[],
  mapRow: (cells: string[], line: number) => T,
  duplicateKeyFn?: (row: T) => string
): CsvParseResult<T> {
  const normalized = text.replace(/^\uFEFF/, '')
  const parsedRecords = parseRecords(normalized)
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
    header.length !== expectedHeader.length ||
    header.some((cell, index) => cell !== expectedHeader[index])
  ) {
    return { rows: [], errors: [`表头必须为：${expectedHeader.join(',')}（顺序不可变）`] }
  }

  const rows: T[] = []
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
    if (record.cells.length !== expectedHeader.length) {
      errors.push(
        `第 ${dataLine} 行：列数应为 ${expectedHeader.length}，实际 ${record.cells.length}`
      )
      continue
    }
    const row = mapRow(
      record.cells.map((cell) => cell.trim()),
      dataLine
    )
    if (duplicateKeyFn) {
      const key = duplicateKeyFn(row)
      const firstLine = seen.get(key)
      if (firstLine !== undefined) {
        // 重复行保留在 rows 中并标行级 error（页面据此阻断预检并高亮该行），
        // 不放进 errors[]，避免同一问题在"解析错误"与"重复行"两处重复提示。
        ;(row as { error?: string }).error = `与第 ${firstLine} 行重复`
        rows.push(row)
        continue
      }
      seen.set(key, dataLine)
    }
    rows.push(row)
  }
  return { rows, errors }
}
