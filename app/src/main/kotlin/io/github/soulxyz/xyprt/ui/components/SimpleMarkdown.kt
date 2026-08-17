package io.github.soulxyz.xyprt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Small, dependency-free Markdown renderer for server-authored product copy.
 *
 * Supported intentionally: headings, bullets, numbered items, paragraphs, **bold** and `code`.
 * We keep the grammar deliberately non-executable: no HTML, JavaScript or embedded remote content.
 */
@Composable
fun SimpleMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = markdown.replace("\r\n", "\n").lines()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("### ") -> Text(
                    inlineMarkdown(line.removePrefix("### ").trim()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                line.startsWith("## ") -> Text(
                    inlineMarkdown(line.removePrefix("## ").trim()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                line.startsWith("# ") -> Text(
                    inlineMarkdown(line.removePrefix("# ").trim()),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                line.startsWith("- ") || line.startsWith("* ") -> MarkdownBullet(line.drop(2).trim())
                Regex("^\\d+[.)]\\s+.*").matches(line) -> {
                    val marker = line.substringBefore(' ') + " "
                    val body = line.substringAfter(' ').trim()
                    Row(Modifier.fillMaxWidth()) {
                        Text(marker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(inlineMarkdown(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                else -> Text(
                    inlineMarkdown(line.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MarkdownBullet(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("•", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(inlineMarkdown(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append('`')
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}
