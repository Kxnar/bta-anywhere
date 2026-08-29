pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.thesignalumproject.net/infrastructure") {
			name = "SignalumMavenInfrastructure"
		}
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bta-anywhere"

include("tunnel-client")
include("bta-mod")
