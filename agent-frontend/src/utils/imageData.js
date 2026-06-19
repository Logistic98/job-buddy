// Converts an image URL to a data: URL so exported resumes embed the photo inline.
// Kept out of components so the network/FileReader logic stays testable and there are no
// stray fetch() calls scattered through .vue files. fetchImpl is injectable for tests.

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
