plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "ai.saffron"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.textmate")
        plugins(providers.gradleProperty("lsp4ijVersion").map { listOf("com.redhat.devtools.lsp4ij:$it") })
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
            url = "https://saffron-ai.lovable.app"
        }
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
