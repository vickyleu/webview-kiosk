package uk.nktnet.webviewkiosk.ui.components.webview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.nktnet.webviewkiosk.utils.ime.ChineseImeState

@Composable
fun ChineseCandidateBar(
    state: ChineseImeState,
    onCandidateSelected: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.enabled) {
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 18.dp),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        if (state.composing.isBlank()) {
                            "中文输入 Ctrl+Space关闭"
                        } else {
                            "拼音 ${state.composing}"
                        }
                    )
                },
            )

            state.candidates.forEachIndexed { index, candidate ->
                ElevatedAssistChip(
                    onClick = { onCandidateSelected(index) },
                    label = { Text("${(index + 1) % 10}. ${candidate.text}") },
                )
            }

            TextButton(onClick = onClose) {
                Text("关闭")
            }
        }
    }
}
