package com.l0gic0l.anduino.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors the JSON printed by `arduino-cli ... --format json` (arduino-cli 1.x,
// internal/cli/feedback/result/rpc.go). Every field has a default because the CLI
// omits empty values.

@Serializable
data class CoreSearchResult(val platforms: List<PlatformSummary> = emptyList())

@Serializable
data class PlatformSummary(
    val id: String = "",
    val maintainer: String = "",
    val website: String = "",
    val email: String = "",
    @SerialName("manually_installed") val manuallyInstalled: Boolean = false,
    val deprecated: Boolean = false,
    val indexed: Boolean = false,
    val releases: Map<String, PlatformRelease> = emptyMap(),
    @SerialName("installed_version") val installedVersion: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
) {
    val installed: String? get() = installedVersion?.takeIf { it.isNotBlank() }
    val latest: String? get() = latestVersion?.takeIf { it.isNotBlank() }
    val name: String
        get() = (releases[latest ?: ""] ?: releases[installed ?: ""] ?: releases.values.lastOrNull())?.name ?: id
    val updatable: Boolean get() = installed != null && latest != null && installed != latest
}

@Serializable
data class PlatformRelease(
    val name: String = "",
    val version: String = "",
    val types: List<String> = emptyList(),
    val installed: Boolean = false,
    val boards: List<BoardRef> = emptyList(),
    val deprecated: Boolean = false,
    val compatible: Boolean = true,
)

@Serializable
data class BoardRef(val name: String = "", val fqbn: String = "")

@Serializable
data class BoardListAllResult(val boards: List<BoardListItem> = emptyList())

@Serializable
data class BoardListItem(
    val name: String = "",
    val fqbn: String = "",
    @SerialName("is_hidden") val isHidden: Boolean = false,
    val platform: BoardPlatformInfo? = null,
)

@Serializable
data class BoardPlatformInfo(val metadata: PlatformMetadata? = null, val release: PlatformRelease? = null)

@Serializable
data class PlatformMetadata(val id: String = "", val maintainer: String = "")

@Serializable
data class BoardDetails(
    val fqbn: String = "",
    val name: String = "",
    val version: String = "",
    @SerialName("config_options") val configOptions: List<ConfigOption> = emptyList(),
)

@Serializable
data class ConfigOption(
    val option: String = "",
    @SerialName("option_label") val optionLabel: String = "",
    val values: List<ConfigValue> = emptyList(),
)

@Serializable
data class ConfigValue(
    val value: String = "",
    @SerialName("value_label") val valueLabel: String = "",
    val selected: Boolean = false,
)

@Serializable
data class LibSearchResult(val libraries: List<SearchedLibrary> = emptyList())

@Serializable
data class SearchedLibrary(
    val name: String = "",
    val latest: LibraryRelease? = null,
    @SerialName("available_versions") val availableVersions: List<String> = emptyList(),
)

@Serializable
data class LibraryRelease(
    val author: String = "",
    val version: String = "",
    val maintainer: String = "",
    val sentence: String = "",
    val paragraph: String = "",
    val website: String = "",
    val category: String = "",
    val architectures: List<String> = emptyList(),
    val types: List<String> = emptyList(),
)

@Serializable
data class LibListResult(@SerialName("installed_libraries") val installedLibraries: List<InstalledLibrary> = emptyList())

@Serializable
data class InstalledLibrary(val library: Library? = null, val release: LibraryRelease? = null)

@Serializable
data class Library(
    val name: String = "",
    val author: String = "",
    val maintainer: String = "",
    val sentence: String = "",
    val website: String = "",
    val category: String = "",
    val version: String = "",
    val location: String = "",
    @SerialName("install_dir") val installDir: String = "",
    @SerialName("container_platform") val containerPlatform: String = "",
    val examples: List<String> = emptyList(),
)

@Serializable
data class LibExamplesResult(val examples: List<LibraryExamples> = emptyList())

@Serializable
data class LibraryExamples(val library: Library? = null, val examples: List<String> = emptyList())
