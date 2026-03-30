plugins {
    application
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.soundcork.stockholm.backend.BackendApplication")
}
