package cn.net.rms.chatroom.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.api.ChangelogEntry
import cn.net.rms.chatroom.data.api.ReleaseChangelog
import cn.net.rms.chatroom.data.local.AppLanguage
import cn.net.rms.chatroom.data.local.AppLocale
import cn.net.rms.chatroom.ui.theme.Zhimo
import java.util.Locale

/**
 * Flatten every release inside (fromCode, toCode] into one merged view so a
 * user who skipped versions sees all of their changes in a single dialog.
 * `history` is newest-first; entries render oldest -> newest with duplicates
 * (same English text) removed. Returns the newest release unchanged when
 * there is nothing to merge (fresh installs, unknown fromCode).
 */
fun mergeChangelog(
    history: List<ReleaseChangelog>,
    fromCode: Int,
    toCode: Int
): ReleaseChangelog {
    val newest = history.first()
    val window = history.filter { it.code > fromCode && it.code <= toCode }
        .reversed() // oldest -> newest
    if (window.isEmpty()) return newest
    val improvements = mutableListOf<ChangelogEntry>()
    val fixes = mutableListOf<ChangelogEntry>()
    fun add(list: MutableList<ChangelogEntry>, entry: ChangelogEntry) {
        if (list.none { it.en == entry.en }) list += entry
    }
    for (release in window) {
        release.improvements.forEach { add(improvements, it) }
        release.fixes.forEach { add(fixes, it) }
    }
    return newest.copy(improvements = improvements, fixes = fixes)
}

/**
 * Bilingual changelog rendering shared by the update-available dialog, the
 * manual check dialog, and the first-launch-after-update dialog. Entries
 * ship both languages; the active locale's half is shown with the other as
 * fallback.
 */
@Composable
fun ChangelogList(
    changelog: ReleaseChangelog,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Read tick so an in-place locale switch re-resolves the entries.
    AppLocale.tick.longValue
    val preferZh = when (AppLocale.current(context)) {
        AppLanguage.ZH -> true
        AppLanguage.EN -> false
        AppLanguage.SYSTEM -> Locale.getDefault().language.startsWith("zh")
    }
    val pick: (ChangelogEntry) -> String = { entry ->
        if (preferZh) entry.zh.ifEmpty { entry.en } else entry.en.ifEmpty { entry.zh }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (changelog.improvements.isNotEmpty()) {
            SectionHeader(stringResource(R.string.changelog_new))
            changelog.improvements.forEach { Bullet(pick(it)) }
            if (changelog.fixes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        if (changelog.fixes.isNotEmpty()) {
            SectionHeader(stringResource(R.string.changelog_fixes))
            changelog.fixes.forEach { Bullet(pick(it)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = Zhimo.ink
    )
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = Zhimo.ink
    )
    Spacer(modifier = Modifier.height(2.dp))
}
