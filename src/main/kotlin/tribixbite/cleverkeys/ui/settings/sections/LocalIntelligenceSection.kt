package tribixbite.cleverkeys.ui.settings.sections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.openjev.OpenJevDecisionPolicy
import tribixbite.cleverkeys.openjev.context.OpenJevContextPolicy
import tribixbite.cleverkeys.openjev.runtime.OpenJevModelStore
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.LocalIntelligenceSection() {
    var diagnostics by rememberSaveable { mutableStateOf(Triple(0L, 0L, 0L)) }
    var emailDraft by rememberSaveable(openjevEmailSnippet) { mutableStateOf(openjevEmailSnippet) }
    var chatDraft by rememberSaveable(openjevChatSnippet) { mutableStateOf(openjevChatSnippet) }
    val activity = this
    val appContext = applicationContext
    // File-provider input and model validation never block settings or the IME thread.
    val modelImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !openjevModelBusy) {
            openjevModelBusy = true
            openjevModelError = null
            settingsViewModel.viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(uri)?.use { OpenJevModelStore(applicationContext).importBundle(it) }
                            ?: error("No model bundle stream")
                    }
                    openjevModelStatus = OpenJevModelStore(applicationContext).status()
                } catch (_: Exception) {
                    openjevModelError = R.string.openjev_model_import_error
                } finally { openjevModelBusy = false }
            }
        }
    }
    LaunchedEffect(localIntelligenceSectionExpanded, openjevDiagnostics) {
        while (localIntelligenceSectionExpanded) {
            openjevModelStatus = withContext(Dispatchers.IO) { OpenJevModelStore(appContext).status() }
            if (openjevDiagnostics) {
                val runtime = OpenJevRuntime.get(appContext)
                diagnostics = Triple(runtime.invocationCount, runtime.lastInferenceMs.toLong(), runtime.loadMs.toLong())
            }
            delay(2_000)
        }
    }
    CollapsibleSettingsSection(
        title = stringResource(R.string.openjev_section_title),
        expanded = localIntelligenceSectionExpanded,
        onExpandChange = { localIntelligenceSectionExpanded = it },
        sectionId = "local_intelligence",
    ) {
        Text(stringResource(R.string.openjev_privacy), style = MaterialTheme.typography.bodySmall)
        SettingsSwitch(
            title = stringResource(R.string.openjev_enable_title), description = stringResource(R.string.openjev_enable_desc),
            checked = openjevEnabled, onCheckedChange = { openjevEnabled = it; saveSetting("openjev_enabled", it) },
        )
        SettingsSwitch(
            title = stringResource(R.string.openjev_context_title), description = stringResource(R.string.openjev_context_desc),
            checked = openjevContextSuggestions, enabled = openjevEnabled,
            onCheckedChange = { openjevContextSuggestions = it; saveSetting("openjev_context_suggestions", it) },
        )
        SettingsSwitch(
            title = stringResource(R.string.openjev_autocorrect_title),
            description = stringResource(if (OpenJevDecisionPolicy.automaticReplacementValidated)
                R.string.openjev_autocorrect_ready_desc else R.string.openjev_autocorrect_desc),
            checked = OpenJevDecisionPolicy.automaticReplacementValidated && openjevNeuralAutocorrect,
            enabled = OpenJevDecisionPolicy.automaticReplacementValidated && openjevEnabled,
            onCheckedChange = { openjevNeuralAutocorrect = it; saveSetting("openjev_neural_autocorrect", it) },
        )
        SettingsDropdown(
            title = stringResource(R.string.openjev_aggressiveness_title), description = stringResource(R.string.openjev_aggressiveness_desc),
            options = listOf(stringResource(R.string.openjev_conservative), stringResource(R.string.openjev_balanced), stringResource(R.string.openjev_aggressive)),
            selectedIndex = 0,
            enabled = false, onSelectionChange = {},
        )
        SettingsSwitch(
            title = stringResource(R.string.openjev_diagnostics_title), description = stringResource(R.string.openjev_diagnostics_desc),
            checked = openjevDiagnostics, enabled = openjevEnabled,
            onCheckedChange = { openjevDiagnostics = it; saveSetting("openjev_diagnostics", it) },
        )
        if (openjevDiagnostics) Text(stringResource(R.string.openjev_diagnostics_values,
            diagnostics.first, diagnostics.second, diagnostics.third), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(when {
            openjevModelBusy -> R.string.openjev_model_working
            openjevModelStatus == "READY" -> R.string.openjev_model_ready
            openjevModelStatus == "INSTALLED" -> R.string.openjev_model_installed
            openjevModelStatus == "ERROR" -> R.string.openjev_model_error
            else -> R.string.openjev_model_missing
        }), style = MaterialTheme.typography.bodyMedium)
        openjevModelError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        OutlinedButton(enabled = !openjevModelBusy, onClick = { modelImport.launch(arrayOf("application/zip", "application/octet-stream")) }) {
            Text(stringResource(R.string.openjev_model_import))
        }
        OutlinedButton(enabled = !openjevModelBusy && openjevModelStatus != "MISSING", onClick = {
            openjevModelBusy = true
            openjevModelError = null
            activity.settingsViewModel.viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { OpenJevModelStore(activity.applicationContext).remove() }
                    openjevModelStatus = "MISSING"
                } catch (_: Exception) { openjevModelError = R.string.openjev_model_remove_error }
                finally { openjevModelBusy = false }
            }
        }) { Text(stringResource(R.string.openjev_model_remove)) }
        Text(stringResource(R.string.openjev_snippets_desc), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = emailDraft, onValueChange = { if (OpenJevContextPolicy.validSnippet(it)) emailDraft = it },
            label = { Text(stringResource(R.string.openjev_email_snippet)) }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(R.string.openjev_snippet_limit)) }, maxLines = 4)
        OutlinedTextField(value = chatDraft, onValueChange = { if (OpenJevContextPolicy.validSnippet(it)) chatDraft = it },
            label = { Text(stringResource(R.string.openjev_chat_snippet)) }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(R.string.openjev_snippet_limit)) }, maxLines = 4)
        OutlinedButton(onClick = {
            openjevEmailSnippet = emailDraft
            openjevChatSnippet = chatDraft
            saveSetting("openjev_email_snippet", emailDraft)
            saveSetting("openjev_chat_snippet", chatDraft)
        }) { Text(stringResource(R.string.openjev_save_phrases)) }
    }
}
