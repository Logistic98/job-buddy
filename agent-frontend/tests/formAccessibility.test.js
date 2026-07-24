import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(relativePath) {
  return readFileSync(resolve(globalThis.process.cwd(), 'src', relativePath), 'utf8')
}

function expectRequiredLabels(content, labels) {
  for (const label of labels) {
    const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    expect(content, `${label} 应显示必填标识`).toMatch(
      new RegExp(`<span[^>]*class=["'][^"']*form-required[^"']*["'][^>]*>\\s*${escaped}`),
    )
  }
}

describe('platform form accessibility contract', () => {
  it.each([
    ['components/LoginPage.vue', ['用户名', '密码']],
    ['components/interview/QuestionEditModal.vue', ['标题', '分类', '难度', '初始代码模板', '数量']],
    ['components/interview/PracticeConfigModal.vue', ['练习名称', '限时时长', '练习模式', '组卷规则']],
    ['components/ProjectDeepDive.vue', ['项目名称', '生成数量', '问题', '难度']],
    ['components/ResumeManager.vue', ['分组名称']],
    ['components/ResumeWriter.vue', ['文件名', '版本说明']],
    ['components/UserManagement.vue', ['新密码', '全局唯一用户名', '显示名称', '初始密码', '账号状态']],
    ['components/RoleManagement.vue', ['角色编码', '角色名称', '角色状态']],
    ['components/MenuManagement.vue', ['菜单编码', '菜单名称', '菜单类型', '排序值', '前台显示', '菜单状态']],
    ['components/settings/MemorySettingsPanel.vue', ['最大记忆条数', '记忆类型', '记忆内容']],
  ])('%s marks every registered required field', (path, labels) => {
    expectRequiredLabels(source(path), labels)
  })

  it.each([
    'components/LoginPage.vue',
    'components/interview/QuestionEditModal.vue',
    'components/interview/PracticeConfigModal.vue',
    'components/ProjectDeepDive.vue',
    'components/ResumeManager.vue',
    'components/ResumeWriter.vue',
    'components/UserManagement.vue',
    'components/RoleManagement.vue',
    'components/MenuManagement.vue',
    'components/JobJourney.vue',
    'components/settings/MemorySettingsPanel.vue',
    'components/settings/RuntimeSettingsPanel.vue',
    'components/settings/ServiceMonitorPanel.vue',
    'components/settings/CompanyBlacklistPanel.vue',
  ])('%s renders validation errors with the shared red alert', (path) => {
    const content = source(path)
    expect(content).toContain('form-error-alert')
    expect(content).toMatch(/role=["']alert["']/)
  })

  it('marks conditional and dynamic required fields', () => {
    const menu = source('components/MenuManagement.vue')
    expect(menu).toContain("'form-required': form.menuType === 'page'")
    expect(menu).toContain("'form-required': form.menuType === 'external'")
    expect(source('components/settings/CompanyBlacklistPanel.vue')).toContain('blacklist-input-label form-required')
  })

  it('marks all runtime and service settings as required groups', () => {
    const runtime = source('components/settings/RuntimeSettingsPanel.vue')
    const services = source('components/settings/ServiceMonitorPanel.vue')
    expect(runtime.match(/all-fields-required/g)).toHaveLength(4)
    expect(runtime.match(/aria-required="true"/g)).toHaveLength(12)
    expect(services).toContain('form-grid all-fields-required')
    expect(services.match(/aria-required="true"/g)).toHaveLength(8)
  })

  it('defines shared red required and error styles', () => {
    const css = source('styles/modules/shell-and-workbench.css')
    expect(css).toContain('.form-required::after')
    expect(css).toContain("content: '*'")
    expect(css).toContain('.form-error-alert')
    expect(css).toContain('color: #b42318 !important')
  })
})
