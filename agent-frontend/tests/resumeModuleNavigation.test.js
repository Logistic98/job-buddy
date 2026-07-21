import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ResumeModuleView from '../src/views/ResumeModuleView.vue'

const EmptyPage = { template: '<div />' }

describe('ResumeModuleView navigation', () => {
  it('navigates to the resume writer when the tab is clicked', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/resumes',
          component: ResumeModuleView,
          children: [
            { path: '', name: 'resume-manager', component: EmptyPage },
            { path: 'writer', name: 'resume-writer', component: EmptyPage },
            { path: 'analysis', name: 'resumes', component: EmptyPage },
          ],
        },
      ],
    })
    await router.push('/resumes')
    await router.isReady()

    const wrapper = mount(RouterView, { global: { plugins: [router] } })
    const writerTab = wrapper.findAll('.resume-module-tab').find((tab) => tab.text() === '简历撰写')

    expect(writerTab.exists()).toBe(true)
    await writerTab.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/resumes/writer')
    expect(writerTab.classes()).toContain('active')

    wrapper.unmount()
  })
})
