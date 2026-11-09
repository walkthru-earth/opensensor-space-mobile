package space.opensensor.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.FloatingIconButton
import space.opensensor.designsystem.FloatingSurface
import space.opensensor.map.resources.Res
import space.opensensor.map.resources.map_no_places
import space.opensensor.map.resources.map_search_clear
import space.opensensor.map.resources.map_search_places

/**
 * Place search over the bundled list ([PlaceSearch]), a compact floating search button that opens
 * into a full width field with its matches below. Picking a place calls [onPlace] and folds the field again.
 * The caller only moves the map with it. Nothing typed leaves the phone.
 */
@Composable
fun PlaceSearchField(onPlace: (Place) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.map_search_places)
    var open by remember { mutableStateOf(false) }
    if (!open) {
        FloatingIconButton(onClick = { open = true }, contentDescription = label, modifier = modifier) {
            Icon(MapIcons.Search, contentDescription = null)
        }
        return
    }
    val index = rememberPlaceIndex()
    var query by remember { mutableStateOf("") }
    val results by produceState(emptyList<Place>(), query, index) {
        value = index?.let { withContext(Dispatchers.Default) { it.search(query) } }.orEmpty()
    }
    val focus = LocalFocusManager.current
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val locale = Locale.current
    val arabic = locale.language == "ar"
    Column(modifier.fillMaxWidth()) {
        FloatingSurface(Modifier.fillMaxWidth()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(label) },
                leadingIcon = { Icon(MapIcons.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = {
                        query = ""
                        open = false
                        focus.clearFocus()
                    }) { Icon(MapIcons.Close, contentDescription = stringResource(Res.string.map_search_clear)) }
                },
                singleLine = true,
                shape = FloatingControl.Shape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = FloatingControl.Size).focusRequester(field),
            )
        }
        if (query.isNotBlank() && index != null) {
            FloatingSurface(Modifier.padding(top = FloatingControl.Inset).fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (results.isEmpty()) {
                        Text(stringResource(Res.string.map_no_places), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    results.forEach { place ->
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(min = FloatingControl.Size)
                                .clickable(role = Role.Button) {
                                    query = ""
                                    open = false
                                    focus.clearFocus()
                                    onPlace(place)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(place.label(arabic), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                countryName(place.country, locale.toLanguageTag()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
