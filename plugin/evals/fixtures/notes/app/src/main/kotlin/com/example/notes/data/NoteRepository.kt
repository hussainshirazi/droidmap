package com.example.notes.data

import javax.inject.Inject

class NoteRepository @Inject constructor(private val dao: NoteDao) {
    fun notes() = dao.observeAll()
    fun note(id: Long) = dao.observe(id)
    suspend fun add(title: String, body: String) = dao.insert(Note(title = title, body = body))
}
