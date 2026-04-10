package com.whoarewe.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun insertAndRetrieveContacts() = runTest {
        val contact = TrustedContact(
            displayName = "Bob",
            publicKey = "aabb",
            totpSecret = "ccdd"
        )
        dao.insertContact(contact)

        val contacts = dao.getAllContacts().first()
        assertEquals(1, contacts.size)
        assertEquals("Bob", contacts[0].displayName)
    }

    @Test
    fun contactsReturnedInAlphabeticalOrder() = runTest {
        dao.insertContact(TrustedContact(displayName = "Zara", publicKey = "a1", totpSecret = "s1"))
        dao.insertContact(TrustedContact(displayName = "Alice", publicKey = "a2", totpSecret = "s2"))
        dao.insertContact(TrustedContact(displayName = "Bob", publicKey = "a3", totpSecret = "s3"))

        val contacts = dao.getAllContacts().first()
        assertEquals(listOf("Alice", "Bob", "Zara"), contacts.map { it.displayName })
    }

    @Test
    fun getContactByPublicKey_findsExisting() = runTest {
        dao.insertContact(TrustedContact(displayName = "Bob", publicKey = "aabb", totpSecret = "ccdd"))

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
        val contact = TrustedContact(displayName = "Bob", publicKey = "aabb", totpSecret = "ccdd")
        val id = dao.insertContact(contact)

        val inserted = dao.getContactById(id)
        assertNotNull(inserted)

        dao.deleteContact(inserted!!)
        val contacts = dao.getAllContacts().first()
        assertEquals(0, contacts.size)
    }

    @Test
    fun updateContact_changesFields() = runTest {
        val id = dao.insertContact(
            TrustedContact(displayName = "Bob", publicKey = "aabb", totpSecret = "ccdd")
        )
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
}
