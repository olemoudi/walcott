package dev.walcott

import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla del spike de Fase 0: comprobar a mano los tres pilares del enforcement
 * (Device Owner, suspensión de paquetes, UsageStats) sobre un emulador provisionado.
 * Esta pantalla se sustituirá por la UI real en Fase 1.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = WalcottAdminReceiver.componentName(this)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var log by remember { mutableStateOf("") }
                    var targetPackage by remember { mutableStateOf("com.android.settings") }
                    val isOwner = dpm.isDeviceOwnerApp(packageName)

                    fun appendLog(line: String) {
                        val ts = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
                        log = "[$ts] $line\n$log"
                    }

                    fun runCatchingToLog(label: String, block: () -> String) {
                        runCatching(block)
                            .onSuccess { appendLog("$label: $it") }
                            .onFailure { appendLog("$label ERROR: $it") }
                    }

                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Walcott — spike Fase 0", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (isOwner) "✔ Esta app ES Device Owner"
                            else "✘ NO es Device Owner (provisionar con adb dpm set-device-owner)",
                        )
                        HorizontalDivider()

                        OutlinedTextField(
                            value = targetPackage,
                            onValueChange = { targetPackage = it },
                            label = { Text("Paquete objetivo") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatchingToLog("suspender $targetPackage") {
                                    val failed = dpm.setPackagesSuspended(
                                        admin, arrayOf(targetPackage), true,
                                    )
                                    if (failed.isEmpty()) "OK" else "falló para ${failed.toList()}"
                                }
                            }) { Text("Suspender") }
                            Button(onClick = {
                                runCatchingToLog("reactivar $targetPackage") {
                                    val failed = dpm.setPackagesSuspended(
                                        admin, arrayOf(targetPackage), false,
                                    )
                                    if (failed.isEmpty()) "OK" else "falló para ${failed.toList()}"
                                }
                            }) { Text("Reactivar") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatchingToLog("restricciones base") {
                                    // DISALLOW_DEBUGGING_FEATURES queda fuera del spike:
                                    // mataría adb en el emulador y perderíamos el control.
                                    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                                    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                                    dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
                                    if (Build.VERSION.SDK_INT >= 30) dpm.setAutoTimeEnabled(admin, true)
                                    "aplicadas (safe-boot, factory-reset, add-user, auto-time)"
                                }
                            }) { Text("Aplicar restricciones") }
                            Button(onClick = {
                                runCatchingToLog("quitar restricciones") {
                                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
                                    "quitadas"
                                }
                            }) { Text("Quitar") }
                        }

                        Button(onClick = {
                            runCatchingToLog("uso última hora") {
                                val usm = getSystemService(UsageStatsManager::class.java)
                                val end = System.currentTimeMillis()
                                val stats = usm.queryUsageStats(
                                    UsageStatsManager.INTERVAL_DAILY, end - 3_600_000, end,
                                )
                                if (stats.isNullOrEmpty()) {
                                    "vacío — falta conceder el appop GET_USAGE_STATS via adb"
                                } else {
                                    stats.filter { it.totalTimeInForeground > 0 }
                                        .sortedByDescending { it.totalTimeInForeground }
                                        .take(5)
                                        .joinToString("; ") {
                                            "${it.packageName}=${it.totalTimeInForeground / 1000}s"
                                        }
                                }
                            }
                        }) { Text("Leer UsageStats") }

                        HorizontalDivider()
                        Text(log, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
