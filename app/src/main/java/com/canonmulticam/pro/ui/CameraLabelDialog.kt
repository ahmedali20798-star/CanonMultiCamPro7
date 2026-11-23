package com.canonmulticam.pro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CameraLabelDialog(
    slotIndex: Int,
    currentLabel: String,
    modelName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(slotIndex, currentLabel) { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color(0xFFF5F3EE),
        title = { Text("تسمية الكاميرا ${slotIndex + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "الموديل: $modelName",
                    color = Color(0xFFB8B5AE)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 24) text = it },
                    singleLine = true,
                    label = { Text("أدخل اسماً مخصصاً (مثل: HOST 1 أو GUEST)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF5F3EE),
                        unfocusedTextColor = Color(0xFFF5F3EE),
                        focusedBorderColor = Color(0xFFA58B62)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA58B62))
            ) {
                Text("حفظ", color = Color(0xFF111111))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color(0xFFB8B5AE))
            }
        }
    )
}
