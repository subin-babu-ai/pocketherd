package dev.pocketherd.domain

import dev.pocketherd.core.model.Machine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class MachineStatus(val machine: Machine, val state: ConnectionState)

/**
 * Every saved machine paired with its live connection state.
 *
 * The agent list alone cannot explain an empty screen: a machine that is refusing to
 * connect looks identical to one with no agents running. This is what tells them apart.
 */
class ObserveMachineStatusUseCase @Inject constructor(
    private val machines: MachineRepository,
    private val herdr: HerdrRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<MachineStatus>> =
        machines.observeMachines().flatMapLatest { saved ->
            if (saved.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    saved.map { machine ->
                        herdr.observeConnection(machine.id).map { MachineStatus(machine, it) }
                    },
                ) { it.toList() }
            }
        }
}

/** The first machine waiting on a host key decision, if any. At most one prompt at a time. */
fun List<MachineStatus>.pendingHostKey(): MachineStatus? =
    firstOrNull { it.state is ConnectionState.NeedsHostKeyApproval }

/** Machines that stopped for a reason the user has to see, excluding ordinary retries. */
fun List<MachineStatus>.blockedMachines(): List<MachineStatus> =
    filter { it.state is ConnectionState.HostKeyChanged || it.state is ConnectionState.Failed }
