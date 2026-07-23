import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  importBossFavoriteJobs: vi.fn(),
  listBossFavoriteJobs: vi.fn(),
}))

vi.mock('../src/api/jobs', async (importOriginal) => ({
  ...(await importOriginal()),
  importBossFavoriteJobs: mocks.importBossFavoriteJobs,
  listBossFavoriteJobs: mocks.listBossFavoriteJobs,
}))

import BossFavoriteImportModal from '../src/components/BossFavoriteImportModal.vue'
import { useJobStore } from '../src/stores/job'

function favorite(index, alreadyImported = false) {
  return {
    favoriteKey: `boss-${index}`,
    securityId: `boss-${index}`,
    jobName: `Java 大模型应用开发岗 ${index}`,
    brandName: `上海示例科技 ${index}`,
    cityName: '上海',
    salaryDesc: '40-50K',
    alreadyImported,
  }
}

function mountModal() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return {
    job: useJobStore(),
    wrapper: mount(BossFavoriteImportModal, {
      props: { visible: true },
      global: {
        plugins: [pinia],
        stubs: { BossLoginQrModal: true },
      },
    }),
  }
}

describe('BossFavoriteImportModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listBossFavoriteJobs.mockResolvedValue({
      jobs: Array.from({ length: 7 }, (_, index) => favorite(index + 1, index === 0)),
      page: 1,
      hasMore: true,
      totalCount: 12,
      totalPages: 3,
    })
    mocks.importBossFavoriteJobs.mockResolvedValue({
      importedCount: 6,
      existingCount: 0,
      failedCount: 0,
      unprocessedCount: 0,
      stopped: false,
      items: Array.from({ length: 6 }, (_, index) => ({ jobKey: `boss-${index + 2}`, status: 'imported' })),
      favorites: [favorite(2, true)],
    })
  })

  it('loads one page only after opening and allows all selectable jobs', async () => {
    const { wrapper } = mountModal()
    await flushPromises()

    expect(mocks.listBossFavoriteJobs).toHaveBeenCalledTimes(1)
    expect(mocks.listBossFavoriteJobs).toHaveBeenCalledWith(1, false)
    expect(wrapper.text()).toContain('导入仅保存岗位摘要，不会自动分析')
    const selectable = wrapper.findAll('input[type="checkbox"]:not(:disabled)')
    expect(selectable).toHaveLength(6)

    for (const checkbox of selectable) await checkbox.trigger('change')

    expect(wrapper.text()).toContain('已选择 6 个')
    expect(wrapper.text()).not.toContain('最多选择')
    const importButton = wrapper
      .findAll('.boss-favorite-import-actions button')
      .find((button) => button.text().includes('导入所选'))
    await importButton.trigger('click')
    await flushPromises()

    expect(mocks.importBossFavoriteJobs).toHaveBeenCalledOnce()
    expect(mocks.importBossFavoriteJobs.mock.calls[0][0]).toHaveLength(6)
  })

  it('uses page replacement instead of appending an endless list', async () => {
    mocks.listBossFavoriteJobs
      .mockReset()
      .mockResolvedValueOnce({ jobs: [favorite(2)], page: 1, hasMore: true, totalPages: 2 })
      .mockResolvedValueOnce({ jobs: [favorite(8)], page: 2, hasMore: false, totalPages: 2 })
    const { wrapper } = mountModal()
    await flushPromises()

    expect(wrapper.text()).toContain('Java 大模型应用开发岗 2')
    const nextButton = wrapper.findAll('.boss-favorite-pagination button').find((button) => button.text() === '下一页')
    await nextButton.trigger('click')
    await flushPromises()

    expect(mocks.listBossFavoriteJobs).toHaveBeenNthCalledWith(2, 2, false)
    expect(wrapper.text()).not.toContain('Java 大模型应用开发岗 2')
    expect(wrapper.text()).toContain('Java 大模型应用开发岗 8')
    expect(wrapper.text()).toContain('第 2 / 2 页')
    expect(nextButton.attributes('disabled')).toBeDefined()
  })

  it('updates local favorites after a partial-safe import response', async () => {
    const { job, wrapper } = mountModal()
    await flushPromises()
    await wrapper.findAll('input[type="checkbox"]:not(:disabled)')[0].trigger('change')
    mocks.importBossFavoriteJobs.mockResolvedValueOnce({
      importedCount: 1,
      existingCount: 0,
      failedCount: 1,
      unprocessedCount: 1,
      stopped: true,
      stoppedReason: 'Boss 请求过于频繁，已进入冷却',
      items: [
        { jobKey: 'boss-2', status: 'imported' },
        { jobKey: 'boss-3', status: 'failed' },
        { jobKey: 'boss-4', status: 'not_processed' },
      ],
      favorites: [favorite(2, true)],
    })

    await wrapper.vm.confirmImport()
    await flushPromises()

    expect(job.favorites).toHaveLength(1)
    expect(wrapper.text()).toContain('成功导入 1 个')
    expect(wrapper.text()).toContain('1 个为保护账号未处理')
    expect(wrapper.text()).toContain('已进入冷却')
  })

  it('shows login in the same modal and loads one page after QR login', async () => {
    const authError = Object.assign(new Error('Boss 直聘未登录'), {
      authRequired: true,
      authData: { authRequired: true, status: 'auth_required' },
    })
    mocks.listBossFavoriteJobs
      .mockReset()
      .mockRejectedValueOnce(authError)
      .mockResolvedValueOnce({
        jobs: Array.from({ length: 7 }, (_, index) => favorite(index + 1, index === 0)),
        page: 1,
        hasMore: true,
        totalPages: 3,
      })
    const { wrapper } = mountModal()
    await flushPromises()

    expect(mocks.listBossFavoriteJobs).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.boss-favorite-import-modal').exists()).toBe(true)
    expect(wrapper.find('.boss-login-modal-mask').exists()).toBe(false)

    wrapper.vm.handleLoggedIn()
    await flushPromises()

    expect(mocks.listBossFavoriteJobs).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('Java 大模型应用开发岗 1')
    expect(wrapper.text()).toContain('Java 大模型应用开发岗 2')
  })
})
