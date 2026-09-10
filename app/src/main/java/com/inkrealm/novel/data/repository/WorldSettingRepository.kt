package com.inkrealm.novel.data.repository

import com.inkrealm.novel.data.dao.WorldSettingDao
import com.inkrealm.novel.data.model.WorldSetting
import kotlinx.coroutines.flow.Flow

class WorldSettingRepository(private val worldSettingDao: WorldSettingDao) {
    fun getByWorkId(workId: Long): Flow<List<WorldSetting>> = worldSettingDao.getByWorkId(workId)
    suspend fun insert(setting: WorldSetting): Long = worldSettingDao.insert(setting)
    suspend fun update(setting: WorldSetting) = worldSettingDao.update(setting)
    suspend fun delete(setting: WorldSetting) = worldSettingDao.delete(setting)
}
