package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.app.projects.ProjectRecords
import kotlinx.coroutines.CancellationException

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught", "SwallowedException")
internal fun ProjectHistory(
    container: AppContainer,
    members: Set<String>,
    tab: Int,
    onOpenSession: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenCommand: (String, String) -> Unit,
) {
    val service = requireNotNull(container.projects)
    val tasks by container.chatService.backgroundTasks.collectAsStateWithLifecycle()
    val files by container.chatService.artifactFiles.collectAsStateWithLifecycle()
    var revision by remember { mutableStateOf(0) }
    val result by produceState<Result<ProjectRecords>?>(null, members, tasks, files, revision) {
        value =
            try {
                Result.success(service.records(members))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
    }
    val records = result?.getOrNull()
    when {
        result == null -> {
            Text(stringResource(R.string.tasks_commands_loading))
        }

        records == null -> {
            Text(stringResource(R.string.project_failed))
            TextButton({ revision++ }) { Text(stringResource(R.string.chat_retry)) }
        }

        tab == 2 -> {
            TasksScreen(
                container.chatService,
                container.fileManager,
                onOpenSession,
                onOpenCommandDetail = onOpenCommand,
                sessionFilter = members,
                projectTasks = records.tasks,
            )
        }

        else -> {
            ArtifactsScreenDestination(
                container,
                onOpenSession,
                onOpenTask,
                sessionFilter = members,
                projectRecords = records,
                onRefresh = { revision++ },
            )
        }
    }
}
