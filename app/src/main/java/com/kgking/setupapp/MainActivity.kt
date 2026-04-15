package com.kgking.setupapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class KernelStatus {
    RUNNING,
    DELINKED,
    FAILED,
}

data class RootResult(
    val status: KernelStatus,
    val title: String,
    val subtitle: String,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val daemonPath = extractAssetToPrivateDir("daemon")
        val appUid = applicationInfo.uid

        setContent {
            MaterialTheme {
                AppScaffold(
                    daemonPrivatePath = daemonPath,
                    whitelistUid = appUid,
                )
            }
        }
    }

    private fun extractAssetToPrivateDir(assetName: String): String {
        val outFile = java.io.File(filesDir, assetName)
        assets.open(assetName).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        outFile.setExecutable(true, false)
        outFile.setReadable(true, false)
        outFile.setWritable(true, true)
        return outFile.absolutePath
    }
}

private object RootBridge {
    init {
        System.loadLibrary("kgking_native")
    }

    external fun runRootCommand(daemonPrivatePath: String, whitelistUid: Int): RootResult
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    daemonPrivatePath: String,
    whitelistUid: Int,
) {
    var rootResult by remember { mutableStateOf<RootResult?>(null) }

    LaunchedEffect(Unit) {
        rootResult = withContext(Dispatchers.IO) {
            RootBridge.runRootCommand(daemonPrivatePath, whitelistUid)
        }
    }

    val displayResult = rootResult ?: RootResult(
        status = KernelStatus.FAILED,
        title = "检测中...",
        subtitle = "正在获取状态",
    )

    val cardColor = when (displayResult.status) {
        KernelStatus.RUNNING -> Color(0xFF2E7D32)
        KernelStatus.DELINKED -> Color(0xFFEF6C00)
        KernelStatus.FAILED -> Color(0xFFB00020)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("KINGSETUP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = cardColor,
                        contentColor = Color.White,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(displayResult.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(displayResult.subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
