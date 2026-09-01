// §6's walkthrough vehicle — a separate module so the library's dependency surface stays
// exactly one grep-able block. Run: ./gradlew :example:run
plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(
            (findProperty("javaToolchain") as String?)?.toInt() ?: 17
        )
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(rootProject)
}

application {
    mainClass = "com.fortressflag.server.example.Run"
}
