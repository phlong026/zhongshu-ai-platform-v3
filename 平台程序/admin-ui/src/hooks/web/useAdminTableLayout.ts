import { onBeforeUnmount, onMounted } from 'vue'

/** Only changes presentation of the existing Element Plus table DOM. Slots, row
 * selection, permissions, sorting and event handlers stay owned by ElTable. */
export function useAdminTableLayout() {
  let mutation: MutationObserver | undefined
  let resize: ResizeObserver | undefined
  let frame = 0
  const tables = new Set<HTMLElement>()
  const selector = ':is(.admin-content, .el-dialog, .el-drawer) .el-table:not(.directory-table):not(.training-compact-table)'
  const schedule = () => {
    if (!frame) frame = requestAnimationFrame(refresh)
  }
  const refresh = () => {
    frame = 0
    for (const table of tables) {
      if (!table.isConnected) {
        resize?.unobserve(table)
        tables.delete(table)
      }
    }
    document.querySelectorAll<HTMLElement>(selector).forEach((table) => {
      if (!tables.has(table)) {
        tables.add(table)
        resize?.observe(table)
      }
      // Hidden keep-alive views must not determine their eventual layout.
      const width = table.getBoundingClientRect().width
      if (!width) return
      const headers = [
        ...table.querySelectorAll<HTMLElement>('.el-table__header-wrapper thead tr:last-child > th')
      ]
      if (!headers.length) return
      const labels = headers.map(
        (h, index) =>
          h.textContent?.trim() || (h.querySelector('.el-checkbox') ? '选择' : `字段 ${index + 1}`)
      )
      const records = width < Math.max(620, headers.length * 92)
      table.classList.add('admin-fit-table')
      table.classList.toggle('admin-table-records', records)
      table.querySelectorAll('colgroup').forEach((group) => {
        ;[...group.children].forEach((col, index) => {
          col.toggleAttribute(
            'data-admin-selection',
            !!headers[index]?.querySelector('.el-checkbox')
          )
        })
      })
      headers.forEach((header) => {
        const controls = !!header.querySelector(
          '.caret-wrapper, .el-checkbox, .el-table__column-filter-trigger, button, input'
        )
        header.toggleAttribute('data-admin-control', controls)
      })
      table.querySelectorAll<HTMLElement>('.el-table__body > tbody > tr').forEach((row) => {
        let index = 0
        for (const cell of [...row.children] as HTMLTableCellElement[]) {
          const label = cell.colSpan > 1 ? '' : labels[index] || ''
          if (cell.dataset.adminLabel !== label) cell.dataset.adminLabel = label
          cell.classList.toggle('admin-spanning-cell', cell.colSpan > 1)
          index += cell.colSpan || 1
        }
      })
    })
  }
  onMounted(() => {
    resize = new ResizeObserver(schedule)
    mutation = new MutationObserver(schedule)
    mutation.observe(document.body, { childList: true, subtree: true, characterData: true })
    schedule()
  })
  onBeforeUnmount(() => {
    mutation?.disconnect()
    resize?.disconnect()
    cancelAnimationFrame(frame)
    tables.clear()
  })
}
