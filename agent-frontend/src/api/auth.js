import { apiUrl, parseApiResponse } from './http'

export async function login(username, password) {
  const response = await fetch(apiUrl('/auth/login'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  return parseApiResponse(response, '登录失败')
}

export async function currentUser(token) {
  const response = await fetch(apiUrl('/auth/me'), {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  return parseApiResponse(response, '获取当前用户失败')
}

export async function logout(token) {
  const response = await fetch(apiUrl('/auth/logout'), {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  return parseApiResponse(response, '退出登录失败')
}
