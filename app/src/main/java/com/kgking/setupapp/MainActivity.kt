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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

data class AppUidItem(val packageName: String, val uid: Int)

data class RootResult(
    val success: Boolean,
    val idOutput: String,
    val details: String,
    val daemonRunning: Boolean,
)

private enum class TabPage { Home, Blacklist }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val daemonPath = extractAssetToPrivateDir("daemon")
        val blacklistToolPath = extractAssetToPrivateDir("kgking_blacklist_tool")
        val appUidList = packageManager.getInstalledApplications(0)
            .map { AppUidItem(it.packageName, it.uid) }
            .sortedBy { it.packageName }

        setContent {
            MaterialTheme {
                AppScaffold(
                    daemonPrivatePath = daemonPath,
                    blacklistToolPath = blacklistToolPath,
                    appUidList = appUidList,
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
    appUidList: List<AppUidItem>,
) {
    var tab by remember { mutableStateOf(TabPage.Home) }
    val scope = rememberCoroutineScope()
    var rootResult by remember { mutableStateOf<RootResult?>(null) }
    var autoBindMessage by remember { mutableStateOf("等待执行...") }
    var delinkCompleted by remember { mutableStateOf(false) }
    var blacklistKernelItems = remember { mutableStateListOf<String>() }
    var addStatus by remember { mutableStateOf("") }

    suspend fun refreshKernelList() {
        val listResult = withContext(Dispatchers.IO) { runBlacklistTool(blacklistToolPath, "list") }
        blacklistKernelItems.clear()
        blacklistKernelItems.addAll(
            listResult.output.lines()
                .map { it.trim() }
                .filter { it.startsWith("UID:") }
        )
    }

    LaunchedEffect(Unit) {
        rootResult = withContext(Dispatchers.IO) { RootBridge.runRootCommand(daemonPrivatePath) }

        if (rootResult?.success == true) {
            val check = withContext(Dispatchers.IO) { runBlacklistTool(blacklistToolPath, "list") }
            if (check.output.contains("无法打开 /dev/kgking")) {
                delinkCompleted = true
            }

            val pubgmUid = appUidList.firstOrNull { it.packageName == "com.tencent.tmgp.pubgmhd" }?.uid
            if (pubgmUid != null) {
                val addResult = withContext(Dispatchers.IO) {
                    runBlacklistTool(blacklistToolPath, "add $pubgmUid")
                }
                autoBindMessage = if (addResult.code == 0) addResult.output.ifBlank { "已尝试自动添加 PUBG UID: $pubgmUid" } else "添加失败: ${addResult.output}"
            } else {
                autoBindMessage = "未找到 com.tencent.tmgp.pubgmhd"
            }
            refreshKernelList()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = tab == TabPage.Home,
                    onClick = { tab = TabPage.Home },
                    icon = { androidx.compose.material3.Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("主页") },
                )
                NavigationBarItem(
                    selected = tab == TabPage.Blacklist,
                    onClick = { tab = TabPage.Blacklist },
                    icon = { androidx.compose.material3.Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("黑名单管理") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            TabPage.Home -> {
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
                                Text("自动添加 PUBG 黑名单结果", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(autoBindMessage)
                            }
                        }
                    }

                    item {
                        Button(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runBlacklistTool(blacklistToolPath, "hide") }
                                addStatus = result.output
                                delinkCompleted = result.output.contains("已隐藏")
                            }
                        }) {
                            Text("一键断链脱表")
                        }
                    }
                }
            }

            TabPage.Blacklist -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("内核模块黑名单 UID", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                if (blacklistKernelItems.isEmpty()) {
                                    Text("暂无数据")
                                } else {
                                    blacklistKernelItems.forEach { Text(it) }
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = {
                                    scope.launch { refreshKernelList() }
                                }) {
                                    Text("刷新")
                                }
                            }
                        }
                    }

                    items(appUidList) { app ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.packageName, style = MaterialTheme.typography.bodyMedium)
                                    Text("UID: ${app.uid}")
                                }
                                Button(onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { runBlacklistTool(blacklistToolPath, "add ${app.uid}") }
                                        addStatus = result.output
                                        refreshKernelList()
                                    }
                                }) {
                                    Text("加入")
                                }
                            }
                        }
                    }

                    if (addStatus.isNotBlank()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(addStatus, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
