plugins { kotlin("jvm") version "2.0.21" }

repositories { mavenCentral() }

kotlin { jvmToolchain(17) }

sourceSets {
    main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("moe/matsuri/nb4a/po0/Po0Protocol.kt", "moe/matsuri/nb4a/po0/Po0Client.kt")
    }
    test {
        kotlin.srcDir("../../app/src/test/java")
        kotlin.include("moe/matsuri/nb4a/po0/**")
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.0.0-alpha.3")
}

tasks.test { testLogging { events("passed", "failed", "skipped") } }
