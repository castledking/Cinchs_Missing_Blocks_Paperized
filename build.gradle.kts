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
    // The jar is Java 21 bytecode (options.release below), so it runs on Paper 1.21.x
    // (Java 21) and 26.x (Java 25). The toolchain is 25 only because the newest-API
    // verification compile reads paper-api 26.x, whose class files are Java 25.
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
    // GriefPrevention, upstream and the 3D fork, is reached by reflection. 7.0.17 is the
    // newest WorldGuard built for Java 21 (7.0.19 is Java 25); the query API Protection
    // calls is the same in both.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17")

    // Unit tests run without a server: paper-api supplies the Bukkit types the classes
    // under test load, nothing more.
    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.add("-Xlint:all,-serial,-processing")
}

// --- the supported range, checked at both ends -----------------------------------
// main compiles against one Paper API; these compile the same sources against the two
// ends of the supported range and fail `check` if either breaks. Each end gets the
// WorldGuard that era's servers run: 7.0.17 pins Guava and Gson strictly below what
// paper-api 26.3 needs, so the two do not resolve together, and 26.3 runs 7.0.19 anyway.
fun verifyApi(name: String, paperApi: String, worldGuard: String, what: String) {
    val classpath = configurations.create("${name}Classpath") {
        isCanBeConsumed = false
        isTransitive = true
    }
    dependencies {
        add(classpath.name, "io.papermc.paper:paper-api:$paperApi")
        add(classpath.name, "net.momirealms:craft-engine-core:${property("craftEngineVersion")}")
        add(classpath.name, "net.momirealms:craft-engine-bukkit:${property("craftEngineVersion")}")
        add(classpath.name, "net.momirealms:craft-engine-bukkit-proxy:${property("craftEngineVersion")}")
        add(classpath.name, "com.sk89q.worldguard:worldguard-bukkit:$worldGuard")
    }
    val task = tasks.register<JavaCompile>(name) {
        group = "verification"
        description = "Compiles main against paper-api $paperApi: $what"
        source = sourceSets.main.get().java
        this.classpath = classpath
        destinationDirectory = layout.buildDirectory.dir("verify/$name")
        javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
        // A check for errors, not a second warnings report: main's own compile lints.
        options.compilerArgs = listOf("-Xlint:none", "-nowarn")
    }
    tasks.named("check") { dependsOn(task) }
}

verifyApi("verifyFloorApi", property("floorPaperApiVersion") as String, "7.0.17",
          "fails on API newer than the oldest supported Paper")
verifyApi("verifyNewestApi", property("newestPaperApiVersion") as String, "7.0.19",
          "fails on API the newest supported Paper has removed")

// --- the bundled pack --------------------------------------------------------
// The plugin installs the CraftEngine pack in pack/ (PackInstaller), so a server only
// installs the jar. pack/ is committed: building the jar needs nothing but Java.
//
// pack/ is generated output. On the development machine it is regenerated from the
// pinned upstream mod with the Python generator kept beside this repository (../tools,
// not part of it) - `./gradlew regeneratePack` - after the upstream pin moves or the
// generator changes. Everywhere else that task is simply absent.
val generator: File = rootDir.parentFile.resolve("tools/generate_pack.py")

if (generator.isFile) {
    val python: String = (findProperty("python") as String?) ?: "python3"
    val workspace: File = rootDir.parentFile
    val upstreamTree = layout.buildDirectory.dir("upstream")
    val generated = layout.buildDirectory.dir("generated-pack")

    val fetchUpstream by tasks.registering(Exec::class) {
        description = "Fetch the pinned upstream mod (GitHub, else the local fallback)"
        val offline = (findProperty("upstream.offline") as String?)?.toBoolean() ?: false
        commandLine(listOf(python, workspace.resolve("tools/upstream.py").path, "fetch",
                           "--out", upstreamTree.get().asFile.path) + if (offline) listOf("--offline") else listOf())
    }

    tasks.register<Exec>("regeneratePack") {
        group = "build"
        description = "Regenerate pack/ from the pinned upstream (needs ../tools and python3)"
        dependsOn(fetchUpstream)
        val log = layout.buildDirectory.file("generate-pack.log")
        doFirst { standardOutput = log.get().asFile.also { it.parentFile.mkdirs() }.outputStream() }
        commandLine(python, generator.path,
                    "--mod", upstreamTree.get().asFile.path,
                    "--out", generated.get().asFile.path,
                    "--mc-version", "26.3",
                    "--build-config", workspace.resolve("tools/build-config.release.yml").path)
        doLast {
            val root = generated.get().asFile
            val files = root.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .filter { bundled(it) }
                .sorted().toList()
            val pack = file("pack")
            pack.deleteRecursively()
            files.forEach { rel -> root.resolve(rel).copyTo(pack.resolve(rel), overwrite = true) }
            // The installer can't list a jar directory, so the pack ships with an index.
            pack.resolve("index.txt").writeText(files.joinToString("\n", postfix = "\n"))
            // The pin it was built from, for the upstream-watch workflow.
            val pin = workspace.resolve("upstream/upstream.properties").readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
            pack.resolve("upstream.properties").writeText(
                "# The upstream commit pack/ was generated from (`./gradlew regeneratePack`).\n" +
                pin.joinToString("\n", postfix = "\n"))
            logger.lifecycle("pack/: ${files.size} files regenerated")
        }
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
    from("pack") {
        into("pack")
        exclude("upstream.properties")
    }
}

// --- the README's counts, derived from the pack -----------------------------------
// The README states how many pieces the pack holds. Those numbers were hand-corrected
// twice and were still wrong, so they are now re-derived from pack/intermediate/pieces.json
// on every `check`, and the build fails if the README disagrees.
//
//   blocks     - ids in the block tree
//   furniture  - ids only in the furniture tree: a doubled slab ships as both a block and
//                furniture, and is one piece, counted as a block
//   pieces     - blocks + furniture
//   items      - ids in the item tree
//
// Recipes are left out: their count moves with every recipe feature the generator gains,
// which is a change to the pack, not drift in the README.
val verifyPackCounts by tasks.registering {
    group = "verification"
    description = "Fails if README.md's piece counts differ from the committed pack's"
    val manifest = file("pack/intermediate/pieces.json")
    val readme = file("README.md")
    inputs.files(manifest, readme)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val tree = groovy.json.JsonSlurper().parse(manifest) as Map<String, Map<String, Any>>
        val blocks = tree.getValue("blocks").keys
        val furniture = tree.getValue("furniture").keys - blocks
        val expected = linkedMapOf(
            "pieces" to blocks.size + furniture.size,
            "blocks" to blocks.size,
            "furniture" to furniture.size,
            "items" to tree.getValue("items").size,
        )
        val text = readme.readText()
        val wrong = expected.mapNotNull { (what, n) ->
            val claimed = Regex("""\*\*([\d,]+)\s+$what\*\*""").findAll(text).map { it.groupValues[1].replace(",", "").toInt() }.toList()
            when {
                claimed.isEmpty() -> "README.md never states the number of $what (the pack has $n)"
                claimed.any { it != n } -> "README.md says ${claimed.joinToString()} $what; the pack has $n"
                else -> null
            }
        }
        if (wrong.isNotEmpty()) {
            throw GradleException(wrong.joinToString("\n"))
        }
        logger.lifecycle("README counts match the pack: " + expected.entries.joinToString { "${it.value} ${it.key}" })
    }
}
tasks.named("check") { dependsOn(verifyPackCounts) }
