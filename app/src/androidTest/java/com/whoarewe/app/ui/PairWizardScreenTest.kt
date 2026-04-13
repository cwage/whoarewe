package com.whoarewe.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whoarewe.app.ui.screens.AddContactScreen
import com.whoarewe.app.ui.screens.PairStep
import org.junit.Rule
import org.junit.Test

class PairWizardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setScreen(
        step: PairStep,
        onScanCamera: () -> Unit = {},
        onScanImage: () -> Unit = {},
        onDone: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AddContactScreen(
                step = step,
                error = null,
                onScanCamera = onScanCamera,
                onScanImage = onScanImage,
                onDone = onDone,
                onBack = onBack,
                onClearError = {}
            )
        }
    }

    @Test
    fun scanStep_showsScanOptions() {
        setScreen(step = PairStep.Scan)

        composeTestRule.onNodeWithText("Scan a contact's QR code").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan with camera").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import from image").assertIsDisplayed()
    }

    @Test
    fun scanStep_cameraCallsCallback() {
        var clicked = false
        setScreen(
            step = PairStep.Scan,
            onScanCamera = { clicked = true }
        )

        composeTestRule.onNodeWithText("Scan with camera").performClick()
        assert(clicked)
    }

    @Test
    fun scanStep_importCallsCallback() {
        var clicked = false
        setScreen(
            step = PairStep.Scan,
            onScanImage = { clicked = true }
        )

        composeTestRule.onNodeWithText("Import from image").performClick()
        assert(clicked)
    }

    @Test
    fun doneStep_showsSuccessMessage() {
        setScreen(step = PairStep.Done(addedName = "Bob"))

        composeTestRule.onNodeWithText("You and Bob are paired!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to contacts").assertIsDisplayed()
    }

    @Test
    fun doneStep_backToContactsCallsCallback() {
        var clicked = false
        setScreen(
            step = PairStep.Done(addedName = "Bob"),
            onDone = { clicked = true }
        )

        composeTestRule.onNodeWithText("Back to contacts").performClick()
        assert(clicked)
    }
}
