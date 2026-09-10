package com.inkrealm.novel.data.repository

import com.inkrealm.novel.data.dao.CharacterDao
import com.inkrealm.novel.data.model.Character
import kotlinx.coroutines.flow.Flow

class CharacterRepository(private val characterDao: CharacterDao) {
    fun getByWorkId(workId: Long): Flow<List<Character>> = characterDao.getByWorkId(workId)
    suspend fun insert(character: Character): Long = characterDao.insert(character)
    suspend fun update(character: Character) = characterDao.update(character)
    suspend fun delete(character: Character) = characterDao.delete(character)
}
