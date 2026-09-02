// The library build. ZERO runtime dependencies (ADR-0020): the dependencies block below
// holds ONLY testImplementation, and CI greps this file for implementation(/api( — the
// go.sum-must-not-exist gate, translated. A runtime dependency is a supply-chain decision
// the user owns: ask, don't add. There is deliberately no maven-publish plugin: Maven
// Central publication is the reserved release decision; do not scaffold it "for later".
plugins {
    `java-library`
}

group = "com.fortressflag"
version = "0.1.0"

java {
    toolchain {
        // The FLOOR toolchain. CI's second leg overrides via -PjavaToolchain to run the
        // newest LTS; release=17 below is what actually pins the bytecode floor.
        languageVersion = JavaLanguageVersion.of(
            (findProperty("javaToolchain") as String?)?.toInt() ?: 17
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // --release, not source/target: it compiles against the 17 API, so a 25 toolchain
    // cannot smuggle in a newer stdlib call.
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Werror")
    options.compilerArgs.add("-Xlint:all,-processing")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}
