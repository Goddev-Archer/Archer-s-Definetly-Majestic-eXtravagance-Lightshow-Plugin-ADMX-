plugins {
    `java-library`
}

group = "de.archer.admx"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

val boilerStub by sourceSets.creating {
    java.srcDir("src/boiler-stub/java")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

dependencies {
    "boilerStubCompileOnly"("io.papermc.paper:paper-api:26.3.build.214-stable")

    compileOnly("io.papermc.paper:paper-api:26.3.build.214-stable")
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly(boilerStub.output)

    testImplementation("io.papermc.paper:paper-api:26.3.build.214-stable")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(boilerStub.output)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.register<Zip>("packageResourcePack") {
    group = "build"
    description = "Packt das ADMX Resource-Pack sauber ohne Duplikate oder verschachtelte Archive."
    archiveFileName.set("ADMX_ResourcePack.zip")
    destinationDirectory.set(layout.projectDirectory)
    from(layout.projectDirectory.dir("resourcepack")) {
        exclude("**/*.zip")
        exclude("assets/admx/items/item/**")
    }
}
