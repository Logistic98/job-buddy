import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class Runner {
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

  static List toList(Object array) {
    List list = new ArrayList();
    int length = Array.getLength(array);
    for (int i = 0; i < length; i++) list.add(Array.get(array, i));
    return list;
  }

  static String read() throws Exception {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
    StringBuilder content = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) content.append(line);
    return content.toString();
  }

  static class Json {
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

  static class Parser {
    String source;
    int index;

    Parser(String source) {
      this.source = source == null ? "" : source;
    }

    Object parse() {
      skipWhitespace();
      return value();
    }

    void skipWhitespace() {
      while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
    }

    char current() {
      return source.charAt(index);
    }

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
