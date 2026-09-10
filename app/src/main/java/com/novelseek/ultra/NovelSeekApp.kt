package com.novelseek.ultra

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.novelseek.ultra.ui.screen.*
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
data class WorkDetailRoute(val workId: Long)

@Serializable
data class ChapterEditRoute(val chapterId: Long, val workId: Long)

@Serializable
data class CharacterListRoute(val workId: Long)

@Serializable
data class WorldListRoute(val workId: Long)

@Serializable
data class OutlineEditRoute(val workId: Long)

@Serializable
object SettingsRoute

@Serializable
data class AgentConfigRoute(val workId: Long)

@Composable
fun NovelSeekApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onWorkClick = { navController.navigate(WorkDetailRoute(it)) },
                onSettingsClick = { navController.navigate(SettingsRoute) }
            )
        }
        composable<WorkDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<WorkDetailRoute>()
            WorkDetailScreen(
                workId = route.workId,
                onBack = { navController.popBackStack() },
                onChapterClick = { chapterId ->
                    navController.navigate(ChapterEditRoute(chapterId, route.workId))
                },
                onCharactersClick = { navController.navigate(CharacterListRoute(route.workId)) },
                onWorldClick = { navController.navigate(WorldListRoute(route.workId)) },
                onOutlineClick = { navController.navigate(OutlineEditRoute(route.workId)) },
                onAgentConfigClick = { navController.navigate(AgentConfigRoute(route.workId)) }
            )
        }
        composable<ChapterEditRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ChapterEditRoute>()
            ChapterEditScreen(
                chapterId = route.chapterId,
                workId = route.workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable<CharacterListRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CharacterListRoute>()
            CharacterListScreen(
                workId = route.workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable<WorldListRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<WorldListRoute>()
            WorldListScreen(
                workId = route.workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable<OutlineEditRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<OutlineEditRoute>()
            OutlineEditScreen(
                workId = route.workId,
                onBack = { navController.popBackStack() }
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<AgentConfigRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AgentConfigRoute>()
            AgentConfigScreen(
                workId = route.workId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
