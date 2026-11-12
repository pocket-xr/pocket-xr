package com.hayeco.flipspaces.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.hayeco.flipspaces.R

/**
 * 0.5.101 (Mike: each settings screen needs "a way to reset to Pocket XR default settings"): a button at the bottom of
 * the screen that asks first, saying what goes back and what stays ([what]).
 */
@Composable
fun ResetButton(screen: String, what: String, onReset: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_reset_defaults)) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.home_reset_title, screen)) },
            text = { Text(what) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onReset()
                }) { Text(stringResource(R.string.home_reset)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}
