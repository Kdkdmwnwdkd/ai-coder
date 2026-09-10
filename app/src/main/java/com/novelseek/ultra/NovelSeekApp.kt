package com.novelseek.ultra

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novelseek.ultra.ui.screen.*

@Composable
fun NovelSeekApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onWorkClick = { navController.navigate("work_detail/$it") },
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable(
            "work_detail/{workId}",
            arguments = listOf(navArgument("workId") { type = NavType.LongType })
        ) { backStackEntry ->
            val workId = backStackEntry.arguments?.getLong("workId") ?: 0L
            WorkDetailScreen(
                workId = workId,
                onBack = { navController.popBackStack() },
                onChapterClick = { chapterId ->
                    navController.navigate("chapter_edit/$chapterId/$workId")
                },
                onCharactersClick = { navController.navigate("characters/$workId") },
                onWorldClick = { navController.navigate("world/$workId") },
                onOutlineClick = { navController.navigate("outline/$workId") },
                onAgentConfigClick = { navController.navigate("agent/$workId") }
            )
        }
        composable(
            "chapter_edit/{chapterId}/{workId}",
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
        composable(
            "characters/{workId}",
            arguments = listOf(navArgument("workId") { type = NavType.LongType })
        ) { backStackEntry ->
            val workId = backStackEntry.arguments?.getLong("workId") ?: 0L
            CharacterListScreen(
                workId = workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "world/{workId}",
            arguments = listOf(navArgument("workId") { type = NavType.LongType })
        ) { backStackEntry ->
            val workId = backStackEntry.arguments?.getLong("workId") ?: 0L
            WorldListScreen(
                workId = workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "outline/{workId}",
            arguments = listOf(navArgument("workId") { type = NavType.LongType })
        ) { backStackEntry ->
            val workId = backStackEntry.arguments?.getLong("workId") ?: 0L
            OutlineEditScreen(
                workId = workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            "agent/{workId}",
            arguments = listOf(navArgument("workId") { type = NavType.LongType })
        ) { backStackEntry ->
            val workId = backStackEntry.arguments?.getLong("workId") ?: 0L
            AgentConfigScreen(
                workId = workId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
