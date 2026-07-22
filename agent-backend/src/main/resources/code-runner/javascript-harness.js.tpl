function stable(value) {
  return JSON.stringify(value)
}
const fs = require('fs')
const tests = JSON.parse(fs.readFileSync(0, 'utf8') || '[]')
const fn = typeof __FUNCTION_NAME__ === 'function' ? __FUNCTION_NAME__ : globalThis['__FUNCTION_NAME__']
const rows = []
for (const test of tests) {
  const hasExpected = Object.prototype.hasOwnProperty.call(test, 'expected')
  const row = { name: test.name || '用例', input: stable(test.args || []) }
  if (hasExpected) row.expected = stable(test.expected)
  try {
    if (typeof fn !== 'function') throw new Error('未找到函数：__FUNCTION_NAME__')
    const actual = fn.apply(null, JSON.parse(JSON.stringify(test.args || [])))
    row.actual = stable(actual)
    row.passed = !hasExpected || stable(actual) === stable(test.expected)
  } catch (err) {
    row.actual = '运行异常'
    row.passed = false
    row.error = err && err.message ? err.message : String(err)
  }
  rows.push(row)
}
console.log(JSON.stringify({ passed: rows.length > 0 && rows.every((r) => r.passed), rows }))
