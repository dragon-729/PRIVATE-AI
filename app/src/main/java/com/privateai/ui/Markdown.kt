package com.privateai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small, dependency-free Markdown renderer for chat answers: headings, lists, **bold**, *italic*, `code`, and fenced code blocks. */
private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val label: String, val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
}

private val HEADING = Regex("^#{1,6} +(.+)$")
private val BULLET = Regex("^[-*•] +(.+)$")
private val NUMBERED = Regex("^(\\d{1,3}[.)]) +(.+)$")
private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`\n]+)`|(?<![*\w])\*([^*\s][^*\n]*?)\*(?![*\w])""")
private val TOKEN = Regex("""(#.*|//.*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)""")

private val KEYWORDS = (
    "def class return if elif else for while in not and or import from as try except finally with lambda None True False pass " +
    "break continue yield raise global is del assert async await fun val var when object interface override private public " +
    "protected static final void int long double float boolean char String new this super null true false throw throws " +
    "extends implements package const let function typeof switch case default do enum struct sealed data open abstract"
    ).split(' ').toSet()

private class Palette(val keyword: Color, val string: Color, val comment: Color, val number: Color)
private val DARK = Palette(Color(0xFF82AAFF), Color(0xFFC3E88D), Color(0xFF7F8CA3), Color(0xFFF78C6C))
private val LIGHT = Palette(Color(0xFF1D4ED8), Color(0xFF15803D), Color(0xFF6B7280), Color(0xFFC2410C))

private fun parseBlocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = src.split("\n")
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Para(para.toString().trim())
        para.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {                       // an unclosed fence (still streaming) is treated as code to the end
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += Block.Code(lang, code.toString())
            }
            HEADING.matches(t) -> {
                flush()
                val level = t.takeWhile { it == '#' }.length
                out += Block.Heading(level, t.drop(level).trim())
            }
            BULLET.matches(t) -> { flush(); out += Block.Bullet("•", t.drop(2).trim()) }
            NUMBERED.matches(t) -> {
                flush()
                val m = NUMBERED.find(t)!!
                out += Block.Bullet(m.groupValues[1], m.groupValues[2].trim())
            }
            line.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groups[1]!!.value) }
            m.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(m.groups[2]!!.value) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groups[3]!!.value) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

private fun highlight(code: String, p: Palette): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in TOKEN.findAll(code)) {
        append(code.substring(last, m.range.first))
        val color = when {
            m.groups[1] != null -> p.comment
            m.groups[2] != null -> p.string
            m.groups[3] != null -> p.number
            m.value in KEYWORDS -> p.keyword
            else -> null
        }
        if (color != null) withStyle(SpanStyle(color = color)) { append(m.value) } else append(m.value)
        last = m.range.last + 1
    }
    append(code.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = if (dark) DARK else LIGHT
    val inlineBg = if (dark) Color(0x33FFFFFF) else Color(0x22000000)
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is Block.Para -> Text(inline(b.text, inlineBg))
                is Block.Heading -> Text(
                    inline(b.text, inlineBg),
                    style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                is Block.Bullet -> Row {
                    Text(b.label, Modifier.widthIn(min = 18.dp).padding(end = 6.dp))
                    Text(inline(b.text, inlineBg), Modifier.weight(1f))
                }
                is Block.Code -> Surface(
                    color = if (dark) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(b.lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(b.code)) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) { Text("Copy", style = MaterialTheme.typography.labelSmall) }
                        }
                        Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(
                                remember(b.code, dark) { highlight(b.code, palette) },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
