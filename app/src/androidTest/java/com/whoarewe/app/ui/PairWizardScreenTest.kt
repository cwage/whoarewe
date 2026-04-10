package com.whoarewe.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whoarewe.app.ui.screens.PairStep
import com.whoarewe.app.ui.screens.PairWizardScreen
import org.junit.Rule
import org.junit.Test

class PairWizardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testKey = ByteArray(32) { it.toByte() }

    private fun setWizard(
        step: PairStep,
        onScanCamera: () -> Unit = {},
        onScanImage: () -> Unit = {},
        onShowFirst: () -> Unit = {},
        onReadyToScan: () -> Unit = {},
        onDone: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PairWizardScreen(
                step = step,
                displayName = "alice",
                publicKey = testKey,
                fingerprint = "AB:CD:EF",
                error = null,
                onScanCamera = onScanCamera,
                onScanImage = onScanImage,
                onShowFirst = onShowFirst,
                onReadyToScan = onReadyToScan,
                onDone = onDone,
                onBack = onBack,
                onClearError = {}
            )
        }
    }

    @Test
    fun chooseStep_showsAllOptions() {
        setWizard(step = PairStep.Choose)

        composeTestRule.onNodeWithText("Scan their code first").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import QR from image").assertIsDisplayed()
        composeTestRule.onNodeWithText("Show my code first").assertIsDisplayed()
    }

    @Test
    fun chooseStep_scanFirstCallsCallback() {
        var clicked = false
        setWizard(
            step = PairStep.Choose,
            onScanCamera = { clicked = true }
        )

        composeTestRule.onNodeWithText("Scan their code first").performClick()
        assert(clicked)
    }

    @Test
    fun chooseStep_showFirstCallsCallback() {
        var clicked = false
        setWizard(
            step = PairStep.Choose,
            onShowFirst = { clicked = true }
        )

        composeTestRule.onNodeWithText("Show my code first").performClick()
        assert(clicked)
    }

    @Test
    fun showFirstStep_showsQrAndNextButton() {
        setWizard(step = PairStep.ShowFirst)

        composeTestRule.onNodeWithText("alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("AB:CD:EF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next: Scan their code").assertIsDisplayed()
    }

    @Test
    fun showFirstStep_nextCallsReadyToScan() {
        var clicked = false
        setWizard(
            step = PairStep.ShowFirst,
            onReadyToScan = { clicked = true }
        )

        composeTestRule.onNodeWithText("Next: Scan their code").performClick()
        assert(clicked)
    }

    @Test
    fun scanAfterShowStep_showsScanOptions() {
        setWizard(step = PairStep.ScanAfterShow)

        composeTestRule.onNodeWithText("Now scan their QR code").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan with camera").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import from image").assertIsDisplayed()
    }

    @Test
    fun showAfterScanStep_showsAddedNameAndDoneButton() {
        setWizard(step = PairStep.ShowAfterScan(addedName = "Bob"))

        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onNodeWithText("alice").assertIsDisplayed()
    }

    @Test
    fun doneStep_showsSuccessMessage() {
        setWizard(step = PairStep.Done(addedName = "Bob"))

        composeTestRule.onNodeWithText("You and Bob are paired!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to contacts").assertIsDisplayed()
    }

    @Test
    fun doneStep_backToContactsCallsCallback() {
        var clicked = false
        setWizard(
            step = PairStep.Done(addedName = "Bob"),
            onDone = { clicked = true }
        )

        composeTestRule.onNodeWithText("Back to contacts").performClick()
        assert(clicked)
    }
}
