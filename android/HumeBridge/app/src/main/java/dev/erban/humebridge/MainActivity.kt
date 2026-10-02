package dev.erban.humebridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import dev.erban.humebridge.ble.ScannedBand
import dev.erban.humebridge.data.HrvHistoryEntity
import dev.erban.humebridge.data.SyncRunEntity
import dev.erban.humebridge.health.HealthConnectAvailability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsState()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { /* UI will re-check before scan/sync. */ }
            val csvLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/csv")
            ) { uri -> if (uri != null) viewModel.exportCsv(uri) }
            val healthPermissionLauncher = rememberLauncherForActivityResult(
                PermissionController.createRequestPermissionResultContract()
            ) { viewModel.refreshHealthConnect() }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    HumeBridgeScreen(
                        state = state,
                        hasPermissions = viewModel.hasBlePermissions(),
                        onRequestPermissions = { permissionLauncher.launch(requiredBlePermissions()) },
                        onRequestHealthPermissions = {
                            healthPermissionLauncher.launch(viewModel.healthPermissions)
                        },
                        onScan = viewModel::scan,
                        onSelect = viewModel::selectBand,
                        onSync = viewModel::sync,
                        onExport = { csvLauncher.launch("humebridge-hrv-bp.csv") },
                        onWriteHealthConnect = viewModel::writeBpToHealthConnect,
                        onSyncAndWriteHealthConnect = viewModel::syncAndWriteBpToHealthConnect,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHealthConnect()
    }

    private fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}

@Composable
private fun HumeBridgeScreen(
    state: MainUiState,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestHealthPermissions: () -> Unit,
    onScan: () -> Unit,
    onSelect: (ScannedBand) -> Unit,
    onSync: () -> Unit,
    onExport: () -> Unit,
    onWriteHealthConnect: () -> Unit,
    onSyncAndWriteHealthConnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Header()
        }

        item {
            StatusCard(state)
        }

        item {
            BandActions(
                state = state,
                hasPermissions = hasPermissions,
                onRequestPermissions = onRequestPermissions,
                onScan = onScan,
                onSync = onSync,
                onExport = onExport,
            )
        }

        item {
            HealthConnectCard(
                state = state,
                hasBlePermissions = hasPermissions,
                onRequestHealthPermissions = onRequestHealthPermissions,
                onWriteHealthConnect = onWriteHealthConnect,
                onSyncAndWriteHealthConnect = onSyncAndWriteHealthConnect,
            )
        }

        if (state.scannedBands.isNotEmpty()) {
            item { SectionTitle("Scanned Bands") }
            items(state.scannedBands) { band ->
                BandRow(band, onSelect)
            }
        }

        item { SectionTitle("Latest BP Estimate") }
        item {
            LatestRecordCard(state.latestRecord)
        }

        item { SectionTitle("Recent Valid 0x56 BP Records") }
        items(state.recentRecords) { record ->
            RecordRow(record)
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("HumeBridge", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Direct Hume Band 0x56 BP estimates, stored locally and manually written to Health Connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BandActions(
    state: MainUiState,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onSync: () -> Unit,
    onExport: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Band", fontWeight = FontWeight.SemiBold)
            if (!hasPermissions) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    onClick = onRequestPermissions,
                ) {
                    Text("Grant BLE Permissions")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = hasPermissions && !state.busy,
                    onClick = onScan,
                ) {
                    Text("Scan")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = hasPermissions && state.selectedBand != null && !state.busy,
                    onClick = onSync,
                ) {
                    Text("Sync 0x56")
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.recordCount > 0 && !state.busy,
                onClick = onExport,
            ) {
                Text("Export CSV")
            }
        }
    }
}

@Composable
private fun HealthConnectCard(
    state: MainUiState,
    hasBlePermissions: Boolean,
    onRequestHealthPermissions: () -> Unit,
    onWriteHealthConnect: () -> Unit,
    onSyncAndWriteHealthConnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Health Connect BP", fontWeight = FontWeight.SemiBold)
            Text("Status: ${healthConnectStatusText(state)}")
            Text(
                "BP records local / eligible / written / pending or failed: " +
                    "${state.recordCount} / ${state.eligibleBpCount} / ${state.writtenBpCount} / ${state.pendingOrFailedBpCount}"
            )
            val invalidCount = state.recordCount - state.eligibleBpCount
            if (invalidCount > 0) {
                Text(
                    "Zero/invalid BP records kept locally: $invalidCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.failedBpCount > 0) {
                Text("Failed records retained locally: ${state.failedBpCount}", color = MaterialTheme.colorScheme.error)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy &&
                        state.healthConnect.availability == HealthConnectAvailability.Available &&
                        !state.healthConnect.hasBloodPressurePermissions,
                    onClick = onRequestHealthPermissions,
                ) {
                    Text(
                        if (state.healthConnect.hasBloodPressurePermissions) {
                            "HC BP Permission Granted"
                        } else {
                            "Grant HC BP Permission"
                        }
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy &&
                        hasBlePermissions &&
                        state.selectedBand != null &&
                        state.healthConnect.availability == HealthConnectAvailability.Available &&
                        state.healthConnect.hasBloodPressurePermissions,
                    onClick = onSyncAndWriteHealthConnect,
                ) {
                    Text("Sync Band + Write BP")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy &&
                        state.healthConnect.availability == HealthConnectAvailability.Available &&
                        state.healthConnect.hasBloodPressurePermissions &&
                        state.pendingOrFailedBpCount > 0,
                    onClick = onWriteHealthConnect,
                ) {
                    Text("Write BP to Health Connect")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status: ${state.status}")
            Text("Selected band: ${state.selectedBand?.let { it.name ?: it.address } ?: "none"}")
            Text("Records stored: ${state.recordCount}")
            state.latestSync?.let {
                Text("Last sync: ${timeText(it.startedAtEpochMillis)} (${it.status}, new ${it.recordsNew})")
                Text("Band sync age: ${syncAgeText(it)}")
                if (isStaleSync(it)) {
                    Text(
                        "Sync soon: retained BP history appears to roll off after roughly a day or two.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.error?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BandRow(band: ScannedBand, onSelect: (ScannedBand) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(band.name ?: "(unnamed)", fontWeight = FontWeight.Medium)
                Text("${band.address}  RSSI ${band.rssi}")
                Text(if (band.advertisesJ2208Service) "Advertises fff0" else "Matched by name")
            }
            Button(modifier = Modifier.width(96.dp), onClick = { onSelect(band) }) { Text("Select") }
        }
    }
}

@Composable
private fun LatestRecordCard(record: HrvHistoryEntity?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (record == null) {
                Text("No records yet")
            } else {
                Text(record.deviceTimeLocal, fontWeight = FontWeight.Medium)
                Text("${record.bpSystolic}/${record.bpDiastolic} mmHg estimate")
                Text("HR ${record.heartRate}  HRV ${record.hrv}  stress ${record.stress}  vascular ${record.vascularAging}")
                Text("raw ${record.rawHex}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecordRow(record: HrvHistoryEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${record.deviceTimeLocal}   ${record.bpSystolic}/${record.bpDiastolic}", fontWeight = FontWeight.Medium)
        Text(
            "hrv=${record.hrv} vascular=${record.vascularAging} hr=${record.heartRate} stress=${record.stress}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(record.rawHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun timeText(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))

private fun syncAgeText(sync: SyncRunEntity): String {
    val lastActivity = sync.finishedAtEpochMillis ?: sync.startedAtEpochMillis
    val ageMillis = (System.currentTimeMillis() - lastActivity).coerceAtLeast(0)
    val minutes = ageMillis / 60_000
    val hours = minutes / 60
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        hours < 48 -> "$hours hours ago"
        else -> "${hours / 24} days ago"
    }
}

private fun isStaleSync(sync: SyncRunEntity): Boolean =
    System.currentTimeMillis() - (sync.finishedAtEpochMillis ?: sync.startedAtEpochMillis) > 20 * 60 * 60 * 1000L

private fun healthConnectStatusText(state: MainUiState): String =
    when (state.healthConnect.availability) {
        HealthConnectAvailability.Available ->
            if (state.healthConnect.hasBloodPressurePermissions) "available, BP permission granted"
            else "available, BP permission needed"
        HealthConnectAvailability.UpdateRequired -> "provider update required"
        HealthConnectAvailability.Unavailable -> "unavailable"
    }
