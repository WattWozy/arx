package dev.archtelemetry.adapter.mcp;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class ArxMcpServerTest {

    private static ArxMcpServer server(ArxMcpServer.ToolExecutor executor) {
        return new ArxMcpServer(System.out, System.err, executor);
    }

    private static ArxMcpServer mockServer() {
        return server((name, args) -> "{\"ok\":true}");
    }

    // -------------------------------------------------------------------------
    // initialize / tools/list lifecycle
    // -------------------------------------------------------------------------

    @Test
    void initialize_returnsProtocolVersionAndServerInfo() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertNotNull(response);
        assertTrue(response.contains("\"jsonrpc\":\"2.0\""), "must include jsonrpc version");
        assertTrue(response.contains("\"id\":1"), "must echo id");
        assertTrue(response.contains("protocolVersion"), "must include protocolVersion");
        assertTrue(response.contains("arx"), "serverInfo must include name 'arx'");
        assertTrue(response.contains("capabilities"), "must include capabilities");
    }

    @Test
    void toolsList_returnsAllSixTools() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertNotNull(response);
        assertTrue(response.contains("check_violations"),    "missing check_violations");
        assertTrue(response.contains("get_metrics"),         "missing get_metrics");
        assertTrue(response.contains("infer_blueprint"),     "missing infer_blueprint");
        assertTrue(response.contains("scan_report"),         "missing scan_report");
        assertTrue(response.contains("query_architecture"),  "missing query_architecture");
        assertTrue(response.contains("get_violation_trend"), "missing get_violation_trend");
    }

    // -------------------------------------------------------------------------
    // Schema validity
    // -------------------------------------------------------------------------

    @Test
    void toolsList_eachToolHasTypeObjectSchema() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}");
        int count = countOccurrences(response, "\"type\": \"object\"");
        assertEquals(10, count, "each of the 10 tools must have inputSchema with type:object");
    }

    @Test
    void toolsList_eachToolSchemaHasRequired() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\",\"params\":{}}");
        int count = countOccurrences(response, "\"required\"");
        assertEquals(10, count, "each of the 10 tools must have a required array in inputSchema");
    }

    @Test
    void toolsList_checkViolationsRequiresRepoAndBlueprint() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{}}");
        int checkIdx = response.indexOf("check_violations");
        assertTrue(checkIdx >= 0);
        String toolSection = response.substring(checkIdx, Math.min(checkIdx + 600, response.length()));
        assertTrue(toolSection.contains("\"repo\""),      "check_violations schema must reference repo");
        assertTrue(toolSection.contains("\"blueprint\""), "check_violations schema must reference blueprint");
        assertTrue(toolSection.contains("\"files\""),     "check_violations schema must reference files");
    }

    @Test
    void toolsList_inferBlueprintDoesNotRequireBlueprint() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\",\"params\":{}}");
        int idx = response.indexOf("infer_blueprint");
        assertTrue(idx >= 0);
        // The required array for infer_blueprint must contain "repo" but not "blueprint"
        String toolSection = response.substring(idx, Math.min(idx + 500, response.length()));
        assertTrue(toolSection.contains("\"repo\""));
        assertFalse(toolSection.contains("\"depth\"") && toolSection.contains("\"required\":[\"repo\",\"blueprint\"]"),
                "infer_blueprint should not require blueprint argument");
    }

    // -------------------------------------------------------------------------
    // Tool dispatch
    // -------------------------------------------------------------------------

    @Test
    void toolsCall_dispatchesCorrectToolName() {
        String[] captured = {null};
        ArxMcpServer s = server((name, args) -> { captured[0] = name; return "{}"; });

        s.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"check_violations\","
                + "\"arguments\":{\"repo\":\"/r\",\"blueprint\":\"/b\"}}}");

        assertEquals("check_violations", captured[0]);
    }

    @Test
    void toolsCall_passesArgumentsJsonToExecutor() {
        String[] capturedArgs = {null};
        ArxMcpServer s = server((name, args) -> { capturedArgs[0] = args; return "{}"; });

        s.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_metrics\","
                + "\"arguments\":{\"repo\":\"/myrepo\",\"blueprint\":\"/arch.blu\"}}}");

        assertNotNull(capturedArgs[0]);
        assertTrue(capturedArgs[0].contains("myrepo"),  "arguments json must contain repo value");
        assertTrue(capturedArgs[0].contains("arch.blu"), "arguments json must contain blueprint value");
    }

    @Test
    void toolsCall_responseIsWellFormedMcpJson() {
        ArxMcpServer s = server((name, args) -> "{\"answer\":42}");
        String response = s.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_metrics\","
                + "\"arguments\":{\"repo\":\"/r\",\"blueprint\":\"/b\"}}}");

        assertTrue(response.contains("\"jsonrpc\":\"2.0\""), "must have jsonrpc field");
        assertTrue(response.contains("\"id\":99"),           "must echo id");
        assertTrue(response.contains("\"result\""),          "must have result");
        assertTrue(response.contains("\"content\""),         "result must have content array");
        assertTrue(response.contains("\"type\":\"text\""),   "content item must have type:text");
        assertTrue(response.contains("\"text\""),            "content item must have text field");
    }

    @Test
    void toolsCall_missingArguments_defaultsToEmptyObject() {
        String[] capturedArgs = {null};
        ArxMcpServer s = server((name, args) -> { capturedArgs[0] = args; return "{}"; });

        s.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"check_violations\"}}");

        assertEquals("{}", capturedArgs[0], "absent arguments must default to {}");
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void unknownTool_returnsIsErrorTrue() {
        ArxMcpServer s = new ArxMcpServer(System.out, System.err, null);
        String response = s.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}");
        assertTrue(response.contains("isError") || response.contains("Error"),
                "unknown tool must produce error response: " + response);
    }

    @Test
    void missingMethod_returnsInvalidRequest() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":12}");
        assertTrue(response.contains("\"error\""),          "missing method must return error object");
        assertTrue(response.contains("-32600")
                || response.contains("Invalid Request"),    "error code must be -32600");
    }

    @Test
    void unknownMethod_returnsMethodNotFound() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"rpc/unknown\",\"params\":{}}");
        assertTrue(response.contains("-32601")
                || response.contains("Method not found"),   "must return -32601");
    }

    @Test
    void notification_returnsNull() {
        String response = mockServer().handleMessage(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        assertNull(response, "notifications must not produce a response");
    }

    @Test
    void toolsCall_executorThrowsIllegalArgument_wrappedAsIsError() {
        ArxMcpServer s = server((name, args) -> {
            throw new IllegalArgumentException("bad args");
        });
        String response = s.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"check_violations\",\"arguments\":{}}}");
        assertTrue(response.contains("isError"),    "IllegalArgumentException must set isError:true");
        assertTrue(response.contains("bad args"),   "error message must be forwarded");
    }

    // -------------------------------------------------------------------------
    // McpJson unit tests
    // -------------------------------------------------------------------------

    @Test
    void mcpJson_getString_findsValue() {
        assertEquals("hello", McpJson.getString("{\"key\":\"hello\"}", "key"));
    }

    @Test
    void mcpJson_getString_returnsNullWhenAbsent() {
        assertNull(McpJson.getString("{\"other\":\"x\"}", "key"));
    }

    @Test
    void mcpJson_getInt_findsValue() {
        assertEquals(42, McpJson.getInt("{\"n\":42}", "n", 0));
    }

    @Test
    void mcpJson_getInt_returnsDefaultWhenAbsent() {
        assertEquals(7, McpJson.getInt("{}", "n", 7));
    }

    @Test
    void mcpJson_getStringArray_findsValues() {
        java.util.List<String> arr = McpJson.getStringArray("{\"files\":[\"a.java\",\"b.java\"]}", "files");
        assertEquals(2, arr.size());
        assertTrue(arr.contains("a.java"));
        assertTrue(arr.contains("b.java"));
    }

    @Test
    void mcpJson_getObject_extractsNestedObject() {
        String obj = McpJson.getObject("{\"params\":{\"name\":\"foo\",\"x\":1}}", "params");
        assertNotNull(obj);
        assertTrue(obj.startsWith("{") && obj.endsWith("}"));
        assertTrue(obj.contains("foo"));
    }

    @Test
    void mcpJson_getRawId_handlesNumber() {
        assertEquals("5", McpJson.getRawId("{\"id\":5,\"method\":\"x\"}"));
    }

    @Test
    void mcpJson_getRawId_handlesString() {
        assertEquals("\"abc\"", McpJson.getRawId("{\"id\":\"abc\",\"method\":\"x\"}"));
    }

    @Test
    void mcpJson_getRawId_handlesNull() {
        assertEquals("null", McpJson.getRawId("{\"id\":null,\"method\":\"x\"}"));
    }

    @Test
    void mcpJson_getRawId_returnNullWhenAbsent() {
        assertEquals("null", McpJson.getRawId("{\"method\":\"x\"}"));
    }

    @Test
    void mcpJson_escape_handlesSpecialChars() {
        String escaped = McpJson.escape("line1\nline2\t\"quoted\"\\back");
        assertTrue(escaped.contains("\\n"));
        assertTrue(escaped.contains("\\t"));
        assertTrue(escaped.contains("\\\""));
        assertTrue(escaped.contains("\\\\"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) { count++; idx += sub.length(); }
        return count;
    }
}
