package com.example.notes.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun NotesNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "notes") {
        composable("notes") { NotesScreen(onOpen = { id -> navController.navigate("note/$id") }) }
        composable("note/{noteId}") { NoteScreen() }
    }
}
