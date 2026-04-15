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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter

data class RootResult(
    val success: Boolean,
    val idOutput: String,
    val details: String,
    val daemonRunning: Boolean,
)

private val defaultBlacklistPackages = arrayOf(
    "com.tencent.tmgp.pubgmhd",
    "com.wn.app.np",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val daemonPath = extractAssetToPrivateDir("daemon")
        val blacklistToolPath = extractAssetToPrivateDir("kgking_blacklist_tool")
        val appUidMap = packageManager.getInstalledApplications(0)
            .associate { it.packageName to it.uid }

        setContent {
            MaterialTheme {
                AppScaffold(
                    daemonPrivatePath = daemonPath,
                    blacklistToolPath = blacklistToolPath,
                    appUidMap = appUidMap,
                )
            }
        }
    }

    private fun extractAssetToPrivateDir(assetName: String): String {
        val outFile = File(filesDir, assetName)
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

    external fun runRootCommand(daemonPrivatePath: String): RootResult
}

private data class ShellExecResult(val output: String, val code: Int)

private fun runAsKgstsu(script: String): ShellExecResult {
    return try {
        val process = ProcessBuilder("/dev/kgstsu").redirectErrorStream(true).start()
        PrintWriter(process.outputStream).use { writer ->
            writer.println(script)
            writer.println("exit")
            writer.flush()
        }
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        ShellExecResult(output = output, code = code)
    } catch (t: Throwable) {
        ShellExecResult(output = t.message ?: "unknown error", code = -1)
    }
}

private fun runBlacklistTool(blacklistToolPath: String, args: String): ShellExecResult {
    return runAsKgstsu("$blacklistToolPath $args")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    daemonPrivatePath: String,
    blacklistToolPath: String,
    appUidMap: Map<String, Int>,
) {
    val scope = rememberCoroutineScope()
    var rootResult by remember { mutableStateOf<RootResult?>(null) }
    var autoBindMessage by remember { mutableStateOf("等待执行...") }
    var delinkCompleted by remember { mutableStateOf(false) }
    var actionStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        rootResult = withContext(Dispatchers.IO) { RootBridge.runRootCommand(daemonPrivatePath) }

        if (rootResult?.success == true) {
            val statusMessages = mutableListOf<String>()
            val missingPackages = mutableListOf<String>()

            defaultBlacklistPackages.forEach { packageName ->
                val uid = appUidMap[packageName] ?: -1
                if (uid <= 0) {
                    missingPackages += packageName
                } else {
                    val addResult = withContext(Dispatchers.IO) {
                        runBlacklistTool(blacklistToolPath, "add $uid")
                    }
                    val message = if (addResult.code == 0) {
                        "$packageName (UID $uid): 添加成功"
                    } else {
                        "$packageName (UID $uid): 添加失败 -> ${addResult.output.trim()}"
                    }
                    statusMessages += message
                }
            }

            if (missingPackages.isNotEmpty()) {
                statusMessages += "未找到包名: ${missingPackages.joinToString()}"
            }

            autoBindMessage = statusMessages.joinToString("\n").ifBlank { "未执行自动黑名单" }

            val check = withContext(Dispatchers.IO) { runBlacklistTool(blacklistToolPath, "list") }
            if (check.output.contains("无法打开 /dev/kgking")) {
                delinkCompleted = true
            }
        }
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

            if (delinkCompleted) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFB00020),
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("已完成脱链脱表", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Root 状态", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(rootResult?.details ?: "正在执行...", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(rootResult?.idOutput?.ifBlank { "暂无输出" } ?: "等待输出...")
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("默认黑名单添加结果", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(autoBindMessage)
                    }
                }
            }

            item {
                Button(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runBlacklistTool(blacklistToolPath, "hide") }
                        actionStatus = result.output
                        delinkCompleted = result.output.contains("已隐藏")
                    }
                }) {
                    Text("一键断链脱表")
                }
            }

            if (actionStatus.isNotBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(actionStatus, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
