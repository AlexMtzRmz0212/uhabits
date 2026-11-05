plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

// Windows version numbers are x.y.z with a z up to 65535; CI passes its run number.
val appVersion = "1.0.${(findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0}"

compose.desktop {
    application {
        mainClass = "org.isoron.uhabits.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "My Habits"
            packageVersion = appVersion
            description = "My Habits desktop app (syncs with the phone app)"
            vendor = "My Habits"
            // Runtime modules the app needs; the check in the packaging notes lists how to re-verify.
            modules("java.sql", "java.naming", "jdk.unsupported")
            windows {
                menuGroup = "My Habits"
                shortcut = true
                iconFile.set(project.file("packaging/icon.ico"))
                perUserInstall = true // no administrator rights needed
                // Never change: it is how a new MSI knows to upgrade the old install in place.
                upgradeUuid = "6f0b2d1e-4c1a-4b7e-9a63-2d5c8f7a1e90"
            }
        }
    }
}

// The database migrations live in the core module. Bundle them so the app finds them on the
// classpath instead of in a folder relative to where it was started.
sourceSets {
    main {
        resources {
            srcDir("../uhabits-core/assets/main")
            exclude("fonts/**")
        }
    }
}

// A zip of the app image: unzip anywhere and run "My Habits.exe". Needs no installer tools.
tasks.register<Zip>("packagePortableZip") {
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("my-habits-desktop-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
}

dependencies {
    implementation(project(":uhabits-core"))
    implementation(libs.sqlite.jdbc)
    // sqlite-jdbc logs through SLF4J; without a binding it prints a warning on every start.
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}
