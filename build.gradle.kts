plugins {
	base
	id("com.gradleup.shadow") version "9.6.1" apply false
	alias(libs.plugins.loom) apply false
}

allprojects {
	group = "io.github.kxnar.btaanywhere"
	version = "0.1.0"
	dependencyLocking {
		lockAllConfigurations()
	}

	repositories {
		mavenCentral()
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.danygames2014.net/signalum") { name = "SignalumMavenMirror" }
		maven("https://maven.thesignalumproject.net/infrastructure") {
			name = "SignalumMavenInfrastructure"
		}
		maven("https://maven.thesignalumproject.net/releases") {
			name = "SignalumMavenReleases"
		}
		maven("https://maven.thesignalumproject.net/nightly") {
			name = "SignalumMavenNightly"
		}
	}
}

subprojects {
	tasks.withType<Jar>().configureEach {
		from(rootProject.file("LICENSE")) {
			into("META-INF")
			rename { "LICENSE_bta-anywhere" }
		}
		from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
			into("META-INF")
			rename { "THIRD_PARTY_NOTICES_bta-anywhere.md" }
		}
	}
}

tasks.register("checkAll") {
	group = "verification"
	description = "Runs every Java verification task."
	dependsOn(":tunnel-client:check", ":bta-mod:check")
}
