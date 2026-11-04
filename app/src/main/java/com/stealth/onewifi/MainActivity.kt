package com.stealth.onewifi

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("Connected")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnClientService()
        } else {
            connectionStatus = "Disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            OneWiFiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WifiSettingsScreen(
                        statusText = connectionStatus,
                        onArmHostStandby = {
                            BleWakeManager.startHardwareScan(this)
                            Toast.makeText(
                                this,
                                "Moto Armed: Zero-Battery BLE Standby Active",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onForceStartHost = {
                            val intent = Intent(this, StealthHostService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                            Toast.makeText(
                                this,
                                "Moto Host Tunnel Started Immediately",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onSearchAndConnectClient = {
                            connectionStatus = "Searching & waking host..."
                            BleWakeManager.sendWakePulse(this)
                            val prepareIntent = VpnService.prepare(this)
                            if (prepareIntent != null) {
                                vpnPermissionLauncher.launch(prepareIntent)
                            } else {
                                startVpnClientService()
                            }
                        },
                        onDisconnectAll = {
                            stopService(Intent(this, StealthClientVpnService::class.java))
                            stopService(Intent(this, StealthHostService::class.java))
                            connectionStatus = "Disconnected"
                        }
                    )
                }
            }
        }
    }

    private fun startVpnClientService() {
        val intent = Intent(this, StealthClientVpnService::class.java)
        startService(intent)
        connectionStatus = "Connected"
        Toast.makeText(this, "Wake pulse sent — auto-connecting to Moto", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WifiSettingsScreen(
    statusText: String,
    onArmHostStandby: () -> Unit,
    onForceStartHost: () -> Unit,
    onSearchAndConnectClient: () -> Unit,
    onDisconnectAll: () -> Unit
) {
    var wifiEnabled by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Top App Bar: "< Wi-Fi" and 3-dot Menu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.combinedClickable(
                    onClick = { },
                    onLongClick = { onArmHostStandby() }
                )
            ) {
                Text(
                    text = "<",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Wi-Fi",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                MaterialTheme(
                    shapes = MaterialTheme.shapes.copy(
                        extraSmall = RoundedCornerShape(22.dp)
                    )
                ) {
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        offset = DpOffset(x = (-8).dp, y = 0.dp),
                        modifier = Modifier
                            .width(210.dp)
                            .background(OneUiPopupDark)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Wi-Fi Direct",
                                    color = OneUiTextPrimary,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onSearchAndConnectClient()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Intelligent Wi-Fi",
                                    color = OneUiTextPrimary,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            },
                            onClick = { menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Advanced settings",
                                    color = OneUiTextPrimary,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            },
                            onClick = { menuExpanded = false }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Rounded "On" Switch Pill Container matching Samsung One UI
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (wifiEnabled) "On" else "Off",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = wifiEnabled,
                    onCheckedChange = { enabled ->
                        wifiEnabled = enabled
                        if (enabled) {
                            onSearchAndConnectClient()
                        } else {
                            onDisconnectAll()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = OneUiBlue
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        if (wifiEnabled) {
            // "Current network" Section Header
            Text(
                text = "Current network",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
            )

            // Current Network Card: moto g64 5G_2512
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .clickable { onSearchAndConnectClient() },
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_wifi_5_lock),
                            contentDescription = "Wi-Fi 5 Secured",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(18.dp))
                        Column {
                            Text(
                                text = "moto g64 5G_2512",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = statusText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(26.dp)
                                .background(OneUiDivider)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .combinedClickable(
                                    onClick = { onSearchAndConnectClient() },
                                    onLongClick = { onForceStartHost() }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Network settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // "Available networks" Section Header
            Text(
                text = "Available networks",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
            )

            // Available Networks Card: "+ Add network"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .clickable { onSearchAndConnectClient() },
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "+",
                        color = OneUiGreen,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light
                    )
                    Spacer(modifier = Modifier.width(18.dp))
                    Text(
                        text = "Add network",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
