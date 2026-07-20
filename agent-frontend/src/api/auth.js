import { apiUrl, parseApiResponse } from './http'

export async function login(username, password) {
  const response = await fetch(apiUrl('/auth/login'), {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  return parseApiResponse(response, '登录失败')
}

export async function currentUser() {
  const response = await fetch(apiUrl('/auth/me'), {
    credentials: 'include',
  })
  return parseApiResponse(response, '获取当前用户失败')
}

export async function logout() {
  const response = await fetch(apiUrl('/auth/logout'), {
    method: 'POST',
    credentials: 'include',
  })
  return parseApiResponse(response, '退出登录失败')
}
