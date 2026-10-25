package com.example.netbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    var red by remember { mutableStateOf(initialColor.red * 255) }
    var green by remember { mutableStateOf(initialColor.green * 255) }
    var blue by remember { mutableStateOf(initialColor.blue * 255) }

    val currentColor = Color(red.toInt().coerceIn(0, 255), green.toInt().coerceIn(0, 255), blue.toInt().coerceIn(0, 255))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите цвет палитры") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(currentColor, CircleShape)
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Red: ${red.toInt()}")
                    Slider(
                        value = red,
                        onValueChange = { red = it },
                        valueRange = 0f..255f
                    )
                    Text("Green: ${green.toInt()}")
                    Slider(
                        value = green,
                        onValueChange = { green = it },
                        valueRange = 0f..255f
                    )
                    Text("Blue: ${blue.toInt()}")
                    Slider(
                        value = blue,
                        onValueChange = { blue = it },
                        valueRange = 0f..255f
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onColorSelected(currentColor) }
            ) {
                Text("Выбрать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}