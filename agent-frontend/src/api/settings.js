import { request } from './http'

export function placeholder() {
  return request('/health')
}
