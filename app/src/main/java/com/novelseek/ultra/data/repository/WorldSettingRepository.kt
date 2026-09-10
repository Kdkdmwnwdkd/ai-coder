package com.novelseek.ultra.data.repository

import com.novelseek.ultra.data.dao.WorldSettingDao
import com.novelseek.ultra.data.model.WorldSetting
import kotlinx.coroutines.flow.Flow

class WorldSettingRepository(private val worldSettingDao: WorldSettingDao) {
    fun getByWorkId(workId: Long): Flow<List<WorldSetting>> = worldSettingDao.getByWorkId(workId)

    suspend fun insert(setting: WorldSetting): Long = worldSettingDao.insert(setting)

    suspend fun update(setting: WorldSetting) = worldSettingDao.update(setting)

    suspend fun delete(setting: WorldSetting) = worldSettingDao.delete(setting)
}
