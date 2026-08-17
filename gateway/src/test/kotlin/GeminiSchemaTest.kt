import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MCP tool schemas translated into Gemini function declarations.
 *
 * Gemini's schema dialect is narrower than JSON Schema and REJECTS the extra
 * keywords rather than ignoring them: one `additionalProperties` left in place
 * and the whole request comes back HTTP 400 — meaning chat is broken outright,
 * for every question, with an error that names the field and not the tool it
 * came from. The tool definitions stay plain JSON Schema for MCP clients, so
 * this translation is the only thing standing between the two dialects.
 */
class GeminiSchemaTest {

    /** One tool, shaped like the ones in Tools.kt but carrying every keyword
     *  Gemini refuses, at every depth. */
    private fun tool(): JSONObject = JSONObject()
        .put("name", "search")
        .put("description", "Search the library.")
        .put("inputSchema", JSONObject()
            .put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray().put("query"))
            .put("properties", JSONObject()
                .put("query", JSONObject()
                    .put("type", "string")
                    .put("description", "What to look for")
                    .put("examples", JSONArray().put("obstruction")))
                .put("limit", JSONObject()
                    .put("type", "integer")
                    .put("default", 8)
                    .put("minimum", 1)
                    .put("maximum", 20)
                    .put("exclusiveMinimum", 0))
                .put("filters", JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("additionalProperties", true)
                        .put("properties", JSONObject()
                            .put("path", JSONObject().put("type", "string")))))))

    private fun declaration(): JSONObject =
        Gemini.declarations(JSONArray().put(tool())).getJSONObject(0)

    /** Every key appearing anywhere in a JSON tree, at any depth. */
    private fun keysOf(value: Any?): Set<String> = when (value) {
        is JSONObject -> value.keys().asSequence().toSet() +
            value.keys().asSequence().flatMap { keysOf(value.get(it)) }
        is JSONArray -> (0 until value.length()).flatMap { keysOf(value.get(it)) }.toSet()
        else -> emptySet()
    }

    @Test
    fun `a declaration carries the name, the description and the schema`() {
        val d = declaration()

        assertEquals("search", d.getString("name"))
        assertEquals("Search the library.", d.getString("description"))
        assertEquals("object", d.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun `not one unsupported keyword survives, at any depth`() {
        val keys = keysOf(declaration().getJSONObject("parameters"))

        for (banned in listOf(
            "additionalProperties", "\$schema", "default", "examples",
            "exclusiveMinimum", "exclusiveMaximum",
        )) {
            assertFalse(banned in keys, "'$banned' reached Gemini and would have 400'd the request")
        }
    }

    @Test
    fun `everything the dialect does accept is left intact`() {
        val params = declaration().getJSONObject("parameters")
        val properties = params.getJSONObject("properties")

        assertEquals(listOf("query"), params.getJSONArray("required").toList())
        assertEquals("What to look for", properties.getJSONObject("query").getString("description"))
        assertEquals(1, properties.getJSONObject("limit").getInt("minimum"))
        assertEquals(20, properties.getJSONObject("limit").getInt("maximum"))
        assertEquals(setOf("query", "limit", "filters"), properties.keys().asSequence().toSet())
    }

    @Test
    fun `an array's item schema is sanitised too, not just copied`() {
        // The recursive case that a shallow strip would miss: `items` is a nested
        // object, and its own additionalProperties is just as fatal.
        val items = declaration().getJSONObject("parameters")
            .getJSONObject("properties").getJSONObject("filters").getJSONObject("items")

        assertFalse(items.has("additionalProperties"))
        assertEquals("string", items.getJSONObject("properties").getJSONObject("path").getString("type"))
    }

    @Test
    fun `a tool with no schema still declares an empty parameter object`() {
        // Gemini wants a `parameters` object even for a no-argument tool; a null
        // there is another 400.
        val d = Gemini.declarations(JSONArray().put(JSONObject().put("name", "ping")))
            .getJSONObject(0)

        assertEquals("ping", d.getString("name"))
        assertEquals("", d.getString("description"))
        assertTrue(d.getJSONObject("parameters").isEmpty)
    }

    @Test
    fun `every tool in the list is declared, in order`() {
        val declared = Gemini.declarations(JSONArray()
            .put(JSONObject().put("name", "search"))
            .put(JSONObject().put("name", "load_chunk")))

        assertEquals(2, declared.length())
        assertEquals("search", declared.getJSONObject(0).getString("name"))
        assertEquals("load_chunk", declared.getJSONObject(1).getString("name"))
    }

    @Test
    fun `the original tool definition is not mutated`() {
        // Tools.list() is what MCP clients are served. If sanitising edited it in
        // place, an MCP client would receive a schema with its constraints
        // stripped out — and only after the first chat turn had run.
        val tools = JSONArray().put(tool())
        Gemini.declarations(tools)

        assertTrue(tools.getJSONObject(0).getJSONObject("inputSchema").has("additionalProperties"))
    }
}
