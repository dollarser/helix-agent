package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionPermissionDefaultsEntity

/**
 * The single-row app default session permission mode (HXA-209). Written only through
 * [com.helix.core.storage.repository.SessionPermissionConfigRepository]; the row id is always
 * [SessionPermissionDefaultsEntity.DEFAULTS_ROW_ID].
 */
@Dao
interface SessionPermissionDefaultsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: SessionPermissionDefaultsEntity)

    @Query("SELECT * FROM session_permission_defaults WHERE id = :id")
    fun byId(id: String): SessionPermissionDefaultsEntity?
}
