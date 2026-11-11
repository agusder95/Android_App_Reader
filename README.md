# Android_App_Reader

Sumi es una aplicación Android para leer cómics y mangas. La configuración inicial ofrece una pantalla de arranque que muestra el nombre de la app.

## Preparar el entorno en Windows

- Instalá un JDK 21 y, desde el SDK Manager de Android Studio, instalá Android 17 (API 37.0), Android SDK Build Tools 36.0.0 y Android SDK Platform-Tools. Para usar un emulador, necesitás también Android Emulator y un dispositivo virtual compatible. Aceptá las licencias de los componentes del SDK que uses.
- Para la prueba de arranque, iniciá un emulador o conectá un dispositivo Android con API 23 o superior y depuración USB habilitada. T03 se comprobó en el emulador `Medium_Phone_API_37.0`.
- El proyecto usa su propio Gradle Wrapper. La primera compilación requiere acceso a los repositorios para descargar Gradle y las dependencias. Las versiones de AGP, Kotlin y Compose están en el [stack aprobado](docs/tech-stack.md#configuración-aprobada-para-spec-001).

Desde la raíz del repositorio, configurá las rutas en PowerShell. Ajustá las rutas de ejemplo a tus instalaciones.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12'
$env:ANDROID_HOME = Join-Path $env:USERPROFILE 'AppData/Local/Android/Sdk'

& "$env:JAVA_HOME/bin/java.exe" -version
& "$env:JAVA_HOME/bin/javac.exe" -version
Test-Path -LiteralPath "$env:ANDROID_HOME/platforms/android-37.0/android.jar" -PathType Leaf
Test-Path -LiteralPath "$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" -PathType Leaf
.\gradlew.bat --version
```

Java y el compilador deben informar la versión principal 21, ambas comprobaciones del SDK deben devolver `True` y el Wrapper debe informar Gradle 9.5.0.

## Compilar y verificar

Ejecutá los comandos por separado desde la raíz y registrá el resultado de cada uno.

```powershell
.\gradlew.bat --max-workers=2 :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`. El informe HTML de lint queda en `app/build/reports/lint-results-debug.html`. Si Gradle informa cero pruebas o `NO-SOURCE`, registrá ese resultado y justificá la ausencia de pruebas.

## Instalar y abrir Sumi

Ejecutá `adb devices -l` con el ADB del SDK y usá el serial del dispositivo que figure como `device`. Reemplazá `emulator-5554` por ese serial.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb devices -l
$serial = 'emulator-5554'

& $adb -s $serial install -r 'app/build/outputs/apk/debug/app-debug.apk'
& $adb -s $serial shell am start -W -n com.agustin.sumi/.MainActivity
```

La instalación debe informar `Success` y la pantalla inicial debe mostrar «Sumi». Para comprobar el arranque sin conexión, activá el modo avión en el emulador de prueba y verificá que el Wi-Fi esté apagado. Ejecutá `& $adb -s $serial shell am force-stop com.agustin.sumi`, repetí el comando de arranque y comprobá que vuelva a mostrarse «Sumi».

Los criterios y resultados del setup se registran en [SPEC-001](specs/001-project-setup/spec.md) y sus [tareas](specs/001-project-setup/tasks.md).
