package com.zcode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zcode.index.CodeIndexService;
import com.zcode.index.QdrantClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Semantic code search over the workspace (Qdrant + embeddings). Prefer grep when you know
 * exact symbols; use this for natural-language / conceptual queries.
 */
@Component
public class CodeSearchTool implements Tool {

    private static final int MAX_SNIPPET_CHARS = 1200;

    private final CodeIndexService indexService;

    public CodeSearchTool(CodeIndexService indexService) {
        this.indexService = indexService;
    }

    @Override
    public String name() {
        return "code_search";
    }

    @Override
    public String description() {
        return "Semantic search over workspace source files (natural language). "
                + "Returns path + line range + snippet. Prefer grep for exact symbols/strings; "
                + "use code_search when you describe behavior or don't know keywords. "
                + "Requires local Qdrant and embedding API.";
    }

    @Override
    public JsonNode inputSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query")
                .put("type", "string")
                .put("description", "Natural-language description of what to find");
        root.putArray("required").add("query");
        return root;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext ctx) throws Exception {
        String query = text(input, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("query is required");
        }

        CodeIndexService.SearchOutcome outcome = indexService.search(ctx.workspace(), query.trim());
        if (!outcome.ok()) {
            return ToolResult.error(outcome.error());
        }

        List<String> lines = new ArrayList<>();
        if (outcome.sync() != null && outcome.sync().note() != null && !outcome.sync().note().isBlank()) {
            lines.add("[" + outcome.sync().note() + "]");
            lines.add("");
        }
        List<QdrantClient.SearchHit> hits = outcome.hits();
        if (hits == null || hits.isEmpty()) {
            lines.add("(no semantic matches)");
            lines.add("Tip: try grep with a known symbol, or broaden the query.");
            return ToolResult.ok(String.join("\n", lines));
        }

        int i = 1;
        for (QdrantClient.SearchHit hit : hits) {
            lines.add(i + ". " + hit.path() + ":" + hit.startLine() + "-" + hit.endLine()
                    + "  (score=" + String.format("%.3f", hit.score()) + ")");
            String snippet = hit.text() == null ? "" : hit.text();
            if (snippet.length() > MAX_SNIPPET_CHARS) {
                snippet = snippet.substring(0, MAX_SNIPPET_CHARS) + "\n…";
            }
            for (String sl : snippet.split("\n", -1)) {
                lines.add("   " + sl);
            }
            lines.add("");
            i++;
        }
        lines.add("Next: read the paths above for full context before editing.");
        String body = String.join("\n", lines);
        if (body.length() > ctx.maxOutputChars()) {
            body = body.substring(0, ctx.maxOutputChars()) + "\n…(truncated)";
        }
        return ToolResult.ok(body);
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
