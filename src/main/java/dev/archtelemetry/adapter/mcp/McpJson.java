package dev.archtelemetry.adapter.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class McpJson {

    private McpJson() {}

    static String getString(String json, String key) {
        Pattern p = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? unescape(m.group(1)) : null;
    }

    static int getInt(String json, String key, int defaultValue) {
        Pattern p = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    static List<String> getStringArray(String json, String key) {
        Pattern p = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[");
        Matcher m = p.matcher(json);
        if (!m.find()) return List.of();
        String bracket = extractBracketed(json, m.end() - 1, '[', ']');
        if (bracket == null) return List.of();
        List<String> result = new ArrayList<>();
        Matcher sm = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(bracket);
        while (sm.find()) result.add(unescape(sm.group(1)));
        return result;
    }

    static String getObject(String json, String key) {
        Pattern p = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        return extractBracketed(json, m.end() - 1, '{', '}');
    }

    static String getRawId(String json) {
        Matcher nm = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (nm.find()) return nm.group(1);
        Matcher sm = Pattern.compile("\"id\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (sm.find()) return "\"" + sm.group(1) + "\"";
        if (Pattern.compile("\"id\"\\s*:\\s*null").matcher(json).find()) return "null";
        return "null";
    }

    static String escape(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    private static String extractBracketed(String json, int start, char open, char close) {
        if (start >= json.length() || json.charAt(start) != open) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped)          { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"')         { inString = !inString; continue; }
            if (inString)         continue;
            if (c == open)        depth++;
            else if (c == close && --depth == 0) return json.substring(start, i + 1);
        }
        return null;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
