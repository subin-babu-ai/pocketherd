package dev.pocketherd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowSizeClass
import dev.pocketherd.feature.agents.AgentsScreen
import dev.pocketherd.feature.machines.MachinesScreen
import dev.pocketherd.feature.pane.PaneScreen

private object Routes {
    const val AGENTS = "agents"
    const val MACHINES = "machines"
    const val PANE = "pane/{machineId}/{paneId}"
    fun pane(machineId: Long, paneId: String) = "pane/$machineId/$paneId"
}

/** A pane the user has selected, in the layout where list and pane share the screen. */
private data class Selection(val machineId: Long, val paneId: String)

@Composable
fun PocketHerdNavHost() {
    // The unfolded inner display clears the expanded breakpoint; the cover display does
    // not. Asking by breakpoint rather than by size-class enum keeps this working as the
    // large and extra-large classes get added above it.
    val twoPane = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    if (twoPane) TwoPaneApp() else SinglePaneApp()
}

@Composable
private fun SinglePaneApp() {
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
                machineId = entry.arguments?.getLong("machineId") ?: 0L,
                paneId = entry.arguments?.getString("paneId").orEmpty(),
                onBack = navController::popBackStack,
                // Replace rather than stack: walking five blocked agents should not leave
                // five panes to press back through.
                onOpenPane = { machineId, paneId ->
                    navController.navigate(Routes.pane(machineId, paneId)) {
                        popUpTo(Routes.AGENTS)
                    }
                },
            )
        }
    }
}

/**
 * List and pane side by side on a large screen.
 *
 * Deliberately not a navigation graph. On this layout the list never leaves the screen, so
 * "open a pane" is a selection rather than a destination, and modelling it as navigation
 * would put entries on a back stack that has nothing to pop back to.
 */
@Composable
private fun TwoPaneApp() {
    // Two primitives rather than a nullable data class with a custom Saver: the saver
    // buys nothing here and the primitives survive process death for free.
    var selectedMachine by rememberSaveable { mutableStateOf(-1L) }
    var selectedPane by rememberSaveable { mutableStateOf("") }
    val selection = selectedPane.takeIf { it.isNotEmpty() }
        ?.let { Selection(selectedMachine, it) }

    var listHidden by rememberSaveable { mutableStateOf(false) }
    var showMachines by rememberSaveable { mutableStateOf(false) }

    if (showMachines) {
        MachinesScreen(onBack = { showMachines = false })
        return
    }

    // Back closes the pane rather than leaving the app, matching what the list-detail
    // pattern does on a phone.
    BackHandler(enabled = selection != null || listHidden) {
        when {
            listHidden -> listHidden = false
            else -> selectedPane = ""
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (!listHidden) {
            Box(Modifier.width(360.dp)) {
                AgentsScreen(
                    onOpenPane = { machineId, paneId ->
                        selectedMachine = machineId
                        selectedPane = paneId
                    },
                    onOpenMachines = { showMachines = true },
                    selectedPaneKey = selection?.let { "${it.machineId}:${it.paneId}" },
                )
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        Box(Modifier.fillMaxWidth()) {
            val current = selection
            if (current == null) {
                AgentsEmptyDetail()
            } else {
                PaneScreen(
                    machineId = current.machineId,
                    paneId = current.paneId,
                    onBack = { selectedPane = "" },
                    onOpenPane = { machineId, paneId ->
                        selectedMachine = machineId
                        selectedPane = paneId
                    },
                    onToggleList = { listHidden = !listHidden },
                    listHidden = listHidden,
                )
            }
        }
    }
}

@Composable
private fun AgentsEmptyDetail() {
    Box(Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) {
        androidx.compose.material3.Text(
            "Pick an agent to see its terminal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
