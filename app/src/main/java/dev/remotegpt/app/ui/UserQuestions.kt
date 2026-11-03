package dev.remotegpt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.remotegpt.app.PendingQuestions
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import dev.remotegpt.client.*

@Composable internal fun QuestionReplyContent(reply: QuestionReplyMessage) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            reply.replies.forEach { answer ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(answer.question, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr(R.string.ui_question_answer), style = MaterialTheme.typography.labelSmall)
                    Text(answer.answer.ifEmpty { tr(R.string.ui_question_empty_answer) }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (reply.remainingText.isNotBlank()) Text(reply.remainingText.trim(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable fun UserQuestionsCard(pending: PendingQuestions, enabled: Boolean = true,
    onAnswer: (Map<String, List<String>>) -> Unit,
) {
    val questions = remember(pending.request) { pending.request.userQuestions }.orEmpty()
    // Secret answers stay in memory only; they must not enter saved instance state.
    val answers = remember(pending.request.key) { mutableStateMapOf<String, String>() }
    WorkbenchSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(R.string.ui_question_required), style = MaterialTheme.typography.titleMedium)
            Text(tr(if (pending.request.isBlocking) R.string.ui_question_blocking else R.string.ui_question_nonblocking),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            questions.forEach { question ->
                key(pending.request.key, question.id) {
                    QuestionEditor(question, enabled && !pending.submitted) { answer ->
                        answers[question.id] = answer
                    }
                }
            }
            if (pending.submitted) Text(tr(R.string.ui_submitted_waiting_for_server_confirmation), style = MaterialTheme.typography.bodySmall)
            else Button(onClick = { onAnswer(answers.mapValues { listOf(it.value) }) },
                enabled = enabled && questions.isNotEmpty() && questions.all { !answers[it.id].isNullOrBlank() }) {
                Text(tr(R.string.ui_question_submit))
            }
        }
    }
}

@Composable private fun QuestionEditor(question: UserQuestion, enabled: Boolean, onAnswer: (String) -> Unit) {
    val selected = if (question.isSecret) remember { mutableStateOf<String?>(null) }
        else rememberSaveable { mutableStateOf<String?>(null) }
    val custom = if (question.isSecret) remember { mutableStateOf("") }
        else rememberSaveable { mutableStateOf("") }
    val other = if (question.isSecret) remember { mutableStateOf(false) }
        else rememberSaveable { mutableStateOf(false) }
    val freeText = question.options.isEmpty() || other.value
    val answer = if (freeText) custom.value else selected.value.orEmpty()
    SideEffect { onAnswer(answer) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.labelMedium)
        Text(question.question, style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.selectableGroup()) {
            question.options.forEach { option ->
                QuestionOption(option.label, option.description, !other.value && selected.value == option.label, enabled) {
                    other.value = false; selected.value = option.label
                }
            }
            if (question.options.isNotEmpty() && question.isOther) {
                QuestionOption(tr(R.string.ui_question_other), "", other.value, enabled) { other.value = true }
            }
        }
        if (freeText) OutlinedTextField(value = custom.value, onValueChange = { custom.value = it },
            modifier = Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(tr(R.string.ui_question_answer)) },
            visualTransformation = if (question.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (question.isSecret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
        )
    }
}

@Composable private fun QuestionOption(label: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
