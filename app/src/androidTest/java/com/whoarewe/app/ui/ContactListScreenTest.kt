package com.whoarewe.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whoarewe.app.ContactWithCode
import com.whoarewe.app.UiState
import com.whoarewe.app.data.Identity
import com.whoarewe.app.data.TrustedContact
import com.whoarewe.app.ui.screens.ContactListScreen
import org.junit.Rule
import org.junit.Test

class ContactListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testIdentity = Identity(displayName = "alice", publicKey = "aabb")

    @Test
    fun emptyState_showsEmptyMessage() {
        composeTestRule.setContent {
            ContactListScreen(
                state = UiState.Main(
                    identity = testIdentity,
                    fingerprint = "AB:CD:EF"
                ),
                onPair = {},
                onClearError = {}
            )
        }

        composeTestRule.onNodeWithText("No trusted contacts yet").assertIsDisplayed()
    }

    @Test
    fun withContacts_showsNamesAndCodes() {
        // Dummy ciphertext/IV bytes — this Compose test never decrypts,
        // it just renders the list of contacts with pre-formatted codes.
        val dummyCiphertext = byteArrayOf(0, 0, 0, 0)
        val dummyIv = byteArrayOf(0, 0, 0, 0)
        val contacts = listOf(
            ContactWithCode(
                contact = TrustedContact(
                    id = 1,
                    displayName = "Bob",
                    publicKey = "aabb",
                    encryptedTotpSecret = dummyCiphertext,
                    totpSecretIv = dummyIv
                ),
                code = "123456"
            ),
            ContactWithCode(
                contact = TrustedContact(
                    id = 2,
                    displayName = "Carol",
                    publicKey = "eeff",
                    encryptedTotpSecret = dummyCiphertext,
                    totpSecretIv = dummyIv
                ),
                code = "789012"
            )
        )

        composeTestRule.setContent {
            ContactListScreen(
                state = UiState.Main(
                    identity = testIdentity,
                    contacts = contacts,
                    fingerprint = "AB:CD:EF"
                ),
                onPair = {},
                onClearError = {}
            )
        }

        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
        composeTestRule.onNodeWithText("Carol").assertIsDisplayed()
        // Codes are formatted with space: "123 456"
        composeTestRule.onNodeWithText("123 456").assertIsDisplayed()
        composeTestRule.onNodeWithText("789 012").assertIsDisplayed()
    }

    @Test
    fun identityBar_showsUserInfo() {
        composeTestRule.setContent {
            ContactListScreen(
                state = UiState.Main(
                    identity = testIdentity,
                    fingerprint = "AB:CD:EF"
                ),
                onPair = {},
                onClearError = {}
            )
        }

        composeTestRule.onNodeWithText("alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("AB:CD:EF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Identity").assertIsDisplayed()
    }

    @Test
    fun pairButton_callsCallback() {
        var clicked = false
        composeTestRule.setContent {
            ContactListScreen(
                state = UiState.Main(
                    identity = testIdentity,
                    fingerprint = "AB:CD:EF"
                ),
                onPair = { clicked = true },
                onClearError = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Pair with someone").performClick()
        assert(clicked)
    }
}
