<template>
  <div class="pdf-preview" aria-label="文档预览">
    <div class="pdf-tools"><el-button :disabled="page <= 1 || busy" @click="page--">上一页</el-button><span>{{ page }} / {{ pages || '…' }} 页</span><el-button :disabled="page >= pages || busy" @click="page++">下一页</el-button><el-button :disabled="zoom <= .6" @click="zoom = Math.max(.6, zoom - .2)">缩小</el-button><el-button :disabled="zoom >= 1.6" @click="zoom = Math.min(1.6, zoom + .2)">放大</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div ref="stage" v-loading="busy" class="pdf-stage"><canvas ref="canvas" aria-label="文档页面" /></div>
  </div>
</template>
<script setup lang="ts">
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type PDFDocumentLoadingTask, type RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
GlobalWorkerOptions.workerSrc = workerUrl
const props = defineProps<{ src: string }>()
const canvas = ref<HTMLCanvasElement>(), stage = ref<HTMLDivElement>()
const page = ref(1), pages = ref(0), zoom = ref(1), busy = ref(false), error = ref('')
let pdf: PDFDocumentProxy | undefined, task: PDFDocumentLoadingTask | undefined, renderTask: RenderTask | undefined
let generation = 0, renderGeneration = 0, observer: ResizeObserver | undefined, width = 0, disposed = false
async function render() {
  const document = pdf, gen = ++renderGeneration
  if (!document || !canvas.value || !stage.value) return
  busy.value = true
  const previous = renderTask; previous?.cancel()
  try {
    await previous?.promise.catch(() => {})
    const pdfPage = await document.getPage(page.value)
    if (disposed || gen !== renderGeneration) return
    const initial = pdfPage.getViewport({ scale: 1 })
    const viewport = pdfPage.getViewport({ scale: Math.max(100, Math.min(1000, stage.value.clientWidth - 28)) / initial.width * zoom.value })
    const ratio = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(16000000 / (viewport.width * viewport.height)))
    const target = canvas.value
    target.width = Math.ceil(viewport.width * ratio); target.height = Math.ceil(viewport.height * ratio)
    target.style.width = `${viewport.width}px`; target.style.height = `${viewport.height}px`
    renderTask = pdfPage.render({ canvas: target, viewport, transform: [ratio, 0, 0, ratio, 0, 0] })
    await renderTask.promise
  } catch (e: any) { if (gen === renderGeneration && !disposed && e?.name !== 'RenderingCancelledException') error.value = '文档预览失败，请关闭后重新打开。' }
  finally { if (gen === renderGeneration && !disposed) busy.value = false }
}
async function load() {
  const gen = ++generation; ++renderGeneration; renderTask?.cancel(); void task?.destroy(); pdf = undefined
  error.value = ''; pages.value = 0; busy.value = true
  const resources = `${import.meta.env.BASE_URL}training-pdf/`
  const next = task = getDocument({ url: props.src, cMapUrl: `${resources}cmaps/`, cMapPacked: true, standardFontDataUrl: `${resources}standard_fonts/`, wasmUrl: `${resources}wasm/`, iccUrl: `${resources}iccs/` })
  try { const document = await next.promise; if (disposed || gen !== generation) { void next.destroy(); return } pdf = document; pages.value = document.numPages; page.value = 1; await nextTick(); await render() }
  catch { if (!disposed && gen === generation) { error.value = '文档未能加载，链接可能已过期，请关闭后重新预览。'; busy.value = false } }
}
watch(() => props.src, load)
watch([page, zoom], render)
onMounted(() => { observer = new ResizeObserver(() => { const next = stage.value?.clientWidth || 0; if (Math.abs(next - width) > 1) { width = next; void render() } }); if (stage.value) observer.observe(stage.value); void load() })
onBeforeUnmount(() => { disposed = true; generation++; renderGeneration++; observer?.disconnect(); renderTask?.cancel(); void task?.destroy() })
</script>
<style scoped>
.pdf-tools{display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:10px;padding:10px;background:#faf8f4}.pdf-tools .el-button{margin:0}.pdf-tools span{font-size:13px;color:#6f665a}.pdf-stage{height:60vh;overflow:auto;background:#e8e4dd;padding:14px;text-align:center;min-height:200px}.pdf-stage canvas{display:block;background:white;margin:auto;box-shadow:0 2px 12px #30291f20}.pdf-preview{min-width:0}
</style>
