import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

val releaseSigningEnvironment = mapOf(
    "storeFile" to System.getenv("PAUSECN_KEYSTORE_FILE"),
    "storePassword" to System.getenv("PAUSECN_KEYSTORE_PASSWORD"),
    "keyAlias" to System.getenv("PAUSECN_KEY_ALIAS"),
    "keyPassword" to System.getenv("PAUSECN_KEY_PASSWORD"),
)
val configuredReleaseSigningValues = releaseSigningEnvironment.filterValues { !it.isNullOrBlank() }
if (configuredReleaseSigningValues.isNotEmpty() && configuredReleaseSigningValues.size != releaseSigningEnvironment.size) {
    val missingVariables = releaseSigningEnvironment
        .filterValues { it.isNullOrBlank() }
        .keys
        .joinToString { key ->
            when (key) {
                "storeFile" -> "PAUSECN_KEYSTORE_FILE"
                "storePassword" -> "PAUSECN_KEYSTORE_PASSWORD"
                "keyAlias" -> "PAUSECN_KEY_ALIAS"
                else -> "PAUSECN_KEY_PASSWORD"
            }
        }
    throw GradleException("Release signing is only partially configured. Missing: $missingVariables")
}
val releaseSigningConfigured = configuredReleaseSigningValues.size == releaseSigningEnvironment.size

val publicReleaseMetadata = linkedMapOf(
    "operatorName" to System.getenv("PAUSECN_OPERATOR_NAME"),
    "privacyContact" to System.getenv("PAUSECN_PRIVACY_CONTACT"),
    "privacyPolicyUrl" to System.getenv("PAUSECN_PRIVACY_POLICY_URL"),
    "appFilingDisclosure" to System.getenv("PAUSECN_APP_FILING_DISCLOSURE"),
)

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n") + "\""

fun normalizedSha256(value: String): String = value
    .replace(Regex("[^0-9A-Fa-f]"), "")
    .uppercase(Locale.ROOT)

fun runForOutput(command: List<String>): Pair<Int, String> {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.pausecn"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pausecn"
        minSdk = 29
        targetSdk = 36
        versionCode = 41
        versionName = "0.2.0-alpha40-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField(
            "String",
            "OPERATOR_NAME",
            buildConfigString(publicReleaseMetadata["operatorName"] ?: "内部测试版本（禁止公开分发）"),
        )
        buildConfigField(
            "String",
            "PRIVACY_CONTACT",
            buildConfigString(publicReleaseMetadata["privacyContact"] ?: "未配置"),
        )
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            buildConfigString(publicReleaseMetadata["privacyPolicyUrl"] ?: ""),
        )
        buildConfigField(
            "String",
            "APP_FILING_DISCLOSURE",
            buildConfigString(publicReleaseMetadata["appFilingDisclosure"] ?: "未配置"),
        )
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("pauseCnRelease") {
                storeFile = rootProject.file(releaseSigningEnvironment.getValue("storeFile")!!)
                storePassword = releaseSigningEnvironment.getValue("storePassword")
                keyAlias = releaseSigningEnvironment.getValue("keyAlias")
                keyPassword = releaseSigningEnvironment.getValue("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("pauseCnRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}

val generatedDependencyInventory = layout.buildDirectory.file(
    "reports/dependencies/release-runtime.md",
)
val checkedInDependencyInventory = rootProject.layout.projectDirectory.file(
    "docs/DEPENDENCY_INVENTORY.md",
)
val generatedReleaseSbom = layout.buildDirectory.file(
    "reports/dependencies/bom.cdx.json",
)
val checkedInReleaseSbom = rootProject.layout.projectDirectory.file(
    "docs/SBOM.cdx.json",
)
val checkedInOsvScan = rootProject.layout.projectDirectory.file(
    "docs/OSV_SCAN_LATEST.json",
)
val checkedInLicenseInventory = rootProject.layout.projectDirectory.file(
    "docs/LICENSE_INVENTORY.json",
)
val roomDatabaseSource = layout.projectDirectory.file(
    "src/main/java/app/pausecn/data/PauseDatabase.kt",
)
val appContainerSource = layout.projectDirectory.file(
    "src/main/java/app/pausecn/data/AppContainer.kt",
)
val settingsStoreSource = layout.projectDirectory.file(
    "src/main/java/app/pausecn/data/SettingsStore.kt",
)
val roomMainSourceDirectory = layout.projectDirectory.dir("src/main/java")
val checkedInRoomSchemas = layout.projectDirectory.dir("schemas")

data class DeclaredLicense(
    val id: String,
    val name: String,
    val url: String,
    val declaredBy: String,
)

data class VerifiedArtifact(
    val name: String,
    val sha256: String,
)

fun releaseRuntimeModules(): List<ModuleComponentIdentifier> =
    configurations.getByName("releaseRuntimeClasspath")
        .incoming
        .resolutionResult
        .allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }
        .distinctBy { Triple(it.group, it.module, it.version) }
        .sortedWith(compareBy({ it.group }, { it.module }, { it.version }))

fun mavenPurl(module: ModuleComponentIdentifier): String =
    "pkg:maven/${module.group}/${module.module}@${module.version}"

fun resolvedModuleDependencies(component: ResolvedComponentResult): List<String> =
    component.dependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
        .map(::mavenPurl)
        .distinct()
        .sorted()

fun releaseDependencyGraph(applicationPurl: String): Map<String, List<String>> {
    val runtimeConfiguration = configurations.getByName("releaseRuntimeClasspath")
    val resolution = runtimeConfiguration.incoming.resolutionResult
    val declaredModules = runtimeConfiguration.allDependencies
        .mapNotNull { dependency ->
            dependency.group?.let { group -> group to dependency.name }
        }
        .toSet()
    val selectedModules = resolution.allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }
    val graph = linkedMapOf<String, List<String>>()
    graph[applicationPurl] = selectedModules
        .filter { (it.group to it.module) in declaredModules }
        .map(::mavenPurl)
        .distinct()
        .sorted()
    resolution.allComponents
        .mapNotNull { component ->
            val module = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
            mavenPurl(module) to resolvedModuleDependencies(component)
        }
        .sortedBy { it.first }
        .forEach { (purl, dependencies) -> graph[purl] = dependencies }
    return graph
}

fun renderReleaseDependencyInventory(): String {
    val modules = releaseRuntimeModules()

    return buildString {
        appendLine("# Release runtime dependency inventory")
        appendLine()
        appendLine("> Generated from Gradle configuration `:app:releaseRuntimeClasspath`. Do not edit by hand; regenerate with `./gradlew generateDependencyInventory`.")
        appendLine()
        appendLine("Resolved external modules: ${modules.size}")
        appendLine()
        appendLine("| Group | Module | Version |")
        appendLine("|---|---|---|")
        modules.forEach { module ->
            appendLine("| `${module.group}` | `${module.module}` | `${module.version}` |")
        }
    }
}

fun jsonString(value: String): String = buildConfigString(value)

fun deterministicUuid(value: String): UUID {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .copyOfRange(0, 16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long)
}

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun loadDeclaredLicenses(): Map<String, List<DeclaredLicense>> {
    if (!checkedInLicenseInventory.asFile.isFile) {
        throw GradleException("Missing docs/LICENSE_INVENTORY.json.")
    }
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parseText(checkedInLicenseInventory.asFile.readText()) as Map<String, Any?>
    val components = root["components"] as? List<*>
        ?: throw GradleException("License inventory does not contain components.")
    return components.associate { rawComponent ->
        val component = rawComponent as? Map<*, *>
            ?: throw GradleException("License inventory contains an invalid component.")
        val purl = component["purl"] as? String
            ?: throw GradleException("License inventory component is missing a PURL.")
        val rawLicenses = component["licenses"] as? List<*>
            ?: throw GradleException("License inventory component $purl is missing licenses.")
        purl to rawLicenses.map { rawLicense ->
            val license = rawLicense as? Map<*, *>
                ?: throw GradleException("License inventory component $purl has an invalid license.")
            DeclaredLicense(
                id = license["id"] as? String
                    ?: throw GradleException("License inventory component $purl is missing an SPDX id."),
                name = license["name"] as? String ?: "",
                url = license["url"] as? String ?: "",
                declaredBy = license["declaredBy"] as? String ?: purl,
            )
        }
    }
}

fun loadVerifiedRuntimeArtifacts(): Map<String, VerifiedArtifact> {
    val metadataFile = rootProject.file("gradle/verification-metadata.xml")
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val document = factory.newDocumentBuilder().parse(metadataFile)
    val result = mutableMapOf<String, VerifiedArtifact>()
    val components = document.getElementsByTagNameNS("*", "component")
    for (componentIndex in 0 until components.length) {
        val component = components.item(componentIndex) as Element
        val group = component.getAttribute("group")
        val name = component.getAttribute("name")
        val version = component.getAttribute("version")
        val exactNames = setOf("$name-$version.aar", "$name-$version.jar")
        val candidates = mutableListOf<VerifiedArtifact>()
        val artifacts = component.getElementsByTagNameNS("*", "artifact")
        for (artifactIndex in 0 until artifacts.length) {
            val artifact = artifacts.item(artifactIndex) as Element
            val artifactName = artifact.getAttribute("name")
            if (!artifactName.endsWith(".aar") && !artifactName.endsWith(".jar")) continue
            if (artifactName.endsWith("-sources.jar") || artifactName.endsWith("-javadoc.jar")) continue
            val hashes = artifact.getElementsByTagNameNS("*", "sha256")
            if (hashes.length == 0) continue
            val hash = (hashes.item(0) as Element).getAttribute("value")
            if (hash.length == 64) candidates += VerifiedArtifact(artifactName, hash.lowercase(Locale.ROOT))
        }
        val selected = candidates.sortedWith(
            compareBy<VerifiedArtifact>({ if (it.name in exactNames) 0 else 1 }, { it.name }),
        ).firstOrNull() ?: continue
        result["pkg:maven/$group/$name@$version"] = selected
    }
    return result
}

fun renderReleaseSbom(): String {
    val modules = releaseRuntimeModules()
    val declaredLicenses = loadDeclaredLicenses()
    val verifiedArtifacts = loadVerifiedRuntimeArtifacts()
    val version = android.defaultConfig.versionName
        ?: throw GradleException("versionName is required for the SBOM.")
    val applicationPurl = "pkg:generic/pausecn@$version"
    val dependencyGraph = releaseDependencyGraph(applicationPurl)
    val componentIdentity = modules.joinToString("\n") { "${it.group}:${it.module}:${it.version}" }
    val graphIdentity = dependencyGraph.entries.joinToString("\n") { (ref, dependencies) ->
        "$ref=${dependencies.joinToString(",")}"
    }
    val licenseIdentity = modules.joinToString("\n") { module ->
        val purl = mavenPurl(module)
        "$purl=${declaredLicenses[purl].orEmpty().joinToString(",") { it.id + ":" + it.declaredBy }}"
    }
    val artifactIdentity = modules.joinToString("\n") { module ->
        val purl = mavenPurl(module)
        "$purl=${verifiedArtifacts[purl]?.sha256.orEmpty()}"
    }
    val serialNumber = deterministicUuid(
        "$applicationPurl\n$componentIdentity\n$graphIdentity\n$licenseIdentity\n$artifactIdentity",
    )

    return buildString {
        appendLine("{")
        appendLine("  \"\$schema\": \"https://cyclonedx.org/schema/bom-1.7.schema.json\",")
        appendLine("  \"bomFormat\": \"CycloneDX\",")
        appendLine("  \"specVersion\": \"1.7\",")
        appendLine("  \"serialNumber\": \"urn:uuid:$serialNumber\",")
        appendLine("  \"version\": 1,")
        appendLine("  \"metadata\": {")
        appendLine("    \"component\": {")
        appendLine("      \"bom-ref\": ${jsonString(applicationPurl)},")
        appendLine("      \"type\": \"application\",")
        appendLine("      \"group\": \"app.pausecn\",")
        appendLine("      \"name\": \"pausecn\",")
        appendLine("      \"version\": ${jsonString(version)},")
        appendLine("      \"purl\": ${jsonString(applicationPurl)}")
        appendLine("    }")
        appendLine("  },")
        appendLine("  \"components\": [")
        modules.forEachIndexed { index, module ->
            val purl = mavenPurl(module)
            val licenses = declaredLicenses[purl]
                ?: throw GradleException("No reviewed declared license for $purl.")
            val verifiedArtifact = verifiedArtifacts[purl]
            appendLine("    {")
            appendLine("      \"bom-ref\": ${jsonString(purl)},")
            appendLine("      \"type\": \"library\",")
            appendLine("      \"group\": ${jsonString(module.group)},")
            appendLine("      \"name\": ${jsonString(module.module)},")
            appendLine("      \"version\": ${jsonString(module.version)},")
            if (verifiedArtifact != null) {
                appendLine("      \"hashes\": [")
                appendLine("        { \"alg\": \"SHA-256\", \"content\": ${jsonString(verifiedArtifact.sha256)} }")
                appendLine("      ],")
            }
            appendLine("      \"licenses\": [")
            licenses.forEachIndexed { licenseIndex, license ->
                append("        { \"license\": { \"id\": ${jsonString(license.id)}, \"acknowledgement\": \"declared\"")
                if (license.url.isNotBlank()) append(", \"url\": ${jsonString(license.url)}")
                append(" } }")
                appendLine(if (licenseIndex == licenses.lastIndex) "" else ",")
            }
            appendLine("      ],")
            appendLine("      \"purl\": ${jsonString(purl)},")
            appendLine("      \"properties\": [")
            val properties = buildList {
                if (verifiedArtifact != null) add("pausecn:verified-artifact" to verifiedArtifact.name)
                add("pausecn:license-declared-name" to licenses.joinToString(" | ") { it.name })
                add("pausecn:license-declared-by" to licenses.joinToString(" | ") { it.declaredBy })
            }
            properties.forEachIndexed { propertyIndex, (name, value) ->
                append("        { \"name\": ${jsonString(name)}, \"value\": ${jsonString(value)} }")
                appendLine(if (propertyIndex == properties.lastIndex) "" else ",")
            }
            appendLine("      ]")
            append("    }")
            appendLine(if (index == modules.lastIndex) "" else ",")
        }
        appendLine("  ],")
        appendLine("  \"dependencies\": [")
        dependencyGraph.entries.forEachIndexed { index, (ref, dependencies) ->
            appendLine("    {")
            append("      \"ref\": ${jsonString(ref)}, \"dependsOn\": [")
            dependencies.forEachIndexed { dependencyIndex, dependency ->
                if (dependencyIndex > 0) append(", ")
                append(jsonString(dependency))
            }
            appendLine("]")
            append("    }")
            appendLine(if (index == dependencyGraph.size - 1) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }
}

tasks.register("generateDependencyInventory") {
    group = "verification"
    description = "Generates the deterministic release runtime dependency inventory."
    outputs.file(generatedDependencyInventory)
    doLast {
        val output = generatedDependencyInventory.get().asFile
        output.parentFile.mkdirs()
        output.writeText(renderReleaseDependencyInventory())
    }
}

tasks.register("verifyDependencyInventory") {
    group = "verification"
    description = "Fails when the checked-in release dependency inventory is stale."
    dependsOn("generateDependencyInventory")
    inputs.file(checkedInDependencyInventory)
    inputs.file(generatedDependencyInventory)
    doLast {
        val checkedIn = checkedInDependencyInventory.asFile
        if (!checkedIn.isFile) {
            throw GradleException("Missing docs/DEPENDENCY_INVENTORY.md.")
        }
        val expected = generatedDependencyInventory.get().asFile.readText()
            .replace("\r\n", "\n")
            .trimEnd() + "\n"
        val actual = checkedIn.readText()
            .replace("\r\n", "\n")
            .trimEnd() + "\n"
        if (actual != expected) {
            throw GradleException(
                "Dependency inventory is stale. Run generateDependencyInventory, review app/build/reports/dependencies/release-runtime.md, and update docs/DEPENDENCY_INVENTORY.md.",
            )
        }
    }
}

tasks.register("verifyRoomSchemaPolicy") {
    group = "verification"
    description = "Verifies that the current Room schema is checked in and destructive migration is forbidden."
    inputs.file(roomDatabaseSource)
    inputs.file(appContainerSource)
    inputs.file(settingsStoreSource)
    inputs.dir(roomMainSourceDirectory)
    inputs.dir(checkedInRoomSchemas)
    doLast {
        val databaseSource = roomDatabaseSource.asFile.readText()
        val databaseVersion = Regex("""version\s*=\s*(\d+)""")
            .find(databaseSource)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: throw GradleException("Could not read the Room database version from PauseDatabase.kt.")
        if (!Regex("""exportSchema\s*=\s*true""").containsMatchIn(databaseSource)) {
            throw GradleException("PauseDatabase must keep exportSchema = true.")
        }

        val schemaFile = checkedInRoomSchemas.file(
            "app.pausecn.data.PauseDatabase/$databaseVersion.json",
        ).asFile
        if (!schemaFile.isFile) {
            throw GradleException(
                "Missing checked-in Room schema for database version $databaseVersion: ${schemaFile.relativeTo(projectDir)}",
            )
        }
        @Suppress("UNCHECKED_CAST")
        val schema = JsonSlurper().parseText(schemaFile.readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val database = schema["database"] as? Map<String, Any?>
            ?: throw GradleException("Room schema $databaseVersion is missing its database object.")
        if ((database["version"] as? Number)?.toInt() != databaseVersion) {
            throw GradleException("Room schema filename and embedded database version do not match.")
        }
        if ((database["identityHash"] as? String).isNullOrBlank()) {
            throw GradleException("Room schema $databaseVersion is missing its identity hash.")
        }

        (1..databaseVersion).forEach { version ->
            val historicalSchemaFile = checkedInRoomSchemas.file(
                "app.pausecn.data.PauseDatabase/$version.json",
            ).asFile
            if (!historicalSchemaFile.isFile) {
                throw GradleException("Missing checked-in historical Room schema version $version.")
            }
            @Suppress("UNCHECKED_CAST")
            val historicalSchema = JsonSlurper().parseText(historicalSchemaFile.readText()) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val historicalDatabase = historicalSchema["database"] as? Map<String, Any?>
                ?: throw GradleException("Room schema $version is missing its database object.")
            if ((historicalDatabase["version"] as? Number)?.toInt() != version) {
                throw GradleException("Historical Room schema $version has a mismatched embedded version.")
            }
        }

        val containerSource = appContainerSource.asFile.readText()
        if (!containerSource.contains("PreservingCorruptionOpenHelperFactory") ||
            !containerSource.contains("openHelperFactory")
        ) {
            throw GradleException(
                "AppContainer must install the non-destructive corruption-preserving Room factory.",
            )
        }
        if (databaseVersion > 1) {
            if (!containerSource.contains("addMigrations")) {
                throw GradleException("Room version $databaseVersion requires explicit migrations in AppContainer.")
            }
            val migrationSource = roomMainSourceDirectory.asFileTree
                .matching { include("**/*.kt", "**/*.java") }
                .files
                .joinToString("\n") { it.readText() }
            val migrationEdges = Regex("""Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
                .findAll(migrationSource)
                .map { match -> match.groupValues[1].toInt() to match.groupValues[2].toInt() }
                .toSet()
            val missingEdges = (1 until databaseVersion)
                .map { it to it + 1 }
                .filterNot(migrationEdges::contains)
            if (missingEdges.isNotEmpty()) {
                throw GradleException("Missing sequential Room migrations: $missingEdges")
            }
        }
        val settingsSource = settingsStoreSource.asFile.readText()
        if (!settingsSource.contains("PreferenceDataStoreFactory") ||
            !settingsSource.contains("ReplaceFileCorruptionHandler") ||
            !settingsSource.contains("preserveCorruptedSettings") ||
            settingsSource.contains("preferencesDataStore(")
        ) {
            throw GradleException(
                "SettingsStore must keep the verified-copy corruption handler instead of the default delegate.",
            )
        }

        val destructiveMigrationUses = roomMainSourceDirectory.asFileTree
            .matching { include("**/*.kt", "**/*.java") }
            .files
            .filter { source ->
                val sourceText = source.readText()
                sourceText.contains("fallbackToDestructiveMigration") ||
                    Regex("""allowDataLossOnRecovery\s*\(\s*true\s*\)""").containsMatchIn(sourceText)
            }
        if (destructiveMigrationUses.isNotEmpty()) {
            val locations = destructiveMigrationUses.joinToString { it.relativeTo(projectDir).path }
            throw GradleException(
                "Destructive Room migration is forbidden because it can silently erase user data: $locations",
            )
        }
    }
}

tasks.register("verifyLicenseInventory") {
    group = "verification"
    description = "Verifies complete, SPDX-mapped declared licenses for the release runtime."
    inputs.file(checkedInLicenseInventory)
    doLast {
        val expectedPurls = releaseRuntimeModules().map(::mavenPurl).sorted()
        val expectedIdentity = sha256(expectedPurls.joinToString("\n"))
        @Suppress("UNCHECKED_CAST")
        val inventory = JsonSlurper().parseText(checkedInLicenseInventory.asFile.readText()) as Map<String, Any?>
        if (inventory["componentSetSha256"] != expectedIdentity) {
            throw GradleException("The license inventory does not match releaseRuntimeClasspath.")
        }
        val count = (inventory["componentCount"] as? Number)?.toInt()
        val declaredCoverage = (inventory["declaredLicenseCoverage"] as? Number)?.toInt()
        val spdxCoverage = (inventory["spdxMappedCoverage"] as? Number)?.toInt()
        if (count != expectedPurls.size || declaredCoverage != count || spdxCoverage != count) {
            throw GradleException("The license inventory does not have complete declared and SPDX-mapped coverage.")
        }
        val licensesByPurl = loadDeclaredLicenses()
        if (licensesByPurl.keys.sorted() != expectedPurls) {
            throw GradleException("The license inventory PURL set does not match releaseRuntimeClasspath.")
        }
        licensesByPurl.forEach { (purl, licenses) ->
            if (licenses.isEmpty() || licenses.any { it.id.isBlank() || it.declaredBy.isBlank() }) {
                throw GradleException("The license inventory has an incomplete declaration for $purl.")
            }
        }
    }
}

tasks.register("generateReleaseSbom") {
    group = "verification"
    description = "Generates a deterministic CycloneDX 1.7 SBOM for the release runtime."
    inputs.file(checkedInLicenseInventory)
    inputs.file(rootProject.layout.projectDirectory.file("gradle/verification-metadata.xml"))
    outputs.file(generatedReleaseSbom)
    doLast {
        val output = generatedReleaseSbom.get().asFile
        output.parentFile.mkdirs()
        output.writeText(renderReleaseSbom())
    }
}

tasks.register("verifyReleaseSbom") {
    group = "verification"
    description = "Fails when the checked-in CycloneDX release SBOM is stale."
    dependsOn("verifyLicenseInventory", "generateReleaseSbom")
    inputs.file(checkedInReleaseSbom)
    inputs.file(generatedReleaseSbom)
    doLast {
        val checkedIn = checkedInReleaseSbom.asFile
        if (!checkedIn.isFile) {
            throw GradleException("Missing docs/SBOM.cdx.json.")
        }
        val expected = generatedReleaseSbom.get().asFile.readText()
            .replace("\r\n", "\n")
            .trimEnd() + "\n"
        val actual = checkedIn.readText()
            .replace("\r\n", "\n")
            .trimEnd() + "\n"
        if (actual != expected) {
            throw GradleException(
                "Release SBOM is stale. Run generateReleaseSbom, review app/build/reports/dependencies/bom.cdx.json, and update docs/SBOM.cdx.json.",
            )
        }
    }
}

tasks.register("verifyVulnerabilityReview") {
    group = "verification"
    description = "Verifies that the zero-finding OSV snapshot is current and bound to the release SBOM."
    dependsOn("verifyReleaseSbom")
    inputs.file(checkedInReleaseSbom)
    inputs.file(checkedInOsvScan)
    doLast {
        val sbomFile = checkedInReleaseSbom.asFile
        val scanFile = checkedInOsvScan.asFile
        if (!scanFile.isFile) {
            throw GradleException("Missing docs/OSV_SCAN_LATEST.json.")
        }

        @Suppress("UNCHECKED_CAST")
        val sbom = JsonSlurper().parseText(sbomFile.readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val scan = JsonSlurper().parseText(scanFile.readText()) as Map<String, Any?>
        val expectedHash = sha256(sbomFile)
        if (scan["sbomSha256"] != expectedHash) {
            throw GradleException("The OSV snapshot does not match the checked-in release SBOM.")
        }
        if (scan["bomSerialNumber"] != sbom["serialNumber"]) {
            throw GradleException("The OSV snapshot serial number does not match the release SBOM.")
        }

        val componentCount = (scan["componentCount"] as? Number)?.toInt()
        if (componentCount != releaseRuntimeModules().size) {
            throw GradleException("The OSV snapshot component count does not match releaseRuntimeClasspath.")
        }
        val affected = (scan["affectedComponentCount"] as? Number)?.toInt()
        val records = (scan["vulnerabilityRecordCount"] as? Number)?.toInt()
        val findings = scan["findings"] as? List<*>
        if (affected != 0 || records != 0 || findings == null || findings.isNotEmpty()) {
            throw GradleException("The OSV snapshot contains unresolved vulnerability findings.")
        }

        val scannedAt = try {
            Instant.parse(scan["scannedAtUtc"] as String)
        } catch (exception: Exception) {
            throw GradleException("The OSV snapshot has an invalid scannedAtUtc value.", exception)
        }
        val now = Instant.now()
        if (scannedAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw GradleException("The OSV snapshot timestamp is unexpectedly in the future.")
        }
        if (scannedAt.isBefore(now.minus(Duration.ofDays(30)))) {
            throw GradleException("The OSV snapshot is older than 30 days; run scripts/scan-osv.ps1 again.")
        }
    }
}

tasks.register("verifyPublicReleaseReadiness") {
    group = "verification"
    description = "Builds and verifies a signed, metadata-complete public release candidate."
    dependsOn(
        "lintDebug",
        "testDebugUnitTest",
        "verifyDependencyInventory",
        "verifyRoomSchemaPolicy",
        "verifyLicenseInventory",
        "verifyReleaseSbom",
        "verifyVulnerabilityReview",
        "assembleRelease",
        "bundleRelease",
    )

    doLast {
        val missingMetadata = publicReleaseMetadata
            .filterValues { it.isNullOrBlank() }
            .keys
            .joinToString()
        if (missingMetadata.isNotEmpty()) {
            throw GradleException("Public release metadata is incomplete: $missingMetadata")
        }
        if (!releaseSigningConfigured) {
            throw GradleException("Public release signing is not configured.")
        }

        val privacyPolicyUrl = publicReleaseMetadata.getValue("privacyPolicyUrl")!!.trim()
        val privacyPolicyUri = runCatching { URI(privacyPolicyUrl) }.getOrNull()
        if (
            privacyPolicyUri?.scheme?.equals("https", ignoreCase = true) != true ||
            privacyPolicyUri.host.isNullOrBlank()
        ) {
            throw GradleException("PAUSECN_PRIVACY_POLICY_URL must be a valid HTTPS URL with a host.")
        }

        val expectedCertificate = normalizedSha256(
            System.getenv("PAUSECN_SIGNING_CERT_SHA256")
                ?: throw GradleException("PAUSECN_SIGNING_CERT_SHA256 is required."),
        )
        if (expectedCertificate.length != 64) {
            throw GradleException("PAUSECN_SIGNING_CERT_SHA256 must contain one SHA-256 fingerprint.")
        }

        val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val releaseAab = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        if (!releaseApk.isFile || !releaseAab.isFile) {
            throw GradleException("Signed release APK/AAB was not produced.")
        }

        val sdkRoot = sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?: throw GradleException("ANDROID_HOME or ANDROID_SDK_ROOT is required for release verification.")
        val windows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val buildTools = file("$sdkRoot/build-tools/${android.buildToolsVersion}")
        val apkSigner = buildTools.resolve(if (windows) "apksigner.bat" else "apksigner")
        val aapt2 = buildTools.resolve(if (windows) "aapt2.exe" else "aapt2")
        if (!apkSigner.isFile || !aapt2.isFile) {
            throw GradleException("Android build tools ${android.buildToolsVersion} are incomplete.")
        }

        val (verifyCode, verifyOutput) = runForOutput(
            listOf(apkSigner.absolutePath, "verify", "--verbose", "--print-certs", releaseApk.absolutePath),
        )
        if (verifyCode != 0 || !verifyOutput.contains("Verifies")) {
            throw GradleException("APK signature verification failed:\n$verifyOutput")
        }
        val actualCertificate = Regex(
            "certificate SHA-256 digest:\\s*([0-9a-fA-F: ]+)",
            RegexOption.IGNORE_CASE,
        ).find(verifyOutput)?.groupValues?.get(1)?.let(::normalizedSha256)
            ?: throw GradleException("Could not read the APK signing certificate fingerprint.")
        if (actualCertificate != expectedCertificate) {
            throw GradleException("APK certificate fingerprint does not match the release ledger.")
        }

        val (permissionCode, permissionOutput) = runForOutput(
            listOf(aapt2.absolutePath, "dump", "permissions", releaseApk.absolutePath),
        )
        if (permissionCode != 0) {
            throw GradleException("Could not inspect APK permissions:\n$permissionOutput")
        }
        val allowedPlatformPermissions = file("src/main/assets/platform-permissions.txt")
            .readLines().map(String::trim).filter(String::isNotEmpty).toSet()
        val actualPlatformPermissions = Regex("android\\.permission\\.[A-Z_]+")
            .findAll(permissionOutput).map { it.value }.toSet()
        if (actualPlatformPermissions != allowedPlatformPermissions) {
            throw GradleException("Platform permissions differ from the shared allowlist: $actualPlatformPermissions")
        }

        val javaHome = file(System.getProperty("java.home"))
        val jarSigner = javaHome.resolve("bin/${if (windows) "jarsigner.exe" else "jarsigner"}")
        val bundleSignatureEntries = ZipFile(releaseAab).use { zip ->
            zip.entries().asSequence()
                .map { it.name.uppercase(Locale.ROOT) }
                .filter { name ->
                    name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
                }
                .toList()
        }
        if (bundleSignatureEntries.none { it.endsWith(".SF") } ||
            bundleSignatureEntries.none { it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC") }
        ) {
            throw GradleException("AAB does not contain a complete JAR signature.")
        }
        val (bundleCode, bundleOutput) = runForOutput(
            listOf(jarSigner.absolutePath, "-verify", releaseAab.absolutePath),
        )
        if (bundleCode != 0) {
            throw GradleException("AAB signature verification failed:\n$bundleOutput")
        }
    }
}

tasks.named("check") {
    dependsOn("verifyRoomSchemaPolicy")
}
