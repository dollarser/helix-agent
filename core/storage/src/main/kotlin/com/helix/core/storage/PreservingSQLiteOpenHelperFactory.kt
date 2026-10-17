package com.helix.core.storage

import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Corruption is an error, never permission to delete a file. */
internal class PreservingSQLiteOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val delegate = configuration.callback
        val callback =
            object : SupportSQLiteOpenHelper.Callback(delegate.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

                override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = delegate.onUpgrade(db, oldVersion, newVersion)

                override fun onDowngrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = delegate.onDowngrade(db, oldVersion, newVersion)

                override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

                override fun onCorruption(db: SupportSQLiteDatabase): Unit =
                    throw SQLiteDatabaseCorruptException("Database corruption requires explicit recovery")
            }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(configuration.context)
                .name(configuration.name)
                .callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false)
                .build(),
        )
    }
}
