// Contract mirror of ../m5stack-aq-parquet/docs/ble-sync-protocol.md: frame codec v1+v2, JSON models,
// CONFIG model + SET_CONFIG builder, LAN framing, Parquet structural verification. Pure JVM: no Android,
// no DI, so it runs in plain JUnit and can be reused by a desktop or hub tool unchanged.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
