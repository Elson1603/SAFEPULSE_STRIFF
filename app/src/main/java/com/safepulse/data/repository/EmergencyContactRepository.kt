package com.safepulse.data.repository

import com.safepulse.data.db.dao.EmergencyContactDao
import com.safepulse.data.db.entity.EmergencyContactEntity
import kotlinx.coroutines.flow.Flow

class EmergencyContactRepository(private val dao: EmergencyContactDao) {
    
    fun getAllContacts(): Flow<List<EmergencyContactEntity>> = dao.getAllContacts()
    
    suspend fun getAllContactsList(): List<EmergencyContactEntity> = dao.getAllContactsList()
    
    suspend fun getPrimaryContact(): EmergencyContactEntity? = dao.getPrimaryContact()
    
    suspend fun getContactById(id: Long): EmergencyContactEntity? = dao.getContactById(id)
    
    suspend fun insert(contact: EmergencyContactEntity): Long = dao.insert(contact)
    
    suspend fun update(contact: EmergencyContactEntity) = dao.update(contact)
    
    suspend fun delete(contact: EmergencyContactEntity) = dao.delete(contact)
    
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    
    suspend fun setPrimaryContact(contactId: Long) {
        dao.clearPrimaryStatus()
        dao.getContactById(contactId)?.let { contact ->
            dao.update(contact.copy(isPrimary = true))
        }
    }
    
    suspend fun getCount(): Int = dao.getCount()
}
