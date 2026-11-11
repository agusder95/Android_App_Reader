import org.gradle.api.artifacts.VersionCatalogsExtension

buildscript {
    val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")

    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(catalog.findLibrary("kotlin-gradle-plugin").get())
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
