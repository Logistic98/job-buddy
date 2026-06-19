package com.jobbuddy.backend.modules.interview.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编程题运行器。
 *
 * 不在 agent-backend 进程内执行用户代码。后端只负责组装判题 Harness，并统一提交给
 * agent-sandbox，由 agent-sandbox 基于 sandbox-runtime/srt 完成隔离执行。
 */
@Component
public class InterviewCodeRunner {
    private static final int MAX_TESTS = 20;
    private static final int CHILD_TIMEOUT_SECONDS = 5;
    private static final int SANDBOX_TIMEOUT_SECONDS = 12;
    private static final String SERVICE_KEY = "agent-sandbox";

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final AgentServiceProperties properties;
    private final ServiceResilience resilience;

    public InterviewCodeRunner(ObjectMapper objectMapper, RestTemplate restTemplate, AgentServiceProperties properties, ServiceResilience resilience) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.resilience = resilience;
    }

    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            Map<String, Object> safePayload = payload == null ? Collections.<String, Object>emptyMap() : payload;
            String language = normalizeLanguage(stringValue(safePayload.get("language")));
            String source = stringValue(safePayload.get("source"));
            String functionName = normalizeFunctionName(stringValue(safePayload.get("functionName")));
            List<Map<String, Object>> tests = normalizeTests(safePayload.get("tests"));
            if (source == null || source.trim().isEmpty()) return failure("代码不能为空");
            if (tests.isEmpty()) return failure("测试用例不能为空");
            return runInSandbox(language, source, functionName, tests);
        } catch (Exception e) {
            return failure(e.getMessage() == null ? "代码运行失败" : e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runInSandbox(String language, String source, String functionName, List<Map<String, Object>> tests) throws Exception {
        String code = buildSandboxOrchestrator(language, source, functionName, tests);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("suffix", ".py");
        body.put("interpreter", "python3");
        body.put("policy", sandboxPolicy());
        body.put("options", sandboxOptions());

        // 判题为非幂等的代码执行，不做重试；通过熔断器避免沙箱不可达时持续阻塞在读超时上，
        // 同时保留 HTTP 错误体与不可达两类诊断文案，便于前端区分编排失败与服务未启动。
        if (resilience.isOpen(SERVICE_KEY)) {
            return failure("agent-sandbox 暂时不可用（熔断中），请稍后重试");
        }
        Map<String, Object> response;
        try {
            response = restTemplate.postForObject(sandboxBaseUrl() + "/v1/code-file", body, Map.class);
            resilience.recordSuccess(SERVICE_KEY);
        } catch (RestClientResponseException e) {
            resilience.recordFailure(SERVICE_KEY);
            return failure("agent-sandbox 调用失败：" + compact(e.getResponseBodyAsString(), "HTTP " + e.getRawStatusCode()));
        } catch (RestClientException e) {
            resilience.recordFailure(SERVICE_KEY);
            return failure("agent-sandbox 不可用，请确认服务已启动并配置 agent.services.sandbox-url：" + e.getMessage());
        }
        return parseSandboxResponse(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSandboxResponse(Map<String, Object> response) throws Exception {
        if (response == null) return failure("agent-sandbox 返回空响应");
        int returncode = intValue(response.get("returncode"), -1);
        String stdout = stringValue(response.get("stdout"));
        String stderr = stringValue(response.get("stderr"));
        if (returncode != 0) {
            return failure(firstLine((stderr == null ? "" : stderr) + "\n" + (stdout == null ? "" : stdout), "沙箱执行失败"));
        }
        String json = lastNonEmptyLine(stdout);
        if (json == null || json.trim().isEmpty()) return failure("沙箱运行无输出");
        Map<String, Object> result = objectMapper.readValue(json, Map.class);
        if (!result.containsKey("passed")) result.put("passed", Boolean.FALSE);
        if (!result.containsKey("rows")) result.put("rows", Collections.emptyList());
        return result;
    }

    private String buildSandboxOrchestrator(String language, String source, String functionName, List<Map<String, Object>> tests) throws Exception {
        String childCode = source;
        String runnerCode = "";
        if ("python".equals(language)) {
            childCode = source + "\n\n" + pythonHarness(functionName);
        } else if ("javascript".equals(language)) {
            childCode = source + "\n\n" + javascriptHarness(functionName);
        } else if ("java".equals(language)) {
            runnerCode = javaRunner(functionName);
        }

        StringBuilder code = new StringBuilder();
        code.append("import base64, json, os, subprocess, sys, tempfile\n");
        code.append("LANGUAGE = ").append(pyString(language)).append("\n");
        code.append("CODE_B64 = ").append(pyString(base64(childCode))).append("\n");
        code.append("RUNNER_B64 = ").append(pyString(base64(runnerCode))).append("\n");
        code.append("TESTS_B64 = ").append(pyString(base64(objectMapper.writeValueAsString(tests)))).append("\n");
        code.append("TIMEOUT_SECONDS = ").append(CHILD_TIMEOUT_SECONDS).append("\n");
        code.append("def decode(value):\n");
        code.append("    return base64.b64decode(value.encode('ascii')).decode('utf-8')\n");
        code.append("def emit(payload):\n");
        code.append("    print(json.dumps(payload, ensure_ascii=False))\n");
        code.append("    sys.exit(0)\n");
        code.append("def first_line(value, fallback):\n");
        code.append("    text = (value or '').strip()\n");
        code.append("    return (text.splitlines()[0] if text else fallback)\n");
        code.append("def write(path, content):\n");
        code.append("    with open(path, 'w', encoding='utf-8') as f:\n");
        code.append("        f.write(content)\n");
        code.append("def parse_child(proc):\n");
        code.append("    if proc.returncode != 0:\n");
        code.append("        emit({'passed': False, 'rows': [], 'message': first_line((proc.stderr or '') + '\\n' + (proc.stdout or ''), '运行失败')})\n");
        code.append("    lines = [line.strip() for line in (proc.stdout or '').splitlines() if line.strip()]\n");
        code.append("    if not lines:\n");
        code.append("        emit({'passed': False, 'rows': [], 'message': '运行无输出'})\n");
        code.append("    try:\n");
        code.append("        result = json.loads(lines[-1])\n");
        code.append("        if 'passed' not in result:\n");
        code.append("            result['passed'] = False\n");
        code.append("        if 'rows' not in result:\n");
        code.append("            result['rows'] = []\n");
        code.append("        emit(result)\n");
        code.append("    except Exception as exc:\n");
        code.append("        emit({'passed': False, 'rows': [], 'message': '运行结果解析失败：' + str(exc)})\n");
        code.append("CODE = decode(CODE_B64)\n");
        code.append("RUNNER = decode(RUNNER_B64) if RUNNER_B64 else ''\n");
        code.append("TESTS = decode(TESTS_B64)\n");
        code.append("try:\n");
        code.append("    with tempfile.TemporaryDirectory(prefix='job-buddy-practice-', dir='/tmp') as workspace:\n");
        code.append("        if LANGUAGE == 'python':\n");
        code.append("            write(os.path.join(workspace, 'main.py'), CODE)\n");
        code.append("            proc = subprocess.run(['python3', 'main.py'], cwd=workspace, input=TESTS, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)\n");
        code.append("            parse_child(proc)\n");
        code.append("        elif LANGUAGE == 'javascript':\n");
        code.append("            write(os.path.join(workspace, 'main.js'), CODE)\n");
        code.append("            proc = subprocess.run(['node', 'main.js'], cwd=workspace, input=TESTS, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)\n");
        code.append("            parse_child(proc)\n");
        code.append("        elif LANGUAGE == 'java':\n");
        code.append("            write(os.path.join(workspace, 'Solution.java'), CODE)\n");
        code.append("            write(os.path.join(workspace, 'Runner.java'), RUNNER)\n");
        code.append("            compile_proc = subprocess.run(['javac', 'Solution.java', 'Runner.java'], cwd=workspace, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)\n");
        code.append("            if compile_proc.returncode != 0:\n");
        code.append("                emit({'passed': False, 'rows': [], 'message': first_line((compile_proc.stderr or '') + '\\n' + (compile_proc.stdout or ''), '编译失败')})\n");
        code.append("            proc = subprocess.run(['java', '-cp', workspace, 'Runner'], cwd=workspace, input=TESTS, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)\n");
        code.append("            parse_child(proc)\n");
        code.append("        else:\n");
        code.append("            emit({'passed': False, 'rows': [], 'message': '当前仅支持 Python、Java、JavaScript 运行样例'})\n");
        code.append("except subprocess.TimeoutExpired:\n");
        code.append("    emit({'passed': False, 'rows': [], 'message': '运行超时，请检查是否存在死循环'})\n");
        code.append("except FileNotFoundError as exc:\n");
        code.append("    emit({'passed': False, 'rows': [], 'message': '运行环境缺少命令：' + str(exc)})\n");
        code.append("except Exception as exc:\n");
        code.append("    emit({'passed': False, 'rows': [], 'message': str(exc) or '沙箱运行失败'})\n");
        return code.toString();
    }

    private Map<String, Object> sandboxPolicy() {
        Map<String, Object> network = new LinkedHashMap<String, Object>();
        network.put("allowedDomains", Collections.emptyList());
        network.put("deniedDomains", Collections.emptyList());

        Map<String, Object> filesystem = new LinkedHashMap<String, Object>();
        filesystem.put("denyRead", list("~/.ssh", "~/.aws", "~/.config/gcloud", "~/.kube"));
        filesystem.put("allowRead", Collections.emptyList());
        filesystem.put("allowWrite", list("/tmp", "/var/tmp", "/private/tmp"));
        filesystem.put("denyWrite", list(".env", "secrets/"));

        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("network", network);
        policy.put("filesystem", filesystem);
        return policy;
    }

    private Map<String, Object> sandboxOptions() {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("timeout", Integer.valueOf(SANDBOX_TIMEOUT_SECONDS));
        options.put("check", Boolean.FALSE);
        return options;
    }

    private String pythonHarness(String functionName) {
        return "import json, sys\n" +
                "def _stable(value):\n" +
                "    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))\n" +
                "tests = json.loads(sys.stdin.read() or '[]')\n" +
                "fn = globals().get('" + functionName + "')\n" +
                "rows = []\n" +
                "for test in tests:\n" +
                "    row = {'name': test.get('name', '用例'), 'input': _stable(test.get('args', [])), 'expected': _stable(test.get('expected'))}\n" +
                "    try:\n" +
                "        if not callable(fn): raise Exception('未找到函数：" + functionName + "')\n" +
                "        actual = fn(*(test.get('args') or []))\n" +
                "        row['actual'] = _stable(actual)\n" +
                "        row['passed'] = actual == test.get('expected')\n" +
                "    except Exception as exc:\n" +
                "        row['actual'] = '运行异常'\n" +
                "        row['passed'] = False\n" +
                "        row['error'] = str(exc)\n" +
                "    rows.append(row)\n" +
                "print(json.dumps({'passed': bool(rows) and all(r.get('passed') for r in rows), 'rows': rows}, ensure_ascii=False))\n";
    }

    private String javascriptHarness(String functionName) {
        return "function stable(value){ return JSON.stringify(value); }\n" +
                "const fs = require('fs');\n" +
                "const tests = JSON.parse(fs.readFileSync(0, 'utf8') || '[]');\n" +
                "const fn = typeof " + functionName + " === 'function' ? " + functionName + " : globalThis['" + functionName + "'];\n" +
                "const rows = [];\n" +
                "for (const test of tests) {\n" +
                "  const row = { name: test.name || '用例', input: stable(test.args || []), expected: stable(test.expected) };\n" +
                "  try {\n" +
                "    if (typeof fn !== 'function') throw new Error('未找到函数：" + functionName + "');\n" +
                "    const actual = fn.apply(null, JSON.parse(JSON.stringify(test.args || [])));\n" +
                "    row.actual = stable(actual); row.passed = stable(actual) === stable(test.expected);\n" +
                "  } catch (err) { row.actual = '运行异常'; row.passed = false; row.error = err && err.message ? err.message : String(err); }\n" +
                "  rows.push(row);\n" +
                "}\n" +
                "console.log(JSON.stringify({ passed: rows.length > 0 && rows.every(r => r.passed), rows }));\n";
    }

    private String javaRunner(String functionName) {
        return "import java.io.*;import java.lang.reflect.*;import java.util.*;" +
                "public class Runner{" +
                "public static void main(String[]a)throws Exception{String input=read();Object tests=new Parser(input).parse();List rows=new ArrayList();for(Object tv:(List)tests){Map t=(Map)tv;Map row=new LinkedHashMap();row.put(\"name\",t.get(\"name\")==null?\"用例\":t.get(\"name\"));row.put(\"input\",Json.write(t.get(\"args\")));row.put(\"expected\",Json.write(t.get(\"expected\")));try{Object actual=invoke(t.get(\"args\"));row.put(\"actual\",Json.write(actual));row.put(\"passed\",eq(actual,t.get(\"expected\")));}catch(Throwable e){row.put(\"actual\",\"运行异常\");row.put(\"passed\",false);row.put(\"error\",e.getCause()==null?e.getMessage():e.getCause().getMessage());}rows.add(row);}boolean ok=!rows.isEmpty();for(Object r:rows)ok=ok&&Boolean.TRUE.equals(((Map)r).get(\"passed\"));Map out=new LinkedHashMap();out.put(\"passed\",ok);out.put(\"rows\",rows);System.out.println(Json.write(out));}" +
                "static Object invoke(Object argsObj)throws Exception{List args=argsObj instanceof List?(List)argsObj:new ArrayList();Solution s=new Solution();Method target=null;for(Method m:Solution.class.getDeclaredMethods()){if(m.getName().equals(\"" + functionName + "\")){target=m;break;}}if(target==null)throw new RuntimeException(\"未找到方法：" + functionName + "\");target.setAccessible(true);Class[] types=target.getParameterTypes();Object[] values;if(target.isVarArgs()&&types.length==1){values=new Object[]{args.toArray(new Object[0])};}else{values=new Object[types.length];for(int i=0;i<types.length;i++)values[i]=convert(i<args.size()?args.get(i):null,types[i]);}return target.invoke(s,values);}" +
                "static Object convert(Object v,Class t){if(v==null)return null;if(t==Object.class)return v;if(t==String.class)return String.valueOf(v);if(t==int.class||t==Integer.class)return ((Number)v).intValue();if(t==long.class||t==Long.class)return ((Number)v).longValue();if(t==double.class||t==Double.class)return ((Number)v).doubleValue();if(t==boolean.class||t==Boolean.class)return Boolean.valueOf(String.valueOf(v));if(t.isArray()&&v instanceof List){List l=(List)v;Class c=t.getComponentType();Object arr=Array.newInstance(c,l.size());for(int i=0;i<l.size();i++)Array.set(arr,i,convert(l.get(i),c));return arr;}return v;}" +
                "static boolean eq(Object a,Object b){if(a==b)return true;if(a==null||b==null)return false;if(a instanceof Number&&b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;if(a.getClass().isArray())a=toList(a);if(b.getClass().isArray())b=toList(b);if(a instanceof List&&b instanceof List){List x=(List)a,y=(List)b;if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!eq(x.get(i),y.get(i)))return false;return true;}if(a instanceof Map&&b instanceof Map){Map x=(Map)a,y=(Map)b;if(x.size()!=y.size())return false;for(Object k:x.keySet())if(!eq(x.get(k),y.get(k)))return false;return true;}return a.equals(b);}" +
                "static List toList(Object arr){List l=new ArrayList();int n=Array.getLength(arr);for(int i=0;i<n;i++)l.add(Array.get(arr,i));return l;}" +
                "static String read()throws Exception{BufferedReader br=new BufferedReader(new InputStreamReader(System.in,\"UTF-8\"));StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);return sb.toString();}" +
                "static class Json{static String write(Object v){if(v==null)return\"null\";if(v instanceof String)return\"\\\"\"+((String)v).replace(\"\\\\\",\"\\\\\\\\\").replace(\"\\\"\",\"\\\\\\\"\")+\"\\\"\";if(v instanceof Number||v instanceof Boolean)return String.valueOf(v);if(v.getClass().isArray()){List l=new ArrayList();int n=Array.getLength(v);for(int i=0;i<n;i++)l.add(Array.get(v,i));return write(l);}if(v instanceof Map){StringBuilder sb=new StringBuilder(\"{\");boolean f=true;for(Object e0:((Map)v).entrySet()){Map.Entry e=(Map.Entry)e0;if(!f)sb.append(',');f=false;sb.append(write(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));}return sb.append('}').toString();}if(v instanceof Iterable){StringBuilder sb=new StringBuilder(\"[\");boolean f=true;for(Object x:(Iterable)v){if(!f)sb.append(',');f=false;sb.append(write(x));}return sb.append(']').toString();}return write(String.valueOf(v));}}" +
                "static class Parser{String s;int i;Parser(String s){this.s=s==null?\"\":s;}Object parse(){skip();return val();}void skip(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}char ch(){return s.charAt(i);}Object val(){skip();char c=ch();if(c=='{')return obj();if(c=='[')return arr();if(c=='\\\"')return str();if(s.startsWith(\"true\",i)){i+=4;return true;}if(s.startsWith(\"false\",i)){i+=5;return false;}if(s.startsWith(\"null\",i)){i+=4;return null;}return num();}Map obj(){Map m=new LinkedHashMap();i++;skip();while(ch()!='}'){String k=str();skip();i++;Object v=val();m.put(k,v);skip();if(ch()==','){i++;skip();}}i++;return m;}List arr(){List l=new ArrayList();i++;skip();while(ch()!=']'){l.add(val());skip();if(ch()==','){i++;skip();}}i++;return l;}String str(){StringBuilder sb=new StringBuilder();i++;while(ch()!='\\\"'){char c=ch();if(c=='\\\\'){i++;c=ch();if(c=='n')sb.append('\\n');else if(c=='t')sb.append('\\t');else sb.append(c);}else sb.append(c);i++;}i++;return sb.toString();}Number num(){int j=i;while(i<s.length()&&\"-+.0123456789eE\".indexOf(ch())>=0)i++;String n=s.substring(j,i);if(n.indexOf('.')>=0||n.indexOf('e')>=0||n.indexOf('E')>=0)return Double.valueOf(n);return Long.valueOf(n);}}" +
                "}";
    }

    private List<Map<String, Object>> normalizeTests(Object testsValue) {
        List<Map<String, Object>> tests = new ArrayList<Map<String, Object>>();
        if (!(testsValue instanceof List)) return tests;
        for (Object item : (List) testsValue) {
            if (!(item instanceof Map)) continue;
            tests.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
            if (tests.size() >= MAX_TESTS) break;
        }
        return tests;
    }

    private String normalizeLanguage(String value) {
        String text = value == null ? "" : value.trim().toLowerCase();
        if ("js".equals(text) || "node".equals(text) || "javascript".equals(text)) return "javascript";
        if ("py".equals(text) || "python".equals(text) || "python3".equals(text)) return "python";
        if ("java".equals(text)) return "java";
        throw new IllegalArgumentException("当前仅支持 Python、Java、JavaScript 运行样例");
    }

    private String normalizeFunctionName(String value) {
        String text = value == null || value.trim().isEmpty() ? "solution" : value.trim();
        if (!text.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("函数名不合法");
        return text;
    }

    private String sandboxBaseUrl() {
        return properties == null ? "" : properties.resolvedSandboxUrl();
    }

    private String pyString(String value) throws Exception {
        return objectMapper.writeValueAsString(value == null ? "" : value);
    }

    private String base64(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("passed", Boolean.FALSE);
        result.put("rows", Collections.emptyList());
        result.put("message", message == null || message.trim().isEmpty() ? "运行失败" : message);
        return result;
    }

    private List<String> list(String... values) {
        List<String> result = new ArrayList<String>();
        Collections.addAll(result, values);
        return result;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private String firstLine(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String text = value.trim();
        int newline = text.indexOf('\n');
        return newline >= 0 ? text.substring(0, newline) : text;
    }

    private String lastNonEmptyLine(String value) {
        if (value == null) return null;
        String[] lines = value.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) return lines[i].trim();
        }
        return null;
    }

    private String compact(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String text = value.trim().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
