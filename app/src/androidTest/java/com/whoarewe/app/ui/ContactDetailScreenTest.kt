package com.whoarewe.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whoarewe.app.ContactWithCode
import com.whoarewe.app.data.TrustedContact
import com.whoarewe.app.ui.screens.ContactDetailScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContactDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dummyCiphertext = byteArrayOf(0, 0, 0, 0)
    private val dummyIv = byteArrayOf(0, 0, 0, 0)

    private val testItem = ContactWithCode(
        contact = TrustedContact(
            id = 1,
            displayName = "Bob",
            publicKey = "aabb",
            encryptedTotpSecret = dummyCiphertext,
            totpSecretIv = dummyIv,
            verifiedAt = 1700000000000L,
            notes = "Met at conference"
        ),
        code = "123456"
    )

    @Test
    fun displaysContactName() {
        composeTestRule.setContent {
            ContactDetailScreen(
                item = testItem,
                secondsRemaining = 120,
                onDelete = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun displaysFormattedCode() {
        composeTestRule.setContent {
            ContactDetailScreen(
                item = testItem,
                secondsRemaining = 120,
                onDelete = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithText("123 456").assertIsDisplayed()
    }

    @Test
    fun displaysNotes() {
        composeTestRule.setContent {
            ContactDetailScreen(
                item = testItem,
                secondsRemaining = 120,
                onDelete = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithText("Met at conference").assertIsDisplayed()
    }

    @Test
    fun displaysFingerprint() {
        composeTestRule.setContent {
            ContactDetailScreen(
                item = testItem,
                secondsRemaining = 120,
                onDelete = {},
                onBack = {}
            )
        }

        // SHA-256 of bytes [0xAA, 0xBB] → first 8 bytes formatted as colon-separated hex
        composeTestRule.onNodeWithText("Fingerprint").assertIsDisplayed()
    }

    @Test
    fun removeButton_showsConfirmationDialog() {
        composeTestRule.setContent {
            ContactDetailScreen(
                item = testItem,
                secondsRemaining = 120,
                onDelete = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithText("Remove contact").performClick()
        composeTestRule.onNodeWithText("Remove Bob?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun deleteConfirmation_confirm_callsCallback() {
        var deleted = false
        composeTestRule.setContent {
            ContactDetailScreen(
                item = testItem,
                secondsRemaining = 120,
                onDelete = { deleted = true },
                onBack = {}
            )
        }

        composeTestRule.onNodeWithText("Remove contact").performClick()
        composeTestRule.onNodeWithText("Remove").performClick()
        assertTrue(deleted)
    }

    @Test
    fun deleteConfirmation_cancel_dismissesDialog() {
        composeTestRule.setContent {
            ContactDetailScreen(
                item = testItem,
                secondsRemaining = 120,
                onDelete = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithText("Remove contact").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Remove Bob?").assertDoesNotExist()
    }

    @Test
    fun hidesNotes_whenBlank() {
        val itemNoNotes = testItem.copy(
            contact = testItem.contact.copy(notes = null)
        )
        composeTestRule.setContent {
            ContactDetailScreen(
                item = itemNoNotes,
                secondsRemaining = 120,
                onDelete = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithText("Notes").assertDoesNotExist()
    }
}
