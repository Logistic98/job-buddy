import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * 提供 Java 代码入口解析、参数转换和结果比较能力。
 */
public class Runner {
  /**
   * 逐项执行测试用例并输出结构化结果。
   *
   * @param args 命令行参数
   * @throws Exception 输入解析或反射调用失败时抛出
   */
  public static void main(String[] args) throws Exception {
    String input = read();
    Object tests = new Parser(input).parse();
    List rows = new ArrayList();
    for (Object testValue : (List) tests) {
      Map test = (Map) testValue;
      Map row = new LinkedHashMap();
      row.put("name", test.get("name") == null ? "用例" : test.get("name"));
      row.put("input", Json.write(test.get("args")));
      boolean hasExpected = test.containsKey("expected");
      if (hasExpected) row.put("expected", Json.write(test.get("expected")));
      try {
        Object actual = invoke(test.get("args"));
        row.put("actual", Json.write(actual));
        row.put("passed", !hasExpected || equalsValue(actual, test.get("expected")));
      } catch (Throwable error) {
        row.put("actual", "运行异常");
        row.put("passed", false);
        row.put(
            "error",
            error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
      }
      rows.add(row);
    }
    boolean passed = !rows.isEmpty();
    for (Object row : rows) {
      passed = passed && Boolean.TRUE.equals(((Map) row).get("passed"));
    }
    Map output = new LinkedHashMap();
    output.put("passed", passed);
    output.put("rows", rows);
    System.out.println(Json.write(output));
  }

  /**
   * 根据模板指定的方法名反射调用用户代码。
   *
   * @param argsValue 用例参数
   * @return 用户方法返回值
   * @throws Exception 未找到入口或反射调用失败时抛出
   */
  static Object invoke(Object argsValue) throws Exception {
    List args = argsValue instanceof List ? (List) argsValue : new ArrayList();
    Solution solution = new Solution();
    Method target = null;
    for (Method method : Solution.class.getDeclaredMethods()) {
      if (method.getName().equals("__FUNCTION_NAME__")) {
        target = method;
        break;
      }
    }
    if (target == null) throw new RuntimeException("未找到方法：__FUNCTION_NAME__");
    target.setAccessible(true);
    Class[] parameterTypes = target.getParameterTypes();
    Object[] values;
    if (target.isVarArgs() && parameterTypes.length == 1) {
      values = new Object[] {args.toArray(new Object[0])};
    } else {
      values = new Object[parameterTypes.length];
      for (int i = 0; i < parameterTypes.length; i++) {
        values[i] = convert(i < args.size() ? args.get(i) : null, parameterTypes[i]);
      }
    }
    return target.invoke(solution, values);
  }

  /**
   * 将 JSON 值转换为用户方法声明的参数类型。
   *
   * @param value 原始参数值
   * @param targetType 目标参数类型
   * @return 转换后的参数值
   */
  static Object convert(Object value, Class targetType) {
    if (value == null) return null;
    if (targetType == Object.class) return value;
    if (targetType == String.class) return String.valueOf(value);
    if (targetType == int.class || targetType == Integer.class)
      return ((Number) value).intValue();
    if (targetType == long.class || targetType == Long.class)
      return ((Number) value).longValue();
    if (targetType == double.class || targetType == Double.class)
      return ((Number) value).doubleValue();
    if (targetType == boolean.class || targetType == Boolean.class)
      return Boolean.valueOf(String.valueOf(value));
    if (targetType.isArray() && value instanceof List) {
      List list = (List) value;
      Class componentType = targetType.getComponentType();
      Object array = Array.newInstance(componentType, list.size());
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, convert(list.get(i), componentType));
      }
      return array;
    }
    return value;
  }

  /**
   * 深度比较数组、集合、映射和基础值。
   *
   * @param left 实际值
   * @param right 期望值
   * @return 两个值是否等价
   */
  static boolean equalsValue(Object left, Object right) {
    if (left == right) return true;
    if (left == null || right == null) return false;
    if (left instanceof Number && right instanceof Number) {
      return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
    }
    if (left.getClass().isArray()) left = toList(left);
    if (right.getClass().isArray()) right = toList(right);
    if (left instanceof List && right instanceof List) {
      List leftList = (List) left;
      List rightList = (List) right;
      if (leftList.size() != rightList.size()) return false;
      for (int i = 0; i < leftList.size(); i++) {
        if (!equalsValue(leftList.get(i), rightList.get(i))) return false;
      }
      return true;
    }
    if (left instanceof Map && right instanceof Map) {
      Map leftMap = (Map) left;
      Map rightMap = (Map) right;
      if (leftMap.size() != rightMap.size()) return false;
      for (Object key : leftMap.keySet()) {
        if (!equalsValue(leftMap.get(key), rightMap.get(key))) return false;
      }
      return true;
    }
    return left.equals(right);
  }

  /**
   * 将任意 Java 数组转换为列表。
   *
   * @param array Java 数组
   * @return 数组元素列表
   */
  static List toList(Object array) {
    List list = new ArrayList();
    int length = Array.getLength(array);
    for (int i = 0; i < length; i++) list.add(Array.get(array, i));
    return list;
  }

  /**
   * 读取标准输入中的完整测试数据。
   *
   * @return 输入文本
   * @throws Exception 读取失败时抛出
   */
  static String read() throws Exception {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
    StringBuilder content = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) content.append(line);
    return content.toString();
  }

  /**
   * 提供最小化 JSON 序列化能力。
   */
  static class Json {
    /**
     * 将支持的 Java 值序列化为 JSON。
     *
     * @param value 待序列化值
     * @return JSON 文本
     */
    static String write(Object value) {
      if (value == null) return "null";
      if (value instanceof String) {
        return "\""
            + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"";
      }
      if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
      if (value.getClass().isArray()) return write(toList(value));
      if (value instanceof Map) {
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (Object entryValue : ((Map) value).entrySet()) {
          Map.Entry entry = (Map.Entry) entryValue;
          if (!first) output.append(',');
          first = false;
          output
              .append(write(String.valueOf(entry.getKey())))
              .append(':')
              .append(write(entry.getValue()));
        }
        return output.append('}').toString();
      }
      if (value instanceof Iterable) {
        StringBuilder output = new StringBuilder("[");
        boolean first = true;
        for (Object item : (Iterable) value) {
          if (!first) output.append(',');
          first = false;
          output.append(write(item));
        }
        return output.append(']').toString();
      }
      return write(String.valueOf(value));
    }
  }

  /**
   * 解析测试用例使用的最小 JSON 子集。
   */
  static class Parser {
    String source;
    int index;

    /**
     * 创建 JSON 解析器。
     *
     * @param source JSON 文本
     */
    Parser(String source) {
      this.source = source == null ? "" : source;
    }

    /**
     * 解析完整 JSON 输入。
     *
     * @return 解析结果
     */
    Object parse() {
      skipWhitespace();
      return value();
    }

    /**
     * 跳过当前位置后的连续空白字符。
     */
    void skipWhitespace() {
      while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
    }

    /**
     * 获取当前位置字符。
     *
     * @return 当前字符
     */
    char current() {
      return source.charAt(index);
    }

    /**
     * 按首字符分派并解析单个 JSON 值。
     *
     * @return JSON 值
     */
    Object value() {
      skipWhitespace();
      char current = current();
      if (current == '{') return object();
      if (current == '[') return array();
      if (current == '"') return string();
      if (source.startsWith("true", index)) {
        index += 4;
        return true;
      }
      if (source.startsWith("false", index)) {
        index += 5;
        return false;
      }
      if (source.startsWith("null", index)) {
        index += 4;
        return null;
      }
      return number();
    }

    /**
     * 解析 JSON 对象。
     *
     * @return 键值映射
     */
    Map object() {
      Map result = new LinkedHashMap();
      index++;
      skipWhitespace();
      while (current() != '}') {
        String key = string();
        skipWhitespace();
        index++;
        Object value = value();
        result.put(key, value);
        skipWhitespace();
        if (current() == ',') {
          index++;
          skipWhitespace();
        }
      }
      index++;
      return result;
    }

    /**
     * 解析 JSON 数组。
     *
     * @return 元素列表
     */
    List array() {
      List result = new ArrayList();
      index++;
      skipWhitespace();
      while (current() != ']') {
        result.add(value());
        skipWhitespace();
        if (current() == ',') {
          index++;
          skipWhitespace();
        }
      }
      index++;
      return result;
    }

    /**
     * 解析 JSON 字符串及基础转义字符。
     *
     * @return 解码后的字符串
     */
    String string() {
      StringBuilder result = new StringBuilder();
      index++;
      while (current() != '"') {
        char current = current();
        if (current == '\\') {
          index++;
          current = current();
          if (current == 'n') result.append('\n');
          else if (current == 't') result.append('\t');
          else result.append(current);
        } else {
          result.append(current);
        }
        index++;
      }
      index++;
      return result.toString();
    }

    /**
     * 解析整数或浮点数。
     *
     * @return 数值
     */
    Number number() {
      int start = index;
      while (index < source.length() && "-+.0123456789eE".indexOf(current()) >= 0) index++;
      String number = source.substring(start, index);
      if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
        return Double.valueOf(number);
      }
      return Long.valueOf(number);
    }
  }
}
