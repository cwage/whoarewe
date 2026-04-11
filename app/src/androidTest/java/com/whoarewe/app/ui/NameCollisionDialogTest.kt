package com.whoarewe.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whoarewe.app.ui.screens.NameCollisionDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pins the three-way dialog surface for cwage/whoarewe#33. These tests exist
 * to make sure a refactor of the dialog (button labels, slot placement,
 * accidental callback swap) fires loudly rather than silently rewiring
 * "Replace" to cancel the operation or vice versa — each button maps to a
 * code path with a very different security outcome, so crossing the wires
 * is exactly the kind of bug this test is here to catch.
 */
class NameCollisionDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setDialog(
        existingName: String = "Alice",
        onReplace: () -> Unit = {},
        onAddAsSecond: () -> Unit = {},
        onCancel: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            NameCollisionDialog(
                existingName = existingName,
                onReplace = onReplace,
                onAddAsSecond = onAddAsSecond,
                onCancel = onCancel
            )
        }
    }

    @Test
    fun dialog_showsHeadlineBodyAndThreeButtons() {
        setDialog(existingName = "Alice")

        composeTestRule.onNodeWithText("\"Alice\" already exists").assertIsDisplayed()
        // Body copy names the impersonation risk explicitly — we deliberately
        // do not want to soften this into a generic warning.
        composeTestRule.onNodeWithText(
            "If you did not expect this, choose Cancel — the QR may " +
                "be an impersonation attempt."
        ).assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Replace existing Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add as a second Alice").assertIsDisplayed()
    }

    @Test
    fun cancelButton_invokesOnlyCancel() {
        val calls = mutableListOf<String>()
        setDialog(
            onReplace = { calls += "replace" },
            onAddAsSecond = { calls += "add" },
            onCancel = { calls += "cancel" }
        )

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun replaceButton_invokesOnlyReplace() {
        // Critical correctness check: tapping Replace must NOT silently
        // also call Cancel or Add — a callback-swap bug would make the
        // "Replace" label hit the Add path, which would leave the victim
        // with two Alices and believe they replaced the old one.
        val calls = mutableListOf<String>()
        setDialog(
            onReplace = { calls += "replace" },
            onAddAsSecond = { calls += "add" },
            onCancel = { calls += "cancel" }
        )

        composeTestRule.onNodeWithText("Replace existing Alice").performClick()

        assertEquals(listOf("replace"), calls)
    }

    @Test
    fun addAsSecondButton_invokesOnlyAddAsSecond() {
        val calls = mutableListOf<String>()
        setDialog(
            onReplace = { calls += "replace" },
            onAddAsSecond = { calls += "add" },
            onCancel = { calls += "cancel" }
        )

        composeTestRule.onNodeWithText("Add as a second Alice").performClick()

        assertEquals(listOf("add"), calls)
    }

    @Test
    fun existingName_isInterpolatedIntoEveryLabel() {
        // Sanity check: if someone passes a non-trivial display name, the
        // dialog copy reflects it everywhere instead of hard-coding "Alice".
        setDialog(existingName = "Åsa Bäck")

        composeTestRule.onNodeWithText("\"Åsa Bäck\" already exists").assertIsDisplayed()
        composeTestRule.onNodeWithText("Replace existing Åsa Bäck").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add as a second Åsa Bäck").assertIsDisplayed()
    }

}
