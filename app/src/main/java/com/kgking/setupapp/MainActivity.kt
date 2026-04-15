package com.kgking.setupapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                RootShellScreen()
            }
        }
    }
}

private enum class RootStatus {
    Running,
    Success,
    Failed,
}

data class RootResult(
    val success: Boolean,
    val idOutput: String,
    val details: String,
)

object NativeBridge {
    init {
        System.loadLibrary("kgking_native")
    }

    external fun runRootCommand(): RootResult
}

@Composable
fun RootShellScreen() {
    var result by remember { mutableStateOf<RootResult?>(null) }

    LaunchedEffect(Unit) {
        result = NativeBridge.runRootCommand()
    }

    val status = when {
        result == null -> RootStatus.Running
        result?.success == true -> RootStatus.Success
        else -> RootStatus.Failed
    }

    val cardContainerColor = when (status) {
        RootStatus.Running -> MaterialTheme.colorScheme.secondaryContainer
        RootStatus.Success -> MaterialTheme.colorScheme.tertiaryContainer
        RootStatus.Failed -> MaterialTheme.colorScheme.errorContainer
    }

    val cardTextColor = when (status) {
        RootStatus.Running -> MaterialTheme.colorScheme.onSecondaryContainer
        RootStatus.Success -> MaterialTheme.colorScheme.onTertiaryContainer
        RootStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = "KGKing Root Setup",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = cardContainerColor,
                    contentColor = cardTextColor,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (status) {
                            RootStatus.Running -> Icons.Filled.AdminPanelSettings
                            RootStatus.Success -> Icons.Filled.Fingerprint
                            RootStatus.Failed -> Icons.Filled.ErrorOutline
                        },
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    Column {
                        Text(
                            text = when (status) {
                                RootStatus.Running -> "正在执行 /dev/kgstsu 并进入 root shell"
                                RootStatus.Success -> "Root shell 执行成功"
                                RootStatus.Failed -> "Root shell 执行失败"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when (status) {
                                RootStatus.Running -> "请稍候，native 层正在处理命令。"
                                RootStatus.Success -> "已获取 root shell 并执行 id 命令。"
                                RootStatus.Failed -> result?.details ?: "请检查权限或命令路径是否正确。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "id 命令输出",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = result?.idOutput?.ifBlank { "暂无输出" } ?: "等待 native 层执行完成...",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
