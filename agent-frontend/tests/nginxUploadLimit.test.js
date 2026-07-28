import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'
import { describe, expect, it } from 'vitest'

const frontendRoot = cwd()
const repositoryRoot = resolve(frontendRoot, '..')
const nginxTemplate = readFileSync(resolve(frontendRoot, 'default.conf.template'), 'utf8')
const dockerfile = readFileSync(resolve(frontendRoot, 'Dockerfile'), 'utf8')
const composeFile = readFileSync(resolve(repositoryRoot, 'docker-compose.yml'), 'utf8')
const environmentExample = readFileSync(resolve(repositoryRoot, '.env.example'), 'utf8')

describe('frontend Nginx upload limit', () => {
  it('keeps the proxy request limit configurable with a deployment-safe default', () => {
    expect(nginxTemplate).toContain('client_max_body_size ${NGINX_CLIENT_MAX_BODY_SIZE};')
    expect(dockerfile).toContain('ENV NGINX_CLIENT_MAX_BODY_SIZE=1025m')
    expect(composeFile).toContain('NGINX_CLIENT_MAX_BODY_SIZE: ${NGINX_CLIENT_MAX_BODY_SIZE:-1025m}')
    expect(environmentExample).toContain('NGINX_CLIENT_MAX_BODY_SIZE=1025m')
  })
})
