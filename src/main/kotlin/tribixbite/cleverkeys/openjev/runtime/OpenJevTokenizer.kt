package tribixbite.cleverkeys.openjev.runtime

import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern

/** Minimal implementation of the pinned ModernBERT NFC / GPT-2 ByteLevel BPE tokenizer. */
class OpenJevTokenizer(file: File) {
    private val vocab = HashMap<String, Int>()
    private val ranks = HashMap<String, Int>()
    private val added = ArrayList<Pair<String, Int>>()
    private val bytes = HashMap<Int, String>()
    // Unicode White_Space = separator categories + ASCII whitespace controls + NEL.
    // Android rejects UNICODE_CHARACTER_CLASS; spell out the same GPT-2 semantics.
    private val whitespace = "\\p{Z}\\t\\n\\x0B\\f\\r\\u0085"
    private val pattern = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^${whitespace}\\p{L}\\p{N}]+|[$whitespace]+(?![^$whitespace])|[$whitespace]+")

    init {
        require(file.length() in 1..8_000_000) { "INVALID_TOKENIZER" }
        val json = JSONObject(file.readText())
        require(json.getJSONObject("normalizer").getString("type") == "NFC")
        require(json.getJSONObject("pre_tokenizer").getString("type") == "ByteLevel")
        val model = json.getJSONObject("model")
        require(model.getString("type") == "BPE")
        val v = model.getJSONObject("vocab")
        v.keys().forEach { vocab[it] = v.getInt(it) }
        val merges = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            val value = merges.get(i)
            val pair = if (value is org.json.JSONArray) value.getString(0) + "\u0000" + value.getString(1)
                       else value.toString().replaceFirst(" ", "\u0000")
            ranks[pair] = i
        }
        val tokens = json.getJSONArray("added_tokens")
        for (i in 0 until tokens.length()) {
            val token = tokens.getJSONObject(i)
            added.add(token.getString("content") to token.getInt("id"))
            vocab[token.getString("content")] = token.getInt("id")
        }
        added.sortByDescending { it.first.length }
        val native = ((33..126) + (161..172) + (174..255)).toMutableList()
        var extra = 256
        for (b in 0..255) {
            bytes[b] = if (b in native) b.toChar().toString() else (extra++).toChar().toString()
        }
        require(vocab["<<LABEL>>"] == 50368 && vocab["<<SEP>>"] == 50369)
    }

    fun encode(text: String, descriptions: List<String>): LongArray {
        require(text.length <= 256 && descriptions.size in 2..8)
        require(descriptions.all { it.length in 1..160 })
        // Reserved class markers in editor text would change output ownership. Decline them.
        require((descriptions + text).none { it.contains("<<LABEL>>") || it.contains("<<SEP>>") })
        val result = ArrayList<Long>(256)
        result.add(50281)
        for (description in descriptions) {
            result.add(50368)
            encodeText("It is $description", result)
        }
        result.add(50369)
        encodeText(text, result)
        result.add(50282)
        // Never silently truncate labels or text: that changes the decision's meaning.
        require(result.size <= 512) { "INPUT_TOO_LONG" }
        return result.toLongArray()
    }

    private fun encodeText(value: String, result: MutableList<Long>) {
        val text = Normalizer.normalize(value, Normalizer.Form.NFC)
        var start = 0
        while (start < text.length) {
            var next = text.length
            var matched: Pair<String, Int>? = null
            for (token in added) {
                val index = text.indexOf(token.first, start)
                if (index >= 0 && index < next) { next = index; matched = token }
            }
            encodeRegular(text.substring(start, next), result)
            if (matched == null) break
            result.add(matched.second.toLong())
            start = next + matched.first.length
        }
    }

    private fun encodeRegular(text: String, result: MutableList<Long>) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val symbols = matcher.group().toByteArray(Charsets.UTF_8).map { bytes[it.toInt() and 255]!! }.toMutableList()
            while (symbols.size > 1) {
                var best = Int.MAX_VALUE
                var index = -1
                for (i in 0 until symbols.size - 1) {
                    val rank = ranks[symbols[i] + "\u0000" + symbols[i + 1]] ?: continue
                    if (rank < best) { best = rank; index = i }
                }
                if (index < 0) break
                val left = symbols[index]
                val right = symbols[index + 1]
                var i = 0
                // BPE merges every non-overlapping occurrence of the selected pair at once.
                while (i < symbols.size - 1) {
                    if (symbols[i] == left && symbols[i + 1] == right) {
                        symbols[i] = left + right
                        symbols.removeAt(i + 1)
                    }
                    i++
                }
            }
            symbols.forEach { result.add((vocab[it] ?: error("INVALID_TOKENIZER")).toLong()) }
        }
    }
}
