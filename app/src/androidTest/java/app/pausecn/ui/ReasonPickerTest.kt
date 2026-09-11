package app.pausecn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReasonPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionWaitsForConfirmationAndSupportsPresetNotesOrFreeInput() {
        val confirmed = mutableListOf<String>()
        composeRule.setContent {
            var generation by remember { mutableIntStateOf(0) }
            PauseTheme {
                key(generation) {
                    ReasonPicker(choices = listOf("搜资料", "历史自定义")) { reason ->
                        confirmed += reason
                        generation += 1
                    }
                }
            }
        }

        composeRule.onNodeWithText("搜资料").performClick()
        composeRule.runOnIdle { assertTrue(confirmed.isEmpty()) }
        composeRule.onNodeWithText("✓ 搜资料").assertIsDisplayed()
        composeRule.onNodeWithTag("continue_reason_input").performTextInput("查会议地点")
        composeRule.onNodeWithText("✓ 搜资料").performClick()
        composeRule.onNodeWithText("搜资料").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_continue_reason").performClick()
        composeRule.runOnIdle { assertEquals(listOf("查会议地点"), confirmed) }

        composeRule.onNodeWithText("搜资料").performClick()
        composeRule.onNodeWithTag("continue_reason_input").performTextInput("补充说明")
        composeRule.onNodeWithTag("confirm_continue_reason").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("查会议地点", "搜资料 · 补充说明"), confirmed)
        }
    }
}
