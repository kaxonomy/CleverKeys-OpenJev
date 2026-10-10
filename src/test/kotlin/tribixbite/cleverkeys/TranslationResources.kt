package tribixbite.cleverkeys

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Shared reader for every resource XML file in each locale, used by the translation tests
 * ([TranslationGlossaryTest], [TranslationLengthTest]). Runs with the project root as CWD,
 * like [TranslationCoverageDriftTest].
 */
internal object TranslationResources {

    /** The default (English) resource directory. */
    val defaultDir = File("res/values")

    /** Every translated locale directory (qualifier dirs such as `values-night` excluded). */
    val localeDirs: List<File> by lazy {
        File("res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .filter { it.name != "values-night" && it.name != "values-v29" }
            .sortedBy { it.name }
    }

    /** `values-zh-rCN` -> `zh-rCN`. */
    fun localeOf(dir: File): String = dir.name.removePrefix("values-")

    /**
     * Name -> flattened text of every `<string>` in every XML file in [dir]. `<plurals>` and
     * `<string-array>` are skipped: none of the translation checks key on them, and their
     * flattened text would mix quantity items. Text is returned as stored (Android escapes
     * such as `\'` intact); callers that measure or match text use [unescape].
     */
    fun strings(dir: File): Map<String, String> = cache.getOrPut(dir.path) {
        val out = LinkedHashMap<String, String>()
        for (node in resourceElements(dir)) {
            if (node.tagName != "string") continue
            val name = node.getAttribute("name")
            if (name.isNullOrEmpty()) continue
            out[name] = node.textContent.orEmpty()
        }
        out
    }

    private val cache = HashMap<String, Map<String, String>>()

    /**
     * Every user-visible text in every XML file in [dir]: each `<string>` under its name and each
     * `<plurals>` item under `name#quantity`. Used by checks that must see ALL copy in a locale
     * (the register rule), where skipping plurals would leave count strings unchecked.
     */
    fun allTexts(dir: File): Map<String, String> = allCache.getOrPut(dir.path) {
        val out = LinkedHashMap<String, String>()
        for (node in resourceElements(dir)) {
            val name = node.getAttribute("name")
            if (name.isNullOrEmpty()) continue
            when (node.tagName) {
                "string" -> out[name] = node.textContent.orEmpty()
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        out["$name#${item.getAttribute("quantity")}"] = item.textContent.orEmpty()
                    }
                }
            }
        }
        out
    }

    private val allCache = HashMap<String, Map<String, String>>()

    private fun resourceElements(dir: File): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val builder = factory.newDocumentBuilder()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "xml" }
            .sortedBy { it.name }
            .flatMap { file ->
                val children = builder.parse(file).documentElement.childNodes
                (0 until children.length).mapNotNull { children.item(it) as? Element }
            }
    }

    /** Resolves the Android string-resource escapes that affect visible text. */
    fun unescape(text: String): String = text
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .replace("\\t", " ")
        .trim()
}
