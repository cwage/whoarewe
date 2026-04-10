plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// Drives scripts/e2e-pairing.sh against two attached devices.
// Build the debug APK first (or the script will tell you to):
//   ./gradlew :app:assembleDebug e2ePairing
// Override device serials with -PdeviceA=... -PdeviceB=...
tasks.register<Exec>("e2ePairing") {
    group = "verification"
    description = "Run end-to-end pairing test against two attached devices"
    dependsOn(":app:assembleDebug")
    workingDir = rootDir
    val args = mutableListOf("./scripts/e2e-pairing.sh")
    (findProperty("deviceA") as? String)?.let { args += it }
    (findProperty("deviceB") as? String)?.let { args += it }
    commandLine(args)
}
