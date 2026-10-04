package app.bildfang

/**
 * Minimal strict JSON parser for unit tests (no JSON library on the
 * plain-JVM test classpath). Throws on any syntax error; numbers become
 * Long/Double, strings String, objects LinkedHashMap, arrays ArrayList,
 * literals true/false/null.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != p.s.length) throw IllegalArgumentException("trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                'n' -> { lit("null"); null }
                else -> num()
            }
        }

        fun lit(t: String) {
            if (!s.startsWith(t, i)) throw IllegalArgumentException("bad literal at $i")
            i += t.length
        }

        fun obj(): LinkedHashMap<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                if (s[i] != ':') throw IllegalArgumentException("expected : at $i")
                i++
                m[k] = value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("expected , or } at $i")
                }
            }
        }

        fun arr(): ArrayList<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("expected , or ] at $i")
                }
            }
        }

        fun str(): String {
            if (s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i]
                if (c == '"') { i++; return sb.toString() }
                if (c == '\\') {
                    i++
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C'.toChar())
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(Integer.parseInt(s.substring(i + 1, i + 5), 16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape $e at $i")
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') { isDouble = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val t = s.substring(start, i)
            return if (isDouble) t.toDouble() else t.toLong()
        }
    }
}
