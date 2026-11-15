package com.example.notes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.notes.data.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NoteViewModel @Inject constructor(savedStateHandle: SavedStateHandle, repository: NoteRepository) : ViewModel() {
    private val noteId: Long = checkNotNull(savedStateHandle.get<String>("noteId")).toLong()
    val note = repository.note(noteId)
}

@Composable
fun NoteScreen(viewModel: NoteViewModel = hiltViewModel()) {
    val note by viewModel.note.collectAsState(null)
    Column {
        Text(note?.title ?: "")
        Text(note?.body ?: "")
    }
}
