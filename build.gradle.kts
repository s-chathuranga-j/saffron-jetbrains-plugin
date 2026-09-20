plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "ai.saffron"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Do not generate DefaultImpls bridges for platform interfaces
        // (ToolWindowFactory): the verifier reports them as calls into
        // deprecated and experimental methods.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.textmate")
        plugins(providers.gradleProperty("lsp4ijVersion").map { listOf("com.redhat.devtools.lsp4ij:$it") })
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "ai.saffron.jetbrains"
        name = "Saffron"
        version = providers.gradleProperty("pluginVersion")
        description = providers.fileContents(layout.projectDirectory.file("DESCRIPTION.html")).asText
        changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.html")).asText
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = "Chathuranga Jayasinghe"
            email = "genius.chathuranga@gmail.com"
            url = "https://saffron-ai.io"
        }
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
            // ./gradlew verifyPlugin -PlocalIde="/path/to/IntelliJ IDEA.app" checks the IDE you actually use.
            providers.gradleProperty("localIde").orNull?.let { local(it) }
        }
    }
}

tasks {
    runIde {
        // Dev sandbox only: skip the trust/first-run dialogs so a project
        // path passed via --args opens straight away.
        jvmArgs("-Didea.trust.all.projects=true", "-Didea.initially.ask.config=never")
    }
}
