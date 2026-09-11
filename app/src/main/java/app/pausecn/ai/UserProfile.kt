package app.pausecn.ai

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/** Explicitly saved global background, not an inferred personality or a history-derived memory. */
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val goal: String = "",
    val preferences: String = "",
    val revision: String = UUID.randomUUID().toString(),
    val updatedAtEpochMs: Long = 0,
)

/** Multiline, bounded user text; no vocabulary blacklist or silent truncation. */
object ProfileText {
    const val MAX_CODE_POINTS = 600

    fun valid(value: String, maxCodePoints: Int = MAX_CODE_POINTS): Boolean = value.codePointCount(0, value.length) <= maxCodePoints &&
        value.codePoints().noneMatch {
            (Character.isISOControl(it) && it !in setOf(9, 10, 13)) ||
                (it in 0x202A..0x202E || it in 0x2066..0x2069)
        } && value.indices.all { i ->
            when {
                value[i].isHighSurrogate() -> i + 1 < value.length && value[i + 1].isLowSurrogate()
                value[i].isLowSurrogate() -> i > 0 && value[i - 1].isHighSurrogate()
                else -> true
            }
        }

    fun normalized(value: String): String {
        require(valid(value)) { "每项背景最多600个Unicode码点，不能含异常控制字符" }
        return value.replace("\r\n", "\n").replace('\r', '\n').trim()
    }
}
