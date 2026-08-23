package app.hermes.mobile.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.hermes.mobile.core.model.ClarifyType
import app.hermes.mobile.core.model.HermesClarifyRequest

@Composable
fun ClarifyDialog(
    request: HermesClarifyRequest,
    onDismiss: () -> Unit,
    onSubmit: (value: String) -> Unit
) {
    var input by remember { mutableStateOf("") }

    val isMasked = request.promptType == ClarifyType.SUDO || request.promptType == ClarifyType.SECRET
    val title = when (request.promptType) {
        ClarifyType.SUDO -> "Sudo Password Required"
        ClarifyType.SECRET -> "Secret / API Key Required"
        ClarifyType.CLARIFY -> "Clarification Requested"
    }

    val icon = when (request.promptType) {
        ClarifyType.SUDO -> Icons.Default.Lock
        ClarifyType.SECRET -> Icons.Default.Key
        ClarifyType.CLARIFY -> Icons.AutoMirrored.Filled.HelpOutline
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = request.question,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = {
                        Text(
                            if (isMasked) "Password / Secret" else "Your Answer"
                        )
                    },
                    visualTransformation = if (isMasked) PasswordVisualTransformation() else VisualTransformation.None,
                    singleLine = isMasked,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onSubmit(input)
                    }
                },
                enabled = input.isNotBlank()
            ) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
