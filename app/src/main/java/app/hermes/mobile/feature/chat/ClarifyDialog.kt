package app.hermes.mobile.feature.chat

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Dns
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.mobile.core.model.ClarifyType
import app.hermes.mobile.core.model.HostAttributedClarify

@Composable
fun ClarifyDialog(
    attributedClarify: HostAttributedClarify,
    onDismiss: () -> Unit,
    onSubmit: (value: String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val request = attributedClarify.request
    val hostDisplayName = attributedClarify.hostDisplayName
    val context = LocalContext.current

    LaunchedEffect(request.requestId) {
        input = ""
    }

    val isMasked = request.promptType == ClarifyType.SUDO || request.promptType == ClarifyType.SECRET

    // Scoped FLAG_SECURE: only enable during the lifetime of this dialog for password/secret fields
    DisposableEffect(isMasked) {
        val window = (context as? Activity)?.window
        if (isMasked) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (isMasked) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

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
        onDismissRequest = {
            input = ""
            onDismiss()
        },
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = hostDisplayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
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
                    keyboardOptions = if (isMasked) {
                        KeyboardOptions(keyboardType = KeyboardType.Password)
                    } else {
                        KeyboardOptions.Default
                    },
                    singleLine = isMasked,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        val toSubmit = input
                        input = ""
                        onSubmit(toSubmit)
                    }
                },
                enabled = input.isNotBlank()
            ) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    input = ""
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}
