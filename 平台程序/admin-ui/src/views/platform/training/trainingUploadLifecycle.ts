export type TrainingUploadSnapshot = {
  version_id?: string
  processing_state?: string
  state?: string
  error?: string
}

export type TrainingUploadFailureKind = 'processing' | 'status' | 'timeout'

export class TrainingUploadFailure extends Error {
  constructor(
    readonly kind: TrainingUploadFailureKind,
    message: string
  ) {
    super(message)
    this.name = 'TrainingUploadFailure'
  }
}

type WaitForTrainingUploadOptions = {
  uploadId: string
  getStatus: () => Promise<TrainingUploadSnapshot>
  isAlive?: () => boolean
  onStatus?: (state: string, elapsedMs: number) => void
  sleep?: (ms: number) => Promise<void>
  initialDelayMs?: number
  intervalMs?: number
  maxConsecutiveErrors?: number
  timeoutMs?: number
  now?: () => number
}

const defaultSleep = (ms: number) =>
  new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })

/** Wait until the backend has finished converting the uploaded material. */
export async function waitForTrainingUpload({
  uploadId,
  getStatus,
  isAlive = () => true,
  onStatus = () => {},
  sleep = defaultSleep,
  initialDelayMs = 800,
  intervalMs = 2500,
  maxConsecutiveErrors = 4,
  timeoutMs = 31 * 60 * 1000,
  now = Date.now
}: WaitForTrainingUploadOptions): Promise<{ versionId: string; elapsedMs: number }> {
  const startedAt = now()
  let delay = initialDelayMs
  let consecutiveErrors = 0

  while (isAlive()) {
    await sleep(delay)
    if (!isAlive()) break

    const elapsedMs = now() - startedAt
    if (elapsedMs > timeoutMs) {
      throw new TrainingUploadFailure('timeout', '处理等待超时，资料可能仍在后台处理')
    }

    try {
      const upload = await getStatus()
      consecutiveErrors = 0
      const state = String(upload.processing_state || upload.state || 'UPLOADED')
      onStatus(state, elapsedMs)
      if (state === 'READY') {
        if (!upload.version_id) {
          throw new TrainingUploadFailure('status', '处理已完成，但没有返回资料版本')
        }
        return { versionId: upload.version_id, elapsedMs }
      }
      if (state === 'FAILED') {
        throw new TrainingUploadFailure(
          'processing',
          upload.error?.trim() || '文件处理失败，请核查格式后在资料库重试'
        )
      }
    } catch (error) {
      if (error instanceof TrainingUploadFailure) throw error
      consecutiveErrors++
      if (consecutiveErrors >= maxConsecutiveErrors) {
        throw new TrainingUploadFailure('status', '无法读取处理状态，请检查网络后重试')
      }
    }
    delay = intervalMs
  }

  throw new TrainingUploadFailure('status', `上传任务 ${uploadId} 已停止等待`)
}
