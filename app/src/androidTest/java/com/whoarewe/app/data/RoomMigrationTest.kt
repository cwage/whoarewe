package com.whoarewe.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Template / scaffolding for Room schema migrations (cwage/whoarewe#23).
 *
 * Until #23 this database was configured with `fallbackToDestructiveMigration()`,
 * which silently dropped every table on a schema bump and wiped the user's
 * identity + every paired contact. The product's recovery story tolerates total
 * data loss as a *visible* failure mode, but invisible loss is exactly what
 * pushes users to insecure workarounds. The destructive fallback is now gone
 * and every schema bump must ship a real `Migration` object plus a test here.
 *
 * What this file currently pins:
 *
 *   - The exported schema JSON for v3 is committed to `app/schemas/` and
 *     packaged as a test asset (build.gradle.kts wires the schema dir into
 *     `androidTest` sources). If `room.schemaLocation` regresses or the JSON
 *     stops being shipped to the test APK, [openV3Schema_succeeds] fails with
 *     `FileNotFoundException` from `MigrationTestHelper.createDatabase`.
 *
 *   - The shape of the v3 tables matches the entity definitions, by inserting
 *     a row through raw SQL on the `MigrationTestHelper` connection and then
 *     reading it back through the real Room DAO after closing and reopening.
 *     If a future entity change drifts from the schema JSON without a version
 *     bump, the DAO read here surfaces the mismatch as a Room schema-hash
 *     failure at open time.
 *
 * What this file is the template for: when v3 → v4 lands, copy
 * [openV3Schema_succeeds] into a new test that calls
 * `helper.createDatabase("…", 3)`, seeds v3 rows, then calls
 * `helper.runMigrationsAndValidate("…", 4, true, MIGRATION_3_4)` and asserts
 * the post-migration shape. The seed-and-validate skeleton is already laid
 * out below — only the migration object and the post-migration assertions
 * need to change. See CLAUDE.md "Room migrations" for the full discipline.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * v3 is currently the only schema in existence, so this test exercises
     * the *create-and-reopen* leg of the migration testing harness without
     * actually running a `Migration`. It guarantees three things future
     * contributors will rely on:
     *
     *   1. The schema export pipeline is healthy — `app/schemas/<dbFqn>/3.json`
     *      exists and is shipped as a test asset.
     *   2. The on-disk shape matches what the live DAO expects, which means
     *      the entity classes and the schema JSON have not drifted out of sync.
     *   3. Round-tripping a `TrustedContact` row written via raw SQL through
     *      the DAO returns equal bytes for both the encrypted-secret blob and
     *      its IV — pinning that the encrypted-at-rest column rename from
     *      cwage/whoarewe#32 stuck and no future bump silently undoes it.
     */
    @Test
    fun openV3Schema_succeeds() {
        val dbName = MIGRATION_TEST_DB

        // Create v3 directly via the helper. This loads `schemas/3.json`
        // from the androidTest assets — if it's missing, this call throws
        // and the test fails loudly with a "schema file not found" message,
        // which is the signal that the schema export got disabled or the
        // assets srcDir wiring broke.
        val seedSecret = byteArrayOf(0x10, 0x20, 0x30)
        val seedIv = byteArrayOf(0x40, 0x50, 0x60)
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO identity (id, displayName, publicKey, createdAt) " +
                    "VALUES (1, 'seed-user', 'aabbcc', 1700000000000)"
            )
            // Bind the byte[] columns through a prepared statement so the
            // raw bytes survive without any string-encoding round trip.
            db.compileStatement(
                "INSERT INTO trusted_contacts " +
                    "(displayName, publicKey, encryptedTotpSecret, totpSecretIv, " +
                    "verifiedAt, notes) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.bindString(1, "seed-contact")
                stmt.bindString(2, "ddeeff")
                stmt.bindBlob(3, seedSecret)
                stmt.bindBlob(4, seedIv)
                stmt.bindLong(5, 1700000001000L)
                stmt.bindNull(6)
                stmt.executeInsert()
            }
        }

        // Reopen via Room. No migrations are registered (there are none
        // yet) — the open call must succeed because the schema-on-disk
        // matches the entity classes' compiled hash. If the entities drift
        // from `schemas/3.json` without bumping the version, Room throws
        // `IllegalStateException` here with the schema-hash mismatch.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val identity = reopened.contactDao().getIdentityOnce()
                assertNotNull("seed identity must round-trip", identity)
                assertEquals("seed-user", identity!!.displayName)
                assertEquals("aabbcc", identity.publicKey)

                // The seeded contact is the only row, so its auto-generated
                // primary key is 1. If a future change starts seeding more
                // rows above this point, switch to consuming `getAllContacts`
                // via `kotlinx.coroutines.flow.first()` here.
                val contact = reopened.contactDao().getContactById(1)
                assertNotNull("seed contact must round-trip", contact)
                assertEquals("seed-contact", contact!!.displayName)
                assertEquals("ddeeff", contact.publicKey)
                assertTrue(
                    "encryptedTotpSecret bytes must round-trip",
                    contact.encryptedTotpSecret.contentEquals(seedSecret)
                )
                assertTrue(
                    "totpSecretIv bytes must round-trip",
                    contact.totpSecretIv.contentEquals(seedIv)
                )
            }
        } finally {
            reopened.close()
            // Clean the on-disk file so a re-run of the same test in
            // the same instrumentation process starts clean.
            context.deleteDatabase(dbName)
        }
    }

    private companion object {
        const val MIGRATION_TEST_DB = "migration-test.db"
    }
}
