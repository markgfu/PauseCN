package app.pausecn.reports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.pausecn.ui.PauseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Isolated UI only: no AppContainer, records, settings or network calls. */
class ReportValidationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun revalidationKeepsSpaceAndSiblingStateButHidesPrivateTextAndTouchTargets() {
        val visible = mutableStateOf(true)
        var privateClicks = 0
        compose.setContent {
            PauseTheme {
                Column(Modifier.testTag("root")) {
                    ReportValidatedBody(visible.value) {
                        TextButton(onClick = { privateClicks++ }) { Text("私人内容") }
                        Spacer(Modifier.height(120.dp))
                    }
                    var expanded by remember { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("automatic")) {
                        Text(if (expanded) "自动设置已展开" else "自动设置")
                    }
                }
            }
        }
        val privateBounds = compose.onNodeWithText("私人内容").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("automatic").performClick()
        val before = compose.onNodeWithTag("automatic").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("私人内容").assertDoesNotExist()
        compose.onNodeWithText("自动设置已展开").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("automatic").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("root").performTouchInput { click(privateBounds.center) }
        compose.runOnIdle { assertEquals(0, privateClicks); visible.value = true }
        compose.onNodeWithText("私人内容").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, privateClicks) }
        assertEquals(before, compose.onNodeWithTag("automatic").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun generationNeedsConfirmationAndCancelOrRevalidationNeverSends() {
        val valid = mutableStateOf(true)
        var requests = 0
        compose.setContent {
            PauseTheme {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }) { Text("生成入口") }
                if (open) ReportGenerationConfirmation(valid.value, onDismiss = { open = false },
                    onConfirm = { requests++; open = false })
            }
        }
        compose.onNodeWithText("生成入口").performClick()
        compose.runOnIdle { assertEquals(0, requests); valid.value = false }
        compose.onNodeWithText("确认生成（可能计费）").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, requests); valid.value = true }
        compose.onNodeWithText("生成入口").performClick()
        compose.onNodeWithText("确认生成（可能计费）").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
