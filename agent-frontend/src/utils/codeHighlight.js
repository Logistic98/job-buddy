const LANGUAGE_ALIASES = {
  js: 'javascript',
  node: 'javascript',
  nodejs: 'javascript',
  py: 'python',
}

const PYTHON_PATTERN =
  /(?<string>"""[\s\S]*?"""|'''[\s\S]*?'''|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(?<comment>#[^\n]*)|(?<keyword>\b(?:and|as|assert|async|await|break|class|continue|def|del|elif|else|except|False|finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|or|pass|raise|return|True|try|while|with|yield)\b)|(?<type>\b(?:bool|bytes|dict|float|frozenset|int|list|object|set|str|tuple)\b)|(?<number>\b(?:0[xX][\dA-Fa-f]+|0[bB][01]+|\d+(?:\.\d+)?)\b)/g

const JAVA_PATTERN =
  /(?<string>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(?<comment>\/\*[\s\S]*?\*\/|\/\/[^\n]*)|(?<keyword>\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|record|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|var|void|volatile|while|yield|true|false|null)\b)|(?<type>\b(?:ArrayList|Arrays|Boolean|Character|Collection|Deque|Double|HashMap|HashSet|Integer|LinkedList|List|Long|Map|Object|Optional|Queue|Set|String|StringBuilder)\b)|(?<number>\b(?:0[xX][\dA-Fa-f]+|0[bB][01]+|\d+(?:\.\d+)?(?:[dDfFlL])?)\b)/g

const JAVASCRIPT_PATTERN =
  /(?<string>`(?:\\.|[^`\\])*`|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(?<comment>\/\*[\s\S]*?\*\/|\/\/[^\n]*)|(?<keyword>\b(?:async|await|break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|false|finally|for|from|function|get|if|import|in|instanceof|let|new|null|of|return|set|static|super|switch|this|throw|true|try|typeof|undefined|var|void|while|with|yield)\b)|(?<type>\b(?:Array|BigInt|Boolean|Date|Error|JSON|Map|Math|Number|Object|Promise|RegExp|Set|String|Symbol|WeakMap|WeakSet)\b)|(?<number>\b(?:0[xX][\dA-Fa-f]+|0[bB][01]+|\d+(?:\.\d+)?)n?\b)/g

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

export function normalizeHighlightLanguage(language) {
  const normalized = String(language || '')
    .trim()
    .toLowerCase()
  return LANGUAGE_ALIASES[normalized] || normalized
}

export function detectCodeLanguage(code, fallback = 'python') {
  const source = String(code || '')
  const scores = {
    python: 0,
    java: 0,
    javascript: 0,
  }
  const score = (language, pattern, weight) => {
    if (pattern.test(source)) scores[language] += weight
  }

  score('python', /^\s*(?:async\s+)?def\s+\w+\s*\(/m, 6)
  score('python', /^\s*class\s+\w+[^\n]*:\s*$/m, 4)
  score('python', /^\s*(?:from\s+\S+\s+)?import\s+/m, 3)
  score('python', /\b(?:self|None|True|False|elif)\b/, 2)

  score(
    'java',
    /\b(?:public|private|protected)\s+(?:static\s+)?(?:class|interface|record|void|[A-Z]\w*|int|long|double|boolean)\b/,
    6,
  )
  score('java', /\bSystem\.(?:out|err)\./, 4)
  score('java', /\b(?:new\s+[A-Z]\w*|implements|throws)\b/, 3)
  score('java', /;\s*(?:\n|$)/m, 1)

  score('javascript', /\b(?:const|let|var)\s+[A-Za-z_$][\w$]*/, 5)
  score('javascript', /(?:=>|\bfunction\s+[A-Za-z_$][\w$]*\s*\()/, 6)
  score('javascript', /\b(?:console\.log|require\s*\(|module\.exports|export\s+default)\b/, 4)

  const normalizedFallback = normalizeHighlightLanguage(fallback)
  const supportedFallback = Object.hasOwn(scores, normalizedFallback) ? normalizedFallback : 'python'
  return Object.entries(scores).reduce(
    (selected, [language, languageScore]) =>
      languageScore > selected.score ? { language, score: languageScore } : selected,
    { language: supportedFallback, score: 0 },
  ).language
}

export function highlightCode(code, language) {
  const source = String(code ?? '')
  const normalized = normalizeHighlightLanguage(language)
  const pattern =
    normalized === 'python'
      ? PYTHON_PATTERN
      : normalized === 'java'
        ? JAVA_PATTERN
        : normalized === 'javascript'
          ? JAVASCRIPT_PATTERN
          : null
  if (!pattern) return escapeHtml(source)

  let output = ''
  let cursor = 0
  for (const match of source.matchAll(pattern)) {
    output += escapeHtml(source.slice(cursor, match.index))
    const tokenType = Object.entries(match.groups || {}).find(([, value]) => value !== undefined)?.[0] || 'plain'
    output += `<span class="code-token code-token-${tokenType}">${escapeHtml(match[0])}</span>`
    cursor = match.index + match[0].length
  }
  return output + escapeHtml(source.slice(cursor))
}
