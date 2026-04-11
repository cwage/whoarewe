package com.whoarewe.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.contactDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun saveAndRetrieveIdentity() = runTest {
        val identity = Identity(displayName = "alice", publicKey = "aabb")
        dao.saveIdentity(identity)

        val retrieved = dao.getIdentityOnce()
        assertNotNull(retrieved)
        assertEquals("alice", retrieved!!.displayName)
        assertEquals("aabb", retrieved.publicKey)
    }

    @Test
    fun noIdentity_returnsNull() = runTest {
        val result = dao.getIdentityOnce()
        assertNull(result)
    }

    // Deterministic ciphertext/IV helpers. These tests are DAO-level —
    // they don't care about cryptographic correctness, just that the row
    // round-trips. Each tag yields distinct-but-stable bytes so an
    // assertion can compare "this row has the `oldsecret` bytes" without
    // running AES at all.
    private fun ct(tag: String): ByteArray = "ct:$tag".toByteArray()
    private fun iv(tag: String): ByteArray = "iv:$tag".toByteArray()

    private fun contact(
        name: String,
        publicKey: String,
        secretTag: String = name,
        notes: String? = null
    ) = TrustedContact(
        displayName = name,
        publicKey = publicKey,
        encryptedTotpSecret = ct(secretTag),
        totpSecretIv = iv(secretTag),
        notes = notes
    )

    @Test
    fun insertAndRetrieveContacts() = runTest {
        dao.insertContact(contact(name = "Bob", publicKey = "aabb"))

        val contacts = dao.getAllContacts().first()
        assertEquals(1, contacts.size)
        assertEquals("Bob", contacts[0].displayName)
    }

    @Test
    fun contactsReturnedInAlphabeticalOrder() = runTest {
        dao.insertContact(contact(name = "Zara", publicKey = "a1"))
        dao.insertContact(contact(name = "Alice", publicKey = "a2"))
        dao.insertContact(contact(name = "Bob", publicKey = "a3"))

        val contacts = dao.getAllContacts().first()
        assertEquals(listOf("Alice", "Bob", "Zara"), contacts.map { it.displayName })
    }

    @Test
    fun getContactByPublicKey_findsExisting() = runTest {
        dao.insertContact(contact(name = "Bob", publicKey = "aabb"))

        val found = dao.getContactByPublicKey("aabb")
        assertNotNull(found)
        assertEquals("Bob", found!!.displayName)
    }

    @Test
    fun getContactByPublicKey_returnsNullForMissing() = runTest {
        val found = dao.getContactByPublicKey("nonexistent")
        assertNull(found)
    }

    @Test
    fun deleteContact_removesIt() = runTest {
        val id = dao.insertContact(contact(name = "Bob", publicKey = "aabb"))

        val inserted = dao.getContactById(id)
        assertNotNull(inserted)

        dao.deleteContact(inserted!!)
        val contacts = dao.getAllContacts().first()
        assertEquals(0, contacts.size)
    }

    @Test
    fun updateContact_changesFields() = runTest {
        val id = dao.insertContact(contact(name = "Bob", publicKey = "aabb"))
        val original = dao.getContactById(id)!!
        dao.updateContact(original.copy(displayName = "Robert", notes = "Updated"))

        val updated = dao.getContactById(id)!!
        assertEquals("Robert", updated.displayName)
        assertEquals("Updated", updated.notes)
    }

    @Test
    fun saveIdentity_replacesOnConflict() = runTest {
        dao.saveIdentity(Identity(displayName = "alice", publicKey = "aabb"))
        dao.saveIdentity(Identity(displayName = "alice_updated", publicKey = "ccdd"))

        val identity = dao.getIdentityOnce()
        assertEquals("alice_updated", identity!!.displayName)
    }

    // ── replaceContact (cwage/whoarewe#33) ──

    @Test
    fun replaceContact_swapsTheTargetedRowOnly() = runTest {
        // Two contacts exist — "Alice" (which we will replace) and "Bob"
        // (which must be left completely untouched). The assertion covers
        // two invariants of the key-change warning's Replace path:
        //   1. The old Alice row is gone.
        //   2. The new Alice row carries the new key and secret.
        //   3. The unrelated Bob row is not affected.
        val oldAliceId = dao.insertContact(
            contact(name = "Alice", publicKey = "oldkey", secretTag = "oldalice")
        )
        val bobId = dao.insertContact(
            contact(name = "Bob", publicKey = "bobkey", secretTag = "bob")
        )

        dao.replaceContact(
            oldAliceId,
            contact(name = "Alice", publicKey = "newkey", secretTag = "newalice")
        )

        val all = dao.getAllContacts().first()
        assertEquals(2, all.size)

        // The old id is gone — the new row has a fresh auto-generated id.
        assertNull(dao.getContactById(oldAliceId))

        val newAlice = all.first { it.displayName == "Alice" }
        assertEquals("newkey", newAlice.publicKey)
        assertArrayEquals(ct("newalice"), newAlice.encryptedTotpSecret)
        assertArrayEquals(iv("newalice"), newAlice.totpSecretIv)
        // Use assertNotEquals rather than Kotlin/Java `assert(...)` because
        // JVM assertions are compiled in but disabled at runtime unless the
        // instrumentation runner is launched with `-ea`, and our runner
        // isn't — so `assert(...)` would silently become a no-op and this
        // "the new row has a fresh auto-generated id" invariant would be
        // unenforced. See Copilot round 1 on PR #37.
        assertNotEquals(
            "replaceContact must insert a brand-new row, not reuse the old id",
            oldAliceId,
            newAlice.id
        )

        // Bob is untouched.
        val bob = dao.getContactById(bobId)
        assertNotNull(bob)
        assertEquals("bobkey", bob!!.publicKey)
        assertArrayEquals(ct("bob"), bob.encryptedTotpSecret)
        assertArrayEquals(iv("bob"), bob.totpSecretIv)
    }

    @Test
    fun replaceContact_missingOldIdStillInsertsNewRow() = runTest {
        // Defensive: if the caller somehow hands in a stale id (e.g. the
        // target was deleted between the UI showing the collision dialog
        // and the user tapping Replace), the @Transaction should still
        // leave the DB in a sensible state — the new row ends up inserted
        // and nothing else is touched. The delete is a no-op in that case.
        dao.replaceContact(
            oldId = 999L,
            replacement = contact(name = "Alice", publicKey = "newkey", secretTag = "newalice")
        )

        val all = dao.getAllContacts().first()
        assertEquals(1, all.size)
        assertEquals("Alice", all[0].displayName)
        assertEquals("newkey", all[0].publicKey)
    }
}
