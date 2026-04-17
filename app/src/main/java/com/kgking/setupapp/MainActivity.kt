package com.kgking.setupapp

import android.os.Bundle
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class KernelStatus {
    SUCCESS,
    FAILED,
}

data class RootResult(
    val status: KernelStatus,
    val title: String,
    val subtitle: String,
)

data class Announcement(
    val lastversion: String,
    val msg: String,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val daemonPath = extractAssetToPrivateDir("daemon")
        val appUid = applicationInfo.uid

        val prefs = getSharedPreferences("kgking_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                AppScaffold(
                    daemonPrivatePath = daemonPath,
                    whitelistUid = appUid,
                    prefs = prefs,
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

private object NetworkBridge {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAnnouncement(): Announcement? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("version", "1")
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("http://api.king7891.top/version.php")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withContext null
                val jsonResponse = JSONObject(responseBody)
                Announcement(
                    lastversion = jsonResponse.optString("lastversion", ""),
                    msg = jsonResponse.optString("msg", "")
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    daemonPrivatePath: String,
    whitelistUid: Int,
    prefs: SharedPreferences,
) {
    var rootResult by remember { mutableStateOf<RootResult?>(null) }
    var announcement by remember { mutableStateOf<Announcement?>(null) }
    var switch1Enabled by remember { mutableStateOf(true) }
    var switch2Enabled by remember { mutableStateOf(true) }

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        rootResult = withContext(Dispatchers.IO) {
            RootBridge.runRootCommand(daemonPrivatePath, whitelistUid)
        }
        if (rootResult?.status == KernelStatus.SUCCESS) {
            announcement = NetworkBridge.fetchAnnouncement()
        }
    }

    val displayResult = rootResult ?: RootResult(
        status = KernelStatus.FAILED,
        title = "检测中...",
        subtitle = "正在获取状态",
    )

    val isSuccess = displayResult.status == KernelStatus.SUCCESS

    val statusCardColor = when (displayResult.status) {
        KernelStatus.SUCCESS -> Color(0xFF2E7D32)
        KernelStatus.FAILED -> Color(0xFFB00020)
    }

    val lightGreenColor = Color(0xFF81C784)
    val cardBackgroundColor = Color(0xFFF5F5F5)

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "KINGSETUP",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = statusCardColor,
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

            if (isSuccess) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = lightGreenColor,
                            contentColor = Color.White,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("版本信息", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                announcement?.lastversion ?: "加载中...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = lightGreenColor,
                            contentColor = Color.White,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("更新公告", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                announcement?.msg ?: "加载中...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = cardBackgroundColor,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("uprobeHook", style = MaterialTheme.typography.bodyLarge)
                                Switch(
                                    checked = switch1Enabled,
                                    onCheckedChange = { checked ->
                                        switch1Enabled = checked
                                        prefs.edit().putBoolean("switch1_enabled", checked).apply()
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("允许发送错误报告", style = MaterialTheme.typography.bodyLarge)
                                Switch(
                                    checked = switch2Enabled,
                                    onCheckedChange = { checked ->
                                        switch2Enabled = checked
                                        prefs.edit().putBoolean("switch2_enabled", checked).apply()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
