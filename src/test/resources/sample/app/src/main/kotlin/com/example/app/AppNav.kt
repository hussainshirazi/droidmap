package com.example.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.feature.detail.DetailScreen
import com.example.feature.home.HomeScreen

private const val HOME = "home"
const val ARG_ID = "id"
private const val DETAIL = "detail/{$ARG_ID}"
private const val TAB_A = "tab_a"
private const val TAB_B = "tab_b"

/** Bottom-bar tabs: TAB_B is only reached through this list. */
private val TABS = listOf(TAB_B)

fun detailRoute(id: Long) = "detail/$id"

/** A navigation helper: callers pass the route. */
fun NavHostController.navigateToTab(route: String) = navigate(route) { launchSingleTop = true }

@Composable
fun AppNav(navController: NavHostController) {
    NavHost(navController = navController, startDestination = HOME) {
        composable(HOME) {
            HomeScreen(onOpen = { id -> navController.navigate(detailRoute(id)) })
        }
        composable(route = DETAIL) { DetailScreen() }
        composable("orphan") { OrphanScreen() }
        composable(TAB_A) { OrphanScreen() }
        composable(TAB_B) { OrphanScreen() }
    }
    LaunchedEffect(Unit) { navController.navigate("settings") }
    LaunchedEffect(TABS) { navController.navigateToTab(TAB_A) }
}

@Composable
fun OrphanScreen() {}
