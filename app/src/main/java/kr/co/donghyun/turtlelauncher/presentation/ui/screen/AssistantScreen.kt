package kr.co.donghyun.turtlelauncher.presentation.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.turtlelauncher.presentation.assistant.TurtleAssistant
import kr.co.donghyun.turtlelauncher.presentation.ui.motion.TurtleMotion
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSub

private data class AssistantMessage(val fromUser: Boolean, val text: String)

@Composable
fun AssistantScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val messages = remember { mutableStateListOf(AssistantMessage(false, TurtleAssistant.greeting().text)) }
    var input by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(TurtleAssistant.greeting().suggestions) }

    fun send(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        messages += AssistantMessage(true, trimmed)
        val reply = TurtleAssistant.respond(context, trimmed)
        messages += AssistantMessage(false, reply.text)
        suggestions = reply.suggestions
        input = ""
    }

    Column(Modifier.fillMaxSize().background(BgDark).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(BgSurface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back", color = TextSub) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Turtle Assistant", color = TextMain, fontWeight = FontWeight.Bold)
                Text("Offline guidance; no API key or cloud upload", color = TextSub, fontSize = 10.sp)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = false,
        ) {
            items(messages) { message ->
                AnimatedVisibility(visible = true, enter = TurtleMotion.dialogEnter) {
                    Box(
                        Modifier.fillMaxWidth().padding(start = if (message.fromUser) 48.dp else 0.dp, end = if (message.fromUser) 0.dp else 48.dp)
                            .background(if (message.fromUser) Turtle.copy(alpha = 0.18f) else BgSurface, RoundedCornerShape(12.dp))
                            .border(1.dp, if (message.fromUser) Turtle else BgBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    ) { Text(message.text, color = TextMain, fontSize = 12.sp) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.take(4).forEach { suggestion ->
                Text(
                    suggestion,
                    color = Turtle,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable { send(suggestion) }.padding(vertical = 5.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
                cursorBrush = SolidColor(Turtle),
                modifier = Modifier.weight(1f).background(BgSurface, RoundedCornerShape(10.dp)).border(1.dp, BgBorder, RoundedCornerShape(10.dp)).padding(12.dp),
            )
            TextButton(onClick = { send(input) }) { Text("Send", color = Turtle, fontWeight = FontWeight.Bold) }
        }
    }
}
