// 将图片 URL 转为 data URL，供导出简历内嵌照片；fetchImpl 可注入以便独立测试。

export async function imageUrlToDataUrl(url, fetchImpl) {
  if (!url || url.startsWith('data:')) return url || ''
  const doFetch = fetchImpl || (typeof fetch !== 'undefined' ? fetch : null)
  if (!doFetch) return url
  try {
    const res = await doFetch(url)
    if (!res.ok) return url
    const blob = await res.blob()
    return await new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result)
      reader.onerror = reject
      reader.readAsDataURL(blob)
    })
  } catch {
    return url
  }
}
