package com.example.feature.detail

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object ProfileKey : NavKey

@Serializable
data class MissingKey(val id: String) : NavKey

class Navigator {
    fun navigate(key: NavKey) {}
}

fun Navigator.openProfile() = navigate(ProfileKey)

fun EntryProviderScope<NavKey>.profileEntry(navigator: Navigator) {
    entry<ProfileKey> {
        ProfileScreen(onBack = { navigator.navigate(MissingKey("x")) })
    }
}

@Composable
fun ProfileScreen(onBack: () -> Unit) {}

@Composable
fun DetailScreen() {}
