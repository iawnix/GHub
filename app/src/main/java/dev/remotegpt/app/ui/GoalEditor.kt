@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.remotegpt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import dev.remotegpt.client.ThreadGoal

internal fun goalStatusLabel(status: String): String = tr(when (status) {
    "active" -> R.string.ui_goal_active
    "paused" -> R.string.ui_goal_paused
    "blocked" -> R.string.ui_goal_blocked
    "complete" -> R.string.ui_goal_complete
    "budgetLimited" -> R.string.ui_goal_budget_limited
    "usageLimited" -> R.string.ui_goal_usage_limited
    else -> R.string.ui_goal_title
})

@Composable internal fun GoalEditorSheet(goal: ThreadGoal?, initialObjective: String, supported: Boolean,
    busy: Boolean, enabled: Boolean, onDismiss: () -> Unit, onSave: (String, Long?) -> Unit,
    onStatus: (String) -> Unit, onClear: () -> Unit,
) {
    var objective by rememberSaveable(goal?.objective, initialObjective) { mutableStateOf(initialObjective.ifBlank { goal?.objective.orEmpty() }) }
    var budget by rememberSaveable(goal?.tokenBudget) { mutableStateOf(goal?.tokenBudget?.toString().orEmpty()) }
    var confirmClear by remember { mutableStateOf(false) }
    val canChange = supported && enabled && !busy
    val parsedBudget = budget.toLongOrNull()
    val validBudget = budget.isBlank() || (parsedBudget != null && parsedBudget > 0)
    WorkbenchBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(R.string.ui_goal_title), style = MaterialTheme.typography.titleLarge)
            if (!supported) Text(tr(R.string.ui_goal_unavailable), style = MaterialTheme.typography.bodyMedium)
            else {
                if (goal != null) Text("${goalStatusLabel(goal.status)} · ${tr(R.string.ui_goal_tokens, goal.tokensUsed)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(objective, { objective = it }, Modifier.fillMaxWidth(), enabled = canChange,
                    label = { Text(tr(R.string.ui_goal_objective)) }, minLines = 2, maxLines = 5)
                OutlinedTextField(budget, { budget = it }, Modifier.fillMaxWidth(), enabled = canChange,
                    label = { Text(tr(R.string.ui_goal_budget)) }, singleLine = true, isError = !validBudget,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Text(tr(R.string.ui_goal_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onSave(objective.trim(), parsedBudget) }, enabled = canChange && objective.isNotBlank() && validBudget,
                    modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(tr(R.string.ui_save))
                }
                if (goal != null) {
                    TextButton(onClick = { onStatus(if (goal.status == "active") "paused" else "active") }, enabled = canChange) {
                        Text(tr(if (goal.status == "active") R.string.ui_goal_pause else R.string.ui_goal_resume))
                    }
                    TextButton(onClick = { confirmClear = true }, enabled = canChange) { Text(tr(R.string.ui_goal_clear)) }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(tr(R.string.ui_goal_clear)) }, text = { Text(tr(R.string.ui_goal_clear_hint)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, enabled = canChange) { Text(tr(R.string.ui_goal_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr(R.string.ui_cancel)) } })
}
