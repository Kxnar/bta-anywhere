plugins {
	`java-library`
	application
	id("com.gradleup.shadow")
}

base.archivesName = "bta-anywhere-tunnel"

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	withSourcesJar()
}

application {
	mainClass = "io.github.kxnar.btaanywhere.cli.TunnelCli"
}

dependencies {
	api(libs.gson)
	implementation(libs.netty.handler)
	implementation(libs.netty.transport)
	implementation(libs.slf4j.legacy.nop)
	implementation("com.offbynull.portmapper:portmapper:2.0.6") {
		exclude(group = "org.slf4j", module = "slf4j-simple")
	}

	listOf(
		"linux-x86_64",
		"linux-aarch_64",
		"osx-x86_64",
		"osx-aarch_64",
		"windows-x86_64"
	).forEach { classifier ->
		implementation("io.netty.incubator:netty-incubator-codec-native-quic:${libs.versions.nettyQuic.get()}:$classifier") {
			exclude(group = "io.netty")
		}
	}

	testImplementation(platform(libs.junit.bom))
	testImplementation(libs.junit.jupiter)
	testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	systemProperty("btaAnywhereProtocolVectors", rootProject.layout.projectDirectory.dir("protocol/test-vectors").asFile)
	testLogging {
		events("failed", "skipped")
	}
}

tasks.jar {
	manifest.attributes["Main-Class"] = application.mainClass.get()
}

tasks.shadowJar {
	archiveClassifier = "all"
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/io.netty.versions.properties")
	relocate("io.netty", "io.github.kxnar.btaanywhere.shadow.io.netty")
	relocate("META-INF/native/libnetty", "META-INF/native/libio_github_kxnar_btaanywhere_shadow_netty")
	relocate("META-INF/native/netty", "META-INF/native/io_github_kxnar_btaanywhere_shadow_netty")
	relocate("com.google.gson", "io.github.kxnar.btaanywhere.shadow.com.google.gson")
	relocate("com.offbynull.portmapper", "io.github.kxnar.btaanywhere.shadow.com.offbynull.portmapper")
	relocate("org.apache.commons", "io.github.kxnar.btaanywhere.shadow.org.apache.commons")
	relocate("org.slf4j", "io.github.kxnar.btaanywhere.shadow.org.slf4j")
}

val shadowElements = configurations.create("shadowElements") {
	isCanBeConsumed = true
	isCanBeResolved = false
}

artifacts {
	add(shadowElements.name, tasks.shadowJar)
}

tasks.assemble {
	dependsOn(tasks.shadowJar)
}
