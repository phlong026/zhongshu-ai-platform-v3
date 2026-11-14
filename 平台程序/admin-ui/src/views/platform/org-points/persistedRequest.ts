export const runPersistedRequest = async <T>(
  persist: () => void,
  request: () => Promise<T>,
  clear: () => void
) => {
  persist()
  const result = await request()
  clear()
  return result
}
