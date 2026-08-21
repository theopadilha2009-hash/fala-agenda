package com.theopadilha.falaagenda.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.data.prefs.ThemeMode

@Composable
fun HomeDrawerSheet(
    themeMode: ThemeMode,
    batteryOk: Boolean,
    onMonth: () -> Unit,
    onUpdate: () -> Unit,
    onShare: () -> Unit,
    onBattery: () -> Unit,
    onWidget: () -> Unit,
    onSettings: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Fala Agenda",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            NavigationDrawerItem(
                label = { Text("Resumo do mês") },
                selected = false,
                onClick = onMonth,
                icon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
            )
            NavigationDrawerItem(
                label = { Text("Atualizar aplicativo") },
                selected = false,
                onClick = onUpdate,
                icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
            )
            NavigationDrawerItem(
                label = { Text("Enviar o aplicativo") },
                selected = false,
                onClick = onShare,
                icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
            )
            NavigationDrawerItem(
                label = { Text(if (batteryOk) "Avisos liberados" else "Não matar alarmes") },
                selected = false,
                onClick = onBattery,
                icon = { Icon(Icons.Outlined.BatteryAlert, contentDescription = null) },
            )
            NavigationDrawerItem(
                label = { Text("Widget da agenda") },
                selected = false,
                onClick = onWidget,
                icon = { Icon(Icons.Outlined.Widgets, contentDescription = null) },
            )
            NavigationDrawerItem(
                label = { Text("Configurações") },
                selected = false,
                onClick = onSettings,
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                "Aparência",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            NavigationDrawerItem(
                label = { Text("Seguir o celular") },
                selected = themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeMode(ThemeMode.SYSTEM) },
                icon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
            )
            NavigationDrawerItem(
                label = { Text("Claro") },
                selected = themeMode == ThemeMode.LIGHT,
                onClick = { onThemeMode(ThemeMode.LIGHT) },
                icon = { Icon(Icons.Outlined.LightMode, contentDescription = null) },
            )
            NavigationDrawerItem(
                label = { Text("Escuro") },
                selected = themeMode == ThemeMode.DARK,
                onClick = { onThemeMode(ThemeMode.DARK) },
                icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
            )
        }
    }
}
