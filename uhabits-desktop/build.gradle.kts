plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.isoron.uhabits.desktop.MainKt")
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
