import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
	alias(libs.plugins.loom)
	java
	id("com.gradleup.shadow")
}

base.archivesName = "bta-anywhere"
version = "0.1.0+bta8.0.1"

val shadowBundle = configurations.create("shadowBundle")
val buildOs = System.getProperty("os.name")
val buildArchitecture = System.getProperty("os.arch")
require(buildOs.startsWith("Windows") && buildArchitecture in setOf("amd64", "x86_64")) {
	"BTA Anywhere v0.1 builds require Windows x86-64; detected $buildOs/$buildArchitecture"
}
val lwjglNatives = "natives-windows"

loom {
	customMinecraftMetadata.set(
		"https://downloads.betterthanadventure.net/bta-client/${libs.versions.btaChannel.get()}/v${libs.versions.bta.get()}/manifest.json"
	)
}

repositories {
	ivy("https://piston-data.mojang.com") {
		patternLayout { artifact("v1/[organisation]/[revision]/[module].jar") }
		metadataSources { artifact() }
	}
}

dependencies {
	minecraft("::${libs.versions.bta.get()}")
	implementation(libs.loader)
	implementation(libs.halplibe)
	implementation(project(":tunnel-client"))
	shadowBundle(project(path = ":tunnel-client", configuration = "shadowElements"))

	compileOnly(libs.bundles.btaLwjgl)
	compileOnly(libs.joml)
	compileOnly(libs.joml.primitives)
	compileOnly(libs.slf4jApi)

	localRuntime(libs.modMenu)
	runtimeClasspath(libs.clientJar)
	localRuntime(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
	localRuntime("org.lwjgl:lwjgl::$lwjglNatives")
	localRuntime("org.lwjgl:lwjgl-glfw::$lwjglNatives")
	localRuntime("org.lwjgl:lwjgl-openal::$lwjglNatives")
	localRuntime("org.lwjgl:lwjgl-opengl::$lwjglNatives")
	localRuntime("org.lwjgl:lwjgl-stb::$lwjglNatives")

	testImplementation(platform(libs.junit.bom))
	testImplementation(libs.junit.jupiter)
	testRuntimeOnly(libs.junit.platform.launcher)
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
	options.encoding = "UTF-8"
	// BTA's merged game JAR mixes legacy classfile versions with newer nested libraries,
	// which produces unavoidable javac classfile warnings. Keep every source lint strict.
	options.compilerArgs.addAll(listOf("-Xlint:all,-classfile", "-Werror"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

tasks.processResources {
	val properties = mapOf(
		"version" to project.version,
		"fabricloader" to libs.versions.loader.get(),
		"halplibe" to libs.versions.halplibe.get(),
		"java" to libs.versions.java.get()
	)
	inputs.properties(properties)
	filesMatching(listOf("fabric.mod.json", "*.mixins.json")) {
		expand(properties)
	}
}

tasks.named<ShadowJar>("shadowJar") {
	configurations = listOf(shadowBundle)
	archiveClassifier = ""
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar {
	archiveClassifier = "thin"
}

tasks.assemble {
	dependsOn(tasks.shadowJar)
}

configurations.configureEach {
	exclude(group = "org.lwjgl.lwjgl")
	exclude(group = "net.java.jutils")
	exclude(group = "net.java.jinput")
	exclude(group = "net.sf.jopt-simple")
	exclude(group = "net.minecraft", module = "launchwrapper")
}
