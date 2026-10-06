plugins {
    java
}

group = property("group") as String
version = property("version") as String

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://maven.enginehub.org/repo/")
}

java {
    // Paper 26.3 requires JVM 25+, so the toolchain follows the server rather than
    // an older habit. 21 is still fine for the language level itself.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")

    // CraftEngine is compileOnly: the server provides it. The core artifact holds
    // BlockBehaviors.register and the behaviour/property/context types; bukkit holds
    // BukkitBlockBehavior, which is the base class the built-in behaviours extend.
    // The proxy artifact is the NMS access layer those base classes are written
    // against, so it is needed on the compile classpath even though we never touch
    // it directly.
    compileOnly("net.momirealms:craft-engine-core:${property("craftEngineVersion")}")
    compileOnly("net.momirealms:craft-engine-bukkit:${property("craftEngineVersion")}")
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:${property("craftEngineVersion")}")

    // Optional land protection (Protection): only touched when installed on the server.
    // GriefPrevention, upstream and the 3D fork, is reached by reflection.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.19")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-Xlint:all,-serial,-processing")
}

// --- the bundled pack --------------------------------------------------------
// The plugin installs a CraftEngine pack bundled in the jar (PackInstaller), so a
// server only installs the jar. It is generated here, at build time, from the upstream
// mod pinned in upstream/upstream.properties: fetched from GitHub, or from the
// verified fallback copy in upstream/ when GitHub can't be reached
// (-Pupstream.offline=true skips the attempt). Needs python3 with PyYAML and Pillow.
// Where the generator lives: tools/ and warped_netherwart_art/.
//
// Two layouts, both real. In the working repository this module sits beside them, so they
// are one directory up. In the published repository the contents of this directory ARE the
// root, and they sit right here. Detecting rather than assuming is what lets one copy of
// this file build in both, instead of the two drifting apart.
val repoRoot: File = sequenceOf(rootDir, rootDir.parentFile)
    .firstOrNull { File(it, "tools").isDirectory }
    ?: error("tools/ not found beside ${rootDir.name} or in it; the pack cannot be generated")
val python: String = (findProperty("python") as String?) ?: "python3"
val upstreamTree = layout.buildDirectory.dir("upstream")
val bundledPack = layout.buildDirectory.dir("bundled-pack")

val fetchUpstream by tasks.registering(Exec::class) {
    description = "Fetch the pinned upstream mod (GitHub, else the local fallback)"
    inputs.dir(repoRoot.resolve("upstream"))
    inputs.file(repoRoot.resolve("tools/upstream.py"))
    outputs.dir(upstreamTree)
    val offline = (findProperty("upstream.offline") as String?)?.toBoolean() ?: false
    commandLine(listOf(python, repoRoot.resolve("tools/upstream.py").path, "fetch",
                       "--out", upstreamTree.get().asFile.path) + if (offline) listOf("--offline") else listOf())
}

// The vanilla reference data the generator reads: every vanilla blockstate, and every
// model those reference transitively.
//
// It is derived, it is about 5,000 files, and it is gitignored -- so a fresh clone has
// none of it, and the generator fails with "missing wall template ...". That is the whole
// reason this task exists: the build fetches what it needs rather than assuming someone
// already ran the fetch by hand.
val vanillaCache = repoRoot.resolve("tools/vanilla_templates")

val fetchVanilla by tasks.registering(Exec::class) {
    description = "Fetch the vanilla blockstates and models the generator reads"
    // Only when absent. Re-fetching 5,000 files on every build would make the build depend
    // on the network for no reason, which is what -Pupstream.offline exists to avoid.
    onlyIf { !vanillaCache.resolve("template_wall_post.json").isFile }
    outputs.dir(vanillaCache)
    commandLine(python, repoRoot.resolve("tools/fetch_vanilla.py").path, "--version", "26.3")
}

val generatePack by tasks.registering(Exec::class) {
    description = "Generate the bundled pack: everything on (tools/build-config.release.yml)"
    dependsOn(fetchUpstream, fetchVanilla)
    inputs.files(fileTree(repoRoot.resolve("tools")) { exclude("**/__pycache__/**") })
    inputs.dir(repoRoot.resolve("warped_netherwart_art"))
    inputs.dir(upstreamTree)
    outputs.dir(bundledPack)
    // The generator's summary goes to a log rather than the Gradle console; a failed
    // build still prints its reason (stderr) and fails the task.
    val log = layout.buildDirectory.file("generate-pack.log")
    doFirst { standardOutput = log.get().asFile.also { it.parentFile.mkdirs() }.outputStream() }
    commandLine(python, repoRoot.resolve("tools/generate_pack.py").path,
                "--mod", upstreamTree.get().asFile.path,
                "--out", bundledPack.get().asFile.path,
                "--mc-version", "26.3",
                "--build-config", repoRoot.resolve("tools/build-config.release.yml").path)
    // The installer can't list a jar directory, so the pack ships with its own index:
    // every bundled file, relative to pack/.
    doLast {
        val root = bundledPack.get().asFile
        val files = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { bundled(it) }
            .sorted().toList()
        root.resolve("index.txt").writeText(files.joinToString("\n", postfix = "\n"))
    }
}

/** What the jar carries: the pack itself and the two files the plugin reads. */
fun bundled(path: String): Boolean =
    (path == "pack.yml" || path.startsWith("configuration/") || path.startsWith("resourcepack/")
        || path == "intermediate/mining.json" || path == "intermediate/pieces.json")
        // The generator's notes on its build settings; plugins/CMB/config.yml is the
        // config now, and these would describe the release build, not this server.
        && path != "configuration/config.yml"

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version, "description" to "Cinch's Missing Blocks companion behaviours")
    }
    dependsOn(generatePack)
    from(bundledPack) {
        into("pack")
        include("index.txt", "pack.yml", "configuration/**", "resourcepack/**",
                "intermediate/mining.json", "intermediate/pieces.json")
        exclude("configuration/config.yml")
    }
}
