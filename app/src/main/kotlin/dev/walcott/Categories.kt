package dev.walcott

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Fixed MVP categories. [id] is the identifier passed to the rule engine
 * ([dev.walcott.rules.FamilyConfig.assignments]); [nameRes], [color] and [icon] are
 * presentation only. Parent-defined categories are left for a later phase.
 */
enum class AppCategory(
    val id: String,
    @StringRes val nameRes: Int,
    val color: Color,
    val icon: ImageVector,
) {
    GAMES("games", R.string.category_games, Color(0xFFEF5DA8), Icons.Outlined.SportsEsports),
    SOCIAL("social", R.string.category_social, Color(0xFF7C6BF0), Icons.Outlined.Chat),
    VIDEO("video", R.string.category_video, Color(0xFFE8623D), Icons.Outlined.PlayCircle),
    EDUCATION("education", R.string.category_education, Color(0xFF2FB37A), Icons.Outlined.MenuBook),
    CREATIVE("creative", R.string.category_creative, Color(0xFFE0A83B), Icons.Outlined.Brush),
    OTHER("other", R.string.category_other, Color(0xFF6B7A8F), Icons.Outlined.Category);

    companion object {
        fun byId(id: String): AppCategory? = entries.firstOrNull { it.id == id }
    }
}
