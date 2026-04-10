package com.whoami.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.whoami.app.UiState
import com.whoami.app.ui.screens.SetupScreen
import org.junit.Rule
import org.junit.Test

class SetupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun initialState_showsTitleAndDisabledButton() {
        composeTestRule.setContent {
            SetupScreen(
                state = UiState.Setup(),
                onDisplayNameChanged = {},
                onGenerateIdentity = {}
            )
        }

        composeTestRule.onNodeWithText("WhoAmI").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create Identity").assertIsNotEnabled()
    }

    @Test
    fun enteringName_enablesButton() {
        var capturedName = ""
        composeTestRule.setContent {
            SetupScreen(
                state = UiState.Setup(displayName = "alice"),
                onDisplayNameChanged = { capturedName = it },
                onGenerateIdentity = {}
            )
        }

        composeTestRule.onNodeWithText("Create Identity").assertIsEnabled()
    }

    @Test
    fun clickingCreateIdentity_callsCallback() {
        var clicked = false
        composeTestRule.setContent {
            SetupScreen(
                state = UiState.Setup(displayName = "alice"),
                onDisplayNameChanged = {},
                onGenerateIdentity = { clicked = true }
            )
        }

        composeTestRule.onNodeWithText("Create Identity").performClick()
        assert(clicked)
    }

    @Test
    fun errorState_displaysErrorMessage() {
        composeTestRule.setContent {
            SetupScreen(
                state = UiState.Setup(error = "Please set up a screen lock"),
                onDisplayNameChanged = {},
                onGenerateIdentity = {}
            )
        }

        composeTestRule.onNodeWithText("Please set up a screen lock").assertIsDisplayed()
    }

    @Test
    fun generatingState_showsProgressAndDisablesInput() {
        composeTestRule.setContent {
            SetupScreen(
                state = UiState.Setup(displayName = "alice", isGenerating = true),
                onDisplayNameChanged = {},
                onGenerateIdentity = {}
            )
        }

        composeTestRule.onNodeWithText("Create Identity").assertDoesNotExist()
    }
}
