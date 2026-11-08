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

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.214-stable")
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly(files("C:/dev/Boiler/api/build/libs/api-3.0.3.jar"))
    compileOnly(files("c:/dev/test-server/plugins/MapEngine-1.8.12-all.jar"))
    testImplementation("io.papermc.paper:paper-api:26.3.build.214-stable")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(files("C:/dev/Boiler/api/build/libs/api-3.0.3.jar"))
    testImplementation(files("c:/dev/test-server/plugins/MapEngine-1.8.12-all.jar"))
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

