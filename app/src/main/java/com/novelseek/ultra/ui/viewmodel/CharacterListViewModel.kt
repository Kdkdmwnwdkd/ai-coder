package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.AppDatabase
import com.novelseek.ultra.data.model.Character
import com.novelseek.ultra.data.repository.CharacterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class CharacterListViewModel(application: Application, private val workId: Long) : AndroidViewModel(application) {
    private val repository: CharacterRepository
    val characters: Flow<List<Character>>

    init {
        val db = AppDatabase.getDatabase(application)
        repository = CharacterRepository(db.characterDao())
        characters = repository.getByWorkId(workId)
    }

    fun addCharacter(name: String, description: String, role: String) {
        viewModelScope.launch {
            repository.insert(Character(workId = workId, name = name, description = description, role = role))
        }
    }

    fun updateCharacter(character: Character) {
        viewModelScope.launch {
            repository.update(character)
        }
    }

    fun deleteCharacter(character: Character) {
        viewModelScope.launch {
            repository.delete(character)
        }
    }
}
