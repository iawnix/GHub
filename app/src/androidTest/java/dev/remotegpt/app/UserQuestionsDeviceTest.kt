package dev.remotegpt.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.*
import dev.remotegpt.client.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UserQuestionsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun request(secret: Boolean = false) = ServerRequest("connection", JsonPrimitive(0), "item/tool/requestUserInput",
        wireJson.parseToJsonElement("""{"threadId":"t","turnId":"turn","itemId":"call","isBlocking":false,"questions":[{"id":"scope","header":"Scope","question":"How far should we go?","isSecret":$secret,"isOther":true,"options":[{"label":"Structures","description":"Prepare structure files"},{"label":"Descriptors","description":"Run calculations"}]}]}""").jsonObject)

    @Test fun answerRequiresExplicitSelectionAndDisablesAfterSubmission() {
        val pending = mutableStateOf(PendingQuestions(request()))
        var result: Map<String, List<String>>? = null
        compose.setContent { WorkbenchTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            UserQuestionsCard(pending.value) { result = it; pending.value = pending.value.copy(submitted = true) }
        } } }
        compose.onNodeWithText(label(R.string.ui_question_nonblocking)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ui_question_submit)).assertIsNotEnabled()
        compose.onNodeWithText("Structures").performClick()
        compose.onNodeWithText(label(R.string.ui_question_submit)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(mapOf("scope" to listOf("Structures")), result) }
        compose.onNodeWithText(label(R.string.ui_submitted_waiting_for_server_confirmation)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ui_question_submit)).assertDoesNotExist()
        compose.onNodeWithText("Descriptors").assertIsNotEnabled()
    }

    @Test fun customAnswerSurvivesRecreationButSecretAnswerDoesNot() {
        val secret = mutableStateOf(false)
        val restore = StateRestorationTester(compose)
        var result: Map<String, List<String>>? = null
        restore.setContent { WorkbenchTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            UserQuestionsCard(PendingQuestions(request(secret.value).copy(epoch = if (secret.value) "secret" else "plain"))) { result = it }
        } } }
        compose.onNodeWithText(label(R.string.ui_question_other)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Prepare POM structures")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(label(R.string.ui_question_submit)).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(mapOf("scope" to listOf("Prepare POM structures")), result); secret.value = true }
        compose.onNodeWithText(label(R.string.ui_question_submit)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.ui_question_other)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("private answer")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(label(R.string.ui_question_submit)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun historyShowsQuestionAnswerAndAccompanyingTextWithoutProtocolMarkup() {
        val reply = QuestionReply("[\"request_user_input_async\",\"call_fixture\",0]", "处理到哪一步？", "准备结构数据")
        val raw = UserQuestions.encodeReply(listOf(reply)) + "\n\n还有这段补充说明。"
        compose.setContent { WorkbenchTheme {
            TimelineCard(TimelineItem("reply", "turn", "userMessage", raw, true), {})
        } }
        compose.onNodeWithText(reply.question).assertIsDisplayed()
        compose.onNodeWithText(reply.answer).assertIsDisplayed()
        compose.onNodeWithText("还有这段补充说明。").assertIsDisplayed()
        compose.onNodeWithText("send_user_message_question_reply", substring = true).assertDoesNotExist()
    }
}
