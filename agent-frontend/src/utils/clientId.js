/**
 * 生成仅用于前端状态关联的 UUID。
 *
 * randomUUID 只在安全上下文中可用，而 getRandomValues 允许普通 HTTP 页面使用。
 * 最后的 Math.random 分支用于兼容缺少 Web Crypto 的旧浏览器；这些标识不承担鉴权或加密职责。
 *
 * @returns {string} UUID v4
 */
export function createUuid() {
  const bytes = new Uint8Array(16)
  const webCrypto = globalThis.crypto
  if (typeof webCrypto?.randomUUID === 'function') return webCrypto.randomUUID()
  if (typeof webCrypto?.getRandomValues === 'function') {
    webCrypto.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }

  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex
    .slice(8, 10)
    .join('')}-${hex.slice(10).join('')}`
}
