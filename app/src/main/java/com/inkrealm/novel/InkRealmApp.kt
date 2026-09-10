package com.inkrealm.novel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inkrealm.novel.ui.screen.*

@Composable
fun InkRealmApp() {
    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableStateOf("long") }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                selectedTab = selectedTab,
                onTabChange = { selectedTab = it },
                onWorkClick = { navController.navigate("work/$it") },
                onAgentClick = { navController.navigate("agent") },
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable(
            "work/{workId}",
            arguments = listOf(navArgument("workId") { type = NavType.LongType })
        ) { backStackEntry ->
            val workId = backStackEntry.arguments?.getLong("workId") ?: 0L
            WorkDetailScreen(
                workId = workId,
                onBack = { navController.popBackStack() },
                onChapterClick = { id -> navController.navigate("chapter/$id/$workId") },
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable(
            "chapter/{chapterId}/{workId}",
            arguments = listOf(
                navArgument("chapterId") { type = NavType.LongType },
                navArgument("workId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val chapterId = backStackEntry.arguments?.getLong("chapterId") ?: 0L
            val workId = backStackEntry.arguments?.getLong("workId") ?: 0L
            ChapterEditScreen(
                chapterId = chapterId,
                workId = workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("agent") {
            AgentScreen(onBack = { navController.popBackStack() })
        }
    }
}
