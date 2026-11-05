plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cada compilação no GitHub Actions gera um número de versão maior,
// assim o Android aceita instalar a nova versão por cima da anterior.
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.alef.assistentea"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alef.assistentea"
        minSdk = 26          // Android 8.0 ou superior
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Chave fixa de depuração guardada no repositório: todas as compilações
    // são assinadas com a mesma chave, então as atualizações instalam por cima
    // sem precisar desinstalar o app.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Reconhecimento de voz offline (Vosk) para esperar o "A" sem mexer na música
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
}
