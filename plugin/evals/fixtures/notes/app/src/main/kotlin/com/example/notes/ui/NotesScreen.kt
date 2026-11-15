package com.example.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.example.notes.data.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor(repository: NoteRepository) : ViewModel() {
    val notes = repository.notes()
}

@Composable
fun NotesScreen(onOpen: (Long) -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsState(emptyList())
    LazyColumn {
        items(notes) { note -> Text(note.title, Modifier.clickable { onOpen(note.id) }) }
    }
}
