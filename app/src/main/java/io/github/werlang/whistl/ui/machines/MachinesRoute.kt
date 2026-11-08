package io.github.werlang.whistl.ui.machines

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.werlang.whistl.data.ClientPublicKey
import io.github.werlang.whistl.data.MachinesRepository

/**
 * The Machines destination: the list, its card menu, the remove confirmation, the host-key sheet a card opens, and the Add
 * machine sheet while a session is open. The screen is drawn from [model]; the sheets are drawn from the same state.
 *
 * @param model the Machines view model, owned by the app so the add button can open the sheet.
 * @param machines the repository the Add machine sheet adds through.
 * @param clientKey this device's public key, for the Add machine sheet.
 * @param clipboard copies the public key.
 * @param modifier the modifier for the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesRoute(
    model: MachinesViewModel,
    machines: MachinesRepository,
    clientKey: ClientPublicKey,
    clipboard: ClipboardWriter,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    MachinesScreen(state = state, onMenu = model::openMenu, modifier = modifier)
    state.menu?.let { menu ->
        MachineMenuSheet(
            menu = menu,
            onReconnect = { model.reconnect(menu.id) },
            onRemove = { model.askRemove(menu.id) },
            onDismiss = model::closeMenu,
        )
    }
    state.confirmRemove?.let { menu ->
        RemoveMachineSheet(menu = menu, onConfirm = model::confirmRemove, onCancel = model::cancelRemove)
    }
    state.hostKey?.let { prompt ->
        HostKeySheet(
            prompt = prompt,
            onTrust = { model.trust(prompt.machineId) },
            onRemove = { model.askRemove(prompt.machineId) },
            onClose = { model.dismissHostKey(prompt) },
        )
    }
    state.addSession?.let { session ->
        AddMachineRoute(
            session = session,
            machines = machines,
            clientKey = clientKey,
            clipboard = clipboard,
            onClosed = model::closeAdd,
        )
    }
}

/**
 * One Add machine session: its own view model, keyed by the session so a new session starts with an empty form.
 *
 * @param session the session number from [MachinesUiState.addSession].
 * @param machines the repository the sheet adds through.
 * @param clientKey this device's public key.
 * @param clipboard copies the public key.
 * @param onClosed called once the sheet closes, by a button, by dismissal, or when the machine connects.
 */
@Composable
private fun AddMachineRoute(
    session: Int,
    machines: MachinesRepository,
    clientKey: ClientPublicKey,
    clipboard: ClipboardWriter,
    onClosed: () -> Unit,
) {
    val add: AddMachineViewModel = viewModel(
        key = "add-machine-$session", // i18n-ok: ViewModel store key, never shown
        factory = viewModelFactory { initializer { AddMachineViewModel(machines, clientKey, clipboard) } },
    )
    val state by add.uiState.collectAsState()
    LaunchedEffect(state.closed) {
        if (state.closed) onClosed()
    }
    AddMachineSheet(
        state = state,
        onHost = add::onHost,
        onUser = add::onUser,
        onPort = add::onPort,
        onName = add::onName,
        onAuth = add::onAuth,
        onConnect = add::connect,
        onTrust = add::trust,
        onRetry = add::retry,
        onRemove = add::removeMachine,
        onCopyKey = add::copyPublicKey,
        onClose = {
            add.close()
            onClosed()
        },
    )
}
