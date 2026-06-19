package com.safepulse.data.db.dao

import androidx.room.*
import com.safepulse.data.db.entity.EmergencyContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencyContactDao {
    
    @Query("SELECT * FROM emergency_contacts ORDER BY isPrimary DESC, name ASC")
    fun getAllContacts(): Flow<List<EmergencyContactEntity>>
    
    @Query("SELECT * FROM emergency_contacts ORDER BY isPrimary DESC, name ASC")
    suspend fun getAllContactsList(): List<EmergencyContactEntity>
    
    @Query("SELECT * FROM emergency_contacts WHERE isPrimary = 1 LIMIT 1")
    suspend fun getPrimaryContact(): EmergencyContactEntity?
    
    @Query("SELECT * FROM emergency_contacts WHERE id = :id")
    suspend fun getContactById(id: Long): EmergencyContactEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: EmergencyContactEntity): Long
    
    @Update
    suspend fun update(contact: EmergencyContactEntity)
    
    @Delete
    suspend fun delete(contact: EmergencyContactEntity)
    
    @Query("DELETE FROM emergency_contacts WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("UPDATE emergency_contacts SET isPrimary = 0")
    suspend fun clearPrimaryStatus()
    
    @Query("SELECT COUNT(*) FROM emergency_contacts")
    suspend fun getCount(): Int
}
