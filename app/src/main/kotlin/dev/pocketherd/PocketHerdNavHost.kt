package dev.pocketherd

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pocketherd.feature.agents.AgentsScreen
import dev.pocketherd.feature.machines.MachinesScreen
import dev.pocketherd.feature.pane.PaneScreen

private object Routes {
    const val AGENTS = "agents"
    const val MACHINES = "machines"
    const val PANE = "pane/{machineId}/{paneId}"
    fun pane(machineId: Long, paneId: String) = "pane/$machineId/$paneId"
}

@Composable
fun PocketHerdNavHost() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = Routes.AGENTS) {
        composable(Routes.AGENTS) {
            AgentsScreen(
                onOpenPane = { machineId, paneId ->
                    navController.navigate(Routes.pane(machineId, paneId))
                },
                onOpenMachines = { navController.navigate(Routes.MACHINES) },
            )
        }

        composable(Routes.MACHINES) {
            MachinesScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.PANE,
            arguments = listOf(
                navArgument("machineId") { type = NavType.LongType },
                navArgument("paneId") { type = NavType.StringType },
            ),
        ) { entry ->
            PaneScreen(
                onBack = navController::popBackStack,
                // Replace rather than stack: walking five blocked agents should not leave
                // five panes on the back stack to press back through.
                onOpenPane = { machineId, paneId ->
                    navController.navigate(Routes.pane(machineId, paneId)) {
                        popUpTo(Routes.AGENTS)
                    }
                },
            )
        }
    }
}
