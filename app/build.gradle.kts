plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.agustin.sumi"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.agustin.sumi"
        minSdk = 23
        targetSdk = 37
        testInstrumentationRunner = "com.agustin.sumi.data.local.document.SafDocumentSourceTest"
    }

    buildFeatures {
        compose = true
    }

    sourceSets.named("test") {
        kotlin.directories.add("src/test/standalone")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.room.testing)

    constraints {
        implementation(libs.serialization.bom) {
            because("Room testing requires compatible JSON and core runtimes in the app and test APKs")
        }
    }
}
