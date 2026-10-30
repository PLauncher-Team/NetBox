package com.example.netbox.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.netbox.SettingsStore
import com.example.netbox.SubscriptionEntry
import java.util.UUID

@Composable
fun SubscriptionManagerScreen(
    subscriptions: List<SubscriptionEntry>,
    onSubscriptionsChange: (List<SubscriptionEntry>) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    val canAdd = newUrl.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Подписки",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "В пул попадают серверы из всех включённых подписок. Отключённые подписки не используются.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (subscriptions.isEmpty()) {
            Text(
                text = "Подписок пока нет. Добавьте URL, чтобы загрузить серверы.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        subscriptions.forEach { subscription ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = subscription.enabled,
                        onCheckedChange = { enabled ->
                            onSubscriptionsChange(
                                subscriptions.map {
                                    if (it.id == subscription.id) it.copy(enabled = enabled) else it
                                }
                            )
                        }
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = subscription.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = subscription.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (subscription.enabled) "Включена" else "Выключена",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (subscription.enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            onSubscriptionsChange(subscriptions.filterNot { it.id == subscription.id })
                        }
                    ) {
                        Text("Удалить")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Добавить подписку")
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Добавить подписку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Название") },
                        placeholder = { Text("Например, Основная") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("URL подписки") },
                        placeholder = { Text("https://example.com/sub.txt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (newUrl.isNotBlank() && !canAdd) {
                        Text(
                            text = "URL должен начинаться с http:// или https://",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canAdd,
                    onClick = {
                        val url = newUrl.trim()
                        val entry = SubscriptionEntry(
                            id = UUID.randomUUID().toString(),
                            name = newName.trim().ifBlank { "Подписка ${subscriptions.size + 1}" },
                            url = url,
                            enabled = true
                        )
                        onSubscriptionsChange(subscriptions + entry)
                        newName = ""
                        newUrl = ""
                        showAddDialog = false
                    }
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
