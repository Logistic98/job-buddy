class TestResizeObserver {
  observe() {}

  unobserve() {}

  disconnect() {}
}

if (!globalThis.ResizeObserver) globalThis.ResizeObserver = TestResizeObserver

if (!globalThis.SVGElement.prototype.getBBox) {
  globalThis.SVGElement.prototype.getBBox = () => ({ x: 0, y: 0, width: 120, height: 24 })
}

if (!globalThis.SVGElement.prototype.getComputedTextLength) {
  globalThis.SVGElement.prototype.getComputedTextLength = () => 120
}
