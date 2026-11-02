import org.jetbrains.gradle.ext.Gradle
import org.jetbrains.gradle.ext.compiler
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    id("java")
    id("java-library")
    id("maven-publish")
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.7"
    id("eclipse")
    id("com.gtnewhorizons.retrofuturagradle") version "2.0.6"
    id("com.matthewprenger.cursegradle") version "1.4.0"
}

@Suppress("PropertyName")
val mod_version: String by project
@Suppress("PropertyName")
val maven_group: String by project
@Suppress("PropertyName")
val mod_id: String by project
@Suppress("PropertyName")
val archives_base_name: String by project

@Suppress("PropertyName")
val use_access_transformer: String by project

@Suppress("PropertyName")
val use_mixins: String by project
@Suppress("PropertyName")
val use_coremod: String by project
@Suppress("PropertyName")
val use_assetmover: String by project

@Suppress("PropertyName")
val include_mod: String by project
@Suppress("PropertyName")
val coremod_plugin_class_name: String by project

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    // Generate sources and javadocs jars when building and publishing
    withSourcesJar()
    // withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

configurations {
    val embed = create("embed")
    implementation.configure {
        extendsFrom(embed)
    }
}

minecraft {
    mcVersion.set("1.12.2")

    // MCP Mappings
    mcpMappingChannel.set("stable")
    mcpMappingVersion.set("39")

    // Set username here, the UUID will be looked up automatically
    username.set("tom500101")

    // Add any additional tweaker classes here
    // extraTweakClasses.add("org.spongepowered.asm.launch.MixinTweaker")

    // Add various JVM arguments here for runtime
    val args = mutableListOf("-ea:${group}")
    if (use_coremod.toBoolean()) {
        args += "-Dfml.coreMods.load=$coremod_plugin_class_name"
    }
    if (use_mixins.toBoolean()) {
        args += "-Dmixin.hotSwap=true"
        args += "-Dmixin.checks.interfaces=true"
        args += "-Dmixin.debug.export=true"
    }
    // Runs the stage-1 structure probe automatically on server start when the build is invoked as
    // `gradlew runServer -Pprobe`. A dedicated server cannot be typed into from a scripted shell, so
    // this is how the probe gets exercised without a player.
    if (project.hasProperty("probe")) {
        args += "-Drlcompass.probe=true"
    }
    // Runs the locator self-test (one search per registered structure) on server start:
    // `gradlew runServer -Pselftest`.
    if (project.hasProperty("selftest")) {
        args += "-Drlcompass.selftest=true"
    }
    extraRunJvmArguments.addAll(args)

    // Include and use dependencies' Access Transformer files
    useDependencyAccessTransformers.set(true)

    // Add any properties you want to swap out for a dynamic value at build time here
    // Any properties here will be added to a class at build time, the name can be configured below
    // Example:
    injectedTags.put("VERSION", mod_version)
    injectedTags.put("MOD_ID", mod_id)
    injectedTags.put("MOD_NAME", "RLcraft Explorer's Compass")
}

// Generate a group.archives_base_name.Tags class
tasks.injectTags.configure {
    // Change Tags class' name here. maven_group is already "com.tom500101.rlcompass", so do not
    // append archives_base_name again — that produced com.tom500101.rlcompass.rlcompass.Tags.
    outputClassName.set("${maven_group}.Tags")
}

// Gradle 9 enforces task-dependency validation strictly, but RFG 2.0.x leaves its generated
// source sets unwired to the tasks that produce them, which aborts the build with e.g.
//   "Task ':compileInjectedTagsKotlin' uses this output of task ':injectTags' without declaring
//    an explicit or implicit dependency"
// Declare every producer -> consumer pair up front.
listOf(
    "compileInjectedTagsJava" to "injectTags",
    "compilePatchedMcJava" to "decompressDecompiledSources",
    "compileMcLauncherJava" to "createMcLauncherFiles",
).forEach { (consumer, producer) ->
    tasks.matching { it.name == consumer }.configureEach { dependsOn(producer) }
}

repositories {
    maven {
        name = "CleanroomMC Maven"
        url = uri("https://maven.cleanroommc.com")
    }
    maven {
        name = "SpongePowered Maven"
        url = uri("https://repo.spongepowered.org/maven")
    }
    maven {
        name = "CurseMaven"
        url = uri("https://cursemaven.com")
        content {
            includeGroup("curse.maven")
        }
    }
    mavenLocal() // Must be last for caching to work
}

dependencies {
    if (use_assetmover.toBoolean()) {
        implementation("com.cleanroommc:assetmover:2.5")
    }
    if (use_mixins.toBoolean()) {
        implementation("zone.rong:mixinbooter:7.1")
    }

    // RLCraft 模组依赖：从项目根目录的 libs/ 文件夹加载所有 jar 并反混淆
    // 把 Recurrent Complex、Battle Towers、Roguelike Dungeons 等 jar 放进 libs/ 即可
    val libsFileTree = fileTree("libs") { include("*.jar") }
    if (!libsFileTree.isEmpty) {
        implementation(rfg.deobf(libsFileTree))
    }

    if (use_mixins.toBoolean()) {
        // Change your mixin refmap name here:
        val mixin =
            modUtils.enableMixins("org.spongepowered:mixin:0.8.3", "mixins.${archives_base_name}.refmap.json") as String
        api(mixin) {
            isTransitive = true
        }
        annotationProcessor("org.ow2.asm:asm-debug-all:5.2")
        annotationProcessor("com.google.guava:guava:24.1.1-jre")
        annotationProcessor("com.google.code.gson:gson:2.8.6")
        annotationProcessor(mixin) {
            isTransitive = false
        }
    }
}

// Adds Access Transformer files to tasks
@Suppress("Deprecation")
if (use_access_transformer.toBoolean()) {
    for (at in sourceSets.getByName("main").resources.files) {
        if (at.name.lowercase().endsWith("_at.cfg")) {
            tasks.deobfuscateMergedJarToSrg.get().accessTransformerFiles.from(at)
            tasks.srgifyBinpatchedJar.get().accessTransformerFiles.from(at)
        }
    }
}

@Suppress("UnstableApiUsage")
tasks.withType<ProcessResources> {
    // This will ensure that this task is redone when the versions change
    inputs.property("version", mod_version)
    inputs.property("mcversion", minecraft.mcVersion)

    // Replace various properties in mcmod.info and pack.mcmeta if applicable
    filesMatching(arrayListOf("mcmod.info", "pack.mcmeta")) {
        expand(
            "version" to mod_version,
            // NOTE: minecraft.mcVersion is a Property<String>; without .get() the template
            // would expand to the literal "extension 'minecraft' property 'mcVersion'".
            "mcversion" to minecraft.mcVersion.get()
        )
    }

    if (use_access_transformer.toBoolean()) {
        rename("(.+_at.cfg)", "META-INF/$1") // Make sure Access Transformer files are in META-INF folder
    }
}

tasks.withType<Jar> {
    manifest {
        val attributeMap = mutableMapOf<String, String>()
        if (use_coremod.toBoolean()) {
            attributeMap["FMLCorePlugin"] = coremod_plugin_class_name
            if (include_mod.toBoolean()) {
                attributeMap["FMLCorePluginContainsFMLMod"] = true.toString()
                attributeMap["ForceLoadAsMod"] = (project.gradle.startParameter.taskNames[0] == "build").toString()
            }
        }
        if (use_access_transformer.toBoolean()) {
            attributeMap["FMLAT"] = archives_base_name + "_at.cfg"
        }
        attributes(attributeMap)
    }
    // Add all embedded dependencies into the jar
    from(provider {
        configurations.getByName("embed").map {
            if (it.isDirectory()) it else zipTree(it)
        }
    })
}

idea {
    module {
        inheritOutputDirs = true
    }
    project {
        settings {
            runConfigurations {
                add(Gradle("1. Run Client").apply {
                    setProperty("taskNames", listOf("runClient"))
                })
                add(Gradle("2. Run Server").apply {
                    setProperty("taskNames", listOf("runServer"))
                })
                add(Gradle("3. Run Obfuscated Client").apply {
                    setProperty("taskNames", listOf("runObfClient"))
                })
                add(Gradle("4. Run Obfuscated Server").apply {
                    setProperty("taskNames", listOf("runObfServer"))
                })
            }
            compiler.javac {
                afterEvaluate {
                    javacAdditionalOptions = "-encoding utf8"
                    moduleJavacAdditionalOptions = mutableMapOf(
                        (project.name + ".main") to tasks.compileJava.get().options.compilerArgs.joinToString(" ") { "\"$it\"" }
                    )
                }
            }
        }
    }
}

tasks.named("processIdeaSettings").configure {
    dependsOn("injectTags")
}

// One-shot bootstrap: the 32 compass frames and their per-frame models are generated from the
// reference mod jar so the shipped assets are plain files under src/. Not wired into build;
// run it explicitly only when the frames need to be re-imported.
tasks.register("bootstrapCompassTextures") {
    doLast {
        val refDir = file(".tools/wts-inspect/decompiled/assets/wheresthatstructure/textures/item")
        val texDir = file("src/main/resources/assets/rlcompass/textures/item")
        val modelDir = file("src/main/resources/assets/rlcompass/models/item")
        texDir.mkdirs()
        modelDir.mkdirs()
        File(texDir, ".gitkeep").delete()

        refDir.listFiles { f -> f.name.endsWith(".png") }?.forEach { f ->
            f.copyTo(File(texDir, f.name.replace("explorerscompass_", "explorers_compass_")), overwrite = true)
        }

        for (k in 1..31) {
            val nn = k.toString().padStart(2, '0')
            File(modelDir, "explorers_compass_$nn.json").writeText(
                "{\n" +
                    "    \"parent\": \"item/generated\",\n" +
                    "    \"textures\": {\n" +
                    "        \"layer0\": \"rlcompass:item/explorers_compass_$nn\"\n" +
                    "    }\n" +
                    "}\n"
            )
        }

        val overrides = (1..31).joinToString(",\n") { k ->
            val nn = k.toString().padStart(2, '0')
            // k/32 written out exactly: 1/32 = 0.03125, so k*3125 is k/32 in hundred-thousandths.
            val threshold = "0." + (k * 3125).toString().padStart(5, '0')
            "        { \"predicate\": { \"angle\": $threshold }, \"model\": \"rlcompass:item/explorers_compass_$nn\" }"
        }
        File(modelDir, "explorers_compass.json").writeText(
            "{\n" +
                "    \"parent\": \"item/generated\",\n" +
                "    \"textures\": {\n" +
                "        \"layer0\": \"rlcompass:item/explorers_compass_00\"\n" +
                "    },\n" +
                "    \"overrides\": [\n" +
                overrides + "\n" +
                "    ]\n" +
                "}\n"
        )
        println("compass frames + models generated")
    }
}
