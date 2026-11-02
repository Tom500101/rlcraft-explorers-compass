pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroup("com.gtnewhorizons.retrofuturagradle")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

// NOTE: org.gradle.toolchains.foojay-resolver-convention is deliberately NOT applied.
// Every published version (0.4.0 … 1.0.0) references JvmVendorSpec.IBM_SEMERU, a field
// Gradle 9 removed, so simply loading it during project evaluation aborts the build with
//     Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field … IBM_SEMERU
// Toolchains are therefore resolved from local JDK installations only — JDK 8 is declared
// through org.gradle.java.installations.paths in gradle.properties.

// Due to an IntelliJ bug, this has to be done
// rootProject.name = archives_base_name
rootProject.name = rootProject.projectDir.getName()
