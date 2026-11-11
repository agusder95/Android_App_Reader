# SPEC-001 — Tareas

**Estado de SPEC-001: Approved.** T01 a T05 fueron ejecutadas y están pendientes de validación final del usuario.

| ID | Tarea | Depende de | Criterio | Verificación |
| --- | --- | --- | --- | --- |
| T01 | Comprobar que el JDK y el SDK disponibles cumplen la configuración aprobada. | — | Prerrequisito de CA-01 y CA-03 | Aplicar los criterios de T01 y registrar la evidencia. |
| T02 | Configurar el Gradle Wrapper, el Version Catalog y el único módulo Android `:app` con Kotlin. | Verificación inicial de T01 conforme | CA-01, CA-04 | Revisar los archivos del build y los identificadores, verificar Gradle y KGP, listar los módulos y compilar el APK de depuración. |
| T03 | Configurar Compose y Material 3 y agregar la pantalla inicial y el tema en los paquetes previstos. | T02 | CA-02, CA-04, CA-05 | Verificar el compilador, el BOM y las dependencias resueltas. Revisar los paquetes y comprobar que el APK instalado arranca y muestra Sumi, también sin conexión. |
| T04 | Documentar el entorno, los comandos y el arranque. | T03 | CA-03 | Revisar que los pasos sean suficientes para reproducir la configuración. |
| T05 | Validar el setup y presentar los resultados al usuario. | T04 | CA-01, CA-02, CA-03, CA-04, CA-05 | Ejecutar las verificaciones del [plan](plan.md) y registrar los resultados, incluida la evidencia para el cierre de T01. |

Los criterios están definidos en la [especificación](spec.md). El cierre requiere la aprobación final del usuario según [AGENTS.md](../../AGENTS.md).

## T01 — Criterios de aceptación

La configuración de referencia está registrada en el [stack técnico](../../docs/tech-stack.md#configuración-aprobada-para-spec-001). Los comandos de PowerShell usan `JAVA_HOME` y `ANDROID_HOME` para comprobar las instalaciones que usará el build. Ambas variables deben apuntar a directorios existentes.

| Archivos requeridos | Comando | Resultado esperado con la configuración aprobada |
| --- | --- | --- |
| `java.exe` y `javac.exe` dentro de `JAVA_HOME/bin` | `& "$env:JAVA_HOME/bin/java.exe" -version` y `& "$env:JAVA_HOME/bin/javac.exe" -version` | Ambos comandos terminan con código 0 y reportan la versión principal 21. |
| `platforms/android-37.0/android.jar` dentro del SDK | `Test-Path -LiteralPath "$env:ANDROID_HOME/platforms/android-37.0/android.jar" -PathType Leaf` | `True`. Corresponde a la plataforma base de Android 17 (API 37.0). |
| `build-tools/36.0.0/aapt2.exe` dentro del SDK | `Test-Path -LiteralPath "$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" -PathType Leaf` | `True`. |

**Verificación inicial.** Registrá las rutas, las versiones detectadas y el resultado de cada comando. Si todas las comprobaciones coinciden con la configuración aprobada, se cumple la precondición técnica de T02. Si alguna falla, registrá el impedimento y dejá T02 pendiente. T02 requiere su propia autorización.

**Cierre.** T01 se completa cuando la verificación inicial es conforme y T05 aporta evidencia de que `assembleDebug`, `testDebugUnitTest` y `lintDebug` se ejecutaron sin fallos. Un fallo de compilación, pruebas existentes o lint bloquea el cierre. Si el setup carece de lógica verificable, registrá la ausencia de pruebas y su justificación según CA-03.

La verificación inicial de T01 se limita al entorno existente. En **T02** se comprueban `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` y `gradle/wrapper/gradle-wrapper.properties`. Las verificaciones de Compose corresponden a **T03**.

### Primer intento — 11 de noviembre de 2026

**Bloqueada por el entorno.** El usuario autorizó únicamente T01. Las rutas de JDK y Android SDK están pendientes de configurar en el entorno de ejecución.

| Comprobación en PowerShell | Resultado |
| --- | --- |
| `Test-Path Env:JAVA_HOME` | `False`. `java.exe -version` y `javac.exe -version` quedaron sin ejecutar. La versión del JDK sigue sin comprobarse. |
| `Test-Path Env:ANDROID_HOME` | `False`. Las comprobaciones de `platforms/android-37/android.jar` y `build-tools/36.0.0/aapt2.exe` quedaron sin ejecutar. |
| `Get-Command java.exe, javac.exe, sdkmanager.bat, adb.exe -CommandType Application -All -ErrorAction SilentlyContinue` | La búsqueda diagnóstica no devolvió ejecutables en `PATH`. Tampoco se encontraron instalaciones utilizables en las ubicaciones habituales consultadas. |
| `Test-Path -LiteralPath ./gradlew.bat -PathType Leaf` y `Test-Path -LiteralPath ./app -PathType Container` | Ambos devolvieron `False`. `assembleDebug`, `testDebugUnitTest` y `lintDebug` quedan para el setup y la validación de T05. |

Para reintentar la verificación inicial, configurar `JAVA_HOME` con un JDK 21 y `ANDROID_HOME` con un SDK que incluya la plataforma API 37 y Build Tools 36.0.0. La precondición técnica de T02 y el cierre de T01 quedan pendientes.

### Reintento de la verificación inicial — 11 de noviembre de 2026

**Verificación inicial conforme.** Se encontraron las instalaciones requeridas. Las variables se configuraron solo en el proceso de PowerShell que ejecutó las comprobaciones. El SDK identifica Android 17 como el paquete base `platforms;android-37.0`. Se corrigió la ruta del chequeo de T01 para usar el nombre real de la carpeta.

- `JAVA_HOME` apuntó a `C:/Program Files/Java/jdk-21.0.12`. `java.exe -version` y `javac.exe -version` reportaron `21.0.12` y terminaron con código 0.
- `ANDROID_HOME` apuntó a `C:/Users/agust/AppData/Local/Android/Sdk`. Ambos comandos `Test-Path` de la tabla devolvieron `True`. Los metadatos del SDK confirmaron API `37.0` y Build Tools `36.0.0`.
- `assembleDebug`, `testDebugUnitTest` y `lintDebug` quedaron sin ejecutar. El Wrapper y el módulo `:app` se crean en T02, y la evidencia para el cierre de T01 corresponde a T05.

La precondición técnica de T02 se verificó. El cierre de T01 requiere la evidencia de T05 y la confirmación del usuario.

## T02 — Evidencia de ejecución del 11 de noviembre de 2026

Se generó el Wrapper 9.5.0 y se comprobaron los SHA-256 oficiales del ZIP y del JAR. El catálogo fija AGP 9.3.1 y KGP 2.4.21. El único módulo, `:app`, usa Kotlin integrado en AGP, `applicationId` y `namespace` iguales a `com.agustin.sumi`, `compileSdk` y `targetSdk` 37, `minSdk` 23 y Build Tools 36.0.0. [Checksums de Gradle](https://gradle.org/release-checksums/)

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat --version` | Gradle 9.5.0 y JDK 21.0.12. |
| `./gradlew.bat projects buildEnvironment :app:assembleDebug` | `:app` fue el único módulo. El classpath resolvió AGP 9.3.1 y KGP 2.4.21. La primera compilación generó `app/build/outputs/apk/debug/app-debug.apk`. |
| `aapt2 dump badging` y revisión del manifiesto combinado | El APK declara `com.agustin.sumi`, SDK de compilación 37 y SDK objetivo 37. El manifiesto combinado confirma el mínimo 23. |
| `./gradlew.bat :app:testDebugUnitTest` | Código 0 con `NO-SOURCE`. T02 aporta configuración y un manifiesto sin lógica de aplicación que probar. Se ejecutaron cero pruebas. |
| `./gradlew.bat :app:lintDebug` | Código 0 y dos advertencias. El manifiesto base carece de ícono y existe una versión de AGP posterior a la fijada en el stack. |

Se verificaron CA-01 y el módulo único de CA-04 para la base de T02. La entrada y el tema de CA-04, junto con las verificaciones de Compose y arranque, dependen de T03. T05 debe repetir las comprobaciones sobre el setup completo. T01 y T02 quedan pendientes de validación final.

### Reejecución de T02 — 11 de noviembre de 2026

Se revisaron el Wrapper, el catálogo, el módulo y el manifiesto base contra el stack aprobado y ADR-002. La implementación existente se reprodujo en una copia de los archivos de build sin salidas previas y con las dependencias ya almacenadas en el caché local.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat --version` | Código 0. Gradle 9.5.0 y JDK 21.0.12. |
| `./gradlew.bat --offline --no-daemon --console=plain projects buildEnvironment :app:assembleDebug` | El primer intento dentro del aislamiento no pudo conectarse al daemon local. El reintento fuera del aislamiento terminó con código 0 y 34 tareas ejecutadas. Gradle listó solo `:app`, resolvió AGP 9.3.1 y KGP 2.4.21, y generó `app-debug.apk`. La tarea `compileDebugKotlin` reportó `NO-SOURCE` en el módulo base. |
| `aapt2 dump badging` y lectura del manifiesto combinado | El APK confirmó `com.agustin.sumi`, `compileSdk` y `targetSdk` 37. El manifiesto combinado confirmó `minSdk` 23. |

La reejecución confirmó CA-01 y la parte de CA-04 aplicable a T02. Las pruebas y lint tienen evidencia de la primera ejecución y quedan por repetir en T05 sobre el setup completo. La entrada, el tema y el arranque siguen pendientes de T03. T02 queda pendiente de confirmación del usuario.

## T03 — Evidencia de ejecución del 11 de noviembre de 2026

Se confirmó la evidencia de T02 y se habilitó Compose en `:app`. El compilador de Compose usa la misma versión de Kotlin (`2.4.21`). La entrada `MainActivity` vive en `com.agustin.sumi`, el tema Material 3 vive en `core/ui/theme` y el nombre Sumi se obtiene de `res/values/strings.xml`. La única dependencia interna de la entrada es el tema compartido.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat --no-daemon --console=plain projects :app:dependencies --configuration debugRuntimeClasspath :app:assembleDebug` | Gradle resolvió Compose BOM `2026.09.00`, Material 3 `1.4.0` y Activity Compose `1.13.0`. La primera compilación se detuvo al agotar el heap predeterminado de 512 MiB. |
| Mismo comando con `--max-workers=2`, después de fijar el heap de Gradle en 2 GiB | Código 0 y `BUILD SUCCESSFUL`. Gradle listó solo `:app`, compiló Kotlin y generó `app/build/outputs/apk/debug/app-debug.apk`. Se comprobó la presencia del compilador Compose `2.4.21`. |
| `aapt2 dump badging` y revisión de paquetes e imports | El APK declara la etiqueta Sumi y la actividad de inicio `com.agustin.sumi.MainActivity`. No declara permiso de Internet. La entrada y el tema respetan el árbol y las dependencias internas de ADR-002. |
| `adb install -r` en `Medium_Phone_API_37.0` (Android 17, API 37) | `Success` en una instancia temporal del emulador. En el primer intento, ADB perdió la conexión antes de la prueba. El reintento usó un puerto ADB compatible. |
| `adb shell cmd connectivity airplane-mode enable`, `svc wifi disable`, `svc data disable` y `dumpsys connectivity` | Modo avión activo y `Active default network: none`. |
| `adb shell am force-stop com.agustin.sumi`, `adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.agustin.sumi/.MainActivity` y `adb shell uiautomator dump` | `Status: ok`, `LaunchState: COLD` y texto visible «Sumi» en la ventana de la app, con la conectividad desactivada. |

Se verificaron CA-02, CA-04 y CA-05 para T03. Quedan pendientes las pruebas unitarias y lint sobre el setup completo en T05, la comprobación recomendada de arranque en API 23 y la confirmación del usuario para validar T03.

## T04 — Evidencia de ejecución del 11 de noviembre de 2026

Se comprobó la precondición de T04 en el registro de T03 y en `.gradle/t03-rebuild.log`. El registro confirma que `:app:assembleDebug` terminó con `BUILD SUCCESSFUL` y que Sumi se instaló y arrancó sin conexión en `Medium_Phone_API_37.0` (Android 17, API 37). La guía de [entorno, compilación y arranque](../../README.md) documenta los prerrequisitos y comandos del setup en Windows.

| Comprobación | Resultado |
| --- | --- |
| Bloque de entorno del README | `java.exe -version` y `javac.exe -version` informaron `21.0.12`. Ambos `Test-Path` del SDK devolvieron `True`. `./gradlew.bat --version` informó Gradle `9.5.0`. |
| `./gradlew.bat --offline --no-daemon --console=plain --max-workers=2 :app:assembleDebug` | Código 0, `BUILD SUCCESSFUL`. Las 36 tareas estaban al día. Se usaron el caché local de Gradle y el APK existente. |
| `adb version` y `aapt2 dump badging` | ADB `37.0.1`. El APK confirmó la etiqueta Sumi y la actividad de arranque `com.agustin.sumi.MainActivity`. |
| Revisión del README | Se comprobaron la sintaxis de los tres bloques PowerShell y los destinos de los enlaces. |

Se completó la parte documental de CA-03. En T04 se revisó la evidencia de instalación y arranque de T03. La compilación desde un checkout limpio y la ejecución integral de pruebas unitarias y lint quedan para T05. El arranque recomendado en API 23 sigue pendiente. T04 queda pendiente de confirmación del usuario.

## T05 — Evidencia de ejecución del 11 de noviembre de 2026

Se confirmó la aprobación de SPEC-001 y la evidencia de T01 a T04. Como los archivos del setup aún están sin incorporar a Git, se copiaron los 13 archivos de configuración y código a `.gradle/t05-check-01`, sin salidas previas de compilación. Se comprobó que cada copia coincidía con su original mediante SHA-256. Las verificaciones usaron el caché local de Gradle y conservaron los cambios del árbol de trabajo.

| Comprobación | Resultado |
| --- | --- |
| `java.exe -version`, `javac.exe -version`, `Test-Path` y `./gradlew.bat --version` | JDK `21.0.12`, plataforma `37.0`, Build Tools `36.0.0` y Gradle `9.5.0`. |
| `./gradlew.bat :app:assembleDebug` | Código 0, `BUILD SUCCESSFUL`. Se ejecutaron 36 tareas desde la copia limpia y se generó `app-debug.apk`. |
| `./gradlew.bat :app:testDebugUnitTest` | Código 0, `NO-SOURCE`. Se descubrieron y ejecutaron cero pruebas. El setup contiene una pantalla estática y un tema, sin lógica de negocio ni fuentes de pruebas unitarias. |
| `./gradlew.bat :app:lintDebug` | Código 0, 0 errores y 1 advertencia `MissingApplicationIcon`. El APK de esta etapa carece de un ícono propio. |
| `./gradlew.bat projects buildEnvironment :app:buildEnvironment :app:dependencies --configuration debugRuntimeClasspath` | Código 0. Se confirmó el módulo único `:app`, AGP `9.3.1`, KGP y Compose Compiler `2.4.21`, BOM `2026.09.00`, Material 3 `1.4.0` y Activity Compose `1.13.0`. |
| `aapt2 dump badging` y lectura del manifiesto combinado | El APK confirmó `com.agustin.sumi`, `compileSdk` y `targetSdk` 37, `minSdk` 23, etiqueta Sumi y actividad de arranque `com.agustin.sumi.MainActivity`. El `namespace` coincide en `app/build.gradle.kts`. |
| `adb install -r` en `Medium_Phone_API_37.0` | `Success` en una instancia temporal de Android 17 (API 37). |
| `adb shell cmd connectivity airplane-mode enable`, `svc wifi disable`, `svc data disable`, `dumpsys connectivity`, `am force-stop`, `am start -W` y `uiautomator dump` | Modo avión activo y `Active default network: none`. La actividad de inicio informó `Status: ok` y `LaunchState: COLD`. La jerarquía de la ventana de la app mostró «Sumi». |

Los comandos de Gradle se ejecutaron por separado para compilación, pruebas y lint, con `--offline --no-daemon --console=plain --max-workers=2`. Los registros y el informe HTML de lint quedaron en la copia de verificación ignorada por Git.

- **CA-01:** configuración aprobada y compilación comprobadas desde una copia limpia de los archivos actuales. La reproducción desde un checkout de Git del setup quedará disponible cuando sus archivos estén versionados.
- **CA-02:** compilador, BOM, Material 3, instalación y pantalla inicial comprobados.
- **CA-03:** compilación, comando de pruebas y lint ejecutados y registrados. La guía de entorno y arranque está en el [README](../../README.md).
- **CA-04:** módulo único y estructura comprobados. La entrada vive en el paquete base, el tema en `core/ui/theme` y los directorios de código incorporados contienen fuentes.
- **CA-05:** se revisaron los imports y se comprobó el arranque en frío sin conexión. La entrada depende internamente del tema compartido.

El arranque recomendado en API 23 quedó pendiente porque `emulator -list-avds` solo listó `Medium_Phone_API_37.0` y el SDK solo tiene una imagen de sistema API 37. Con la evidencia de T05, T01 a T05 están listas para revisión y confirmación final del usuario. SPEC-001 sigue en `Approved`.

## Revisión final — 11 de noviembre de 2026

El usuario autorizó el cierre condicionado al cumplimiento de las verificaciones obligatorias. T01 a T05 fueron ejecutadas. El cierre queda pendiente por la evidencia de checkout exigida en CA-01.

| Criterio | Resultado de la revisión |
| --- | --- |
| CA-01 | Configuración aprobada y compilación confirmadas en la copia limpia de T05. Los 13 archivos necesarios existen en el proyecto principal y coinciden con la copia por SHA-256. Ninguno está en `HEAD`, por lo que un checkout limpio del repositorio aún carece del setup. |
| CA-02 | Cumplido. El informe de dependencias confirma Compose Compiler, BOM y Material 3. La evidencia de T05 registra la instalación y la pantalla «Sumi» en API 37. |
| CA-03 | Cumplido. Los tres registros confirman `BUILD SUCCESSFUL`. `assembleDebug` ejecutó 36 tareas, `testDebugUnitTest` informó `NO-SOURCE` con 0 pruebas y `lintDebug` informó 0 errores y una advertencia `MissingApplicationIcon`. El README documenta el entorno y el arranque. |
| CA-04 | Cumplido. Se confirmó el módulo único `:app`, la entrada en el paquete base y el tema en `core/ui/theme`. |
| CA-05 | Cumplido. La revisión de imports respetó ADR-002 y T05 registró un arranque en frío en API 37 sin red predeterminada. |

**Evidencia necesaria para cerrar.** Un checkout limpio de una revisión Git que contenga los archivos del setup y los prerrequisitos documentados, con el identificador de esa revisión y el registro de `./gradlew.bat :app:assembleDebug` exitoso y su APK generado.

**Pendientes que no bloquean.** El arranque recomendado en API 23 sigue sin comprobarse por falta de un emulador compatible. Se conserva la advertencia de lint por el ícono.

SPEC-001 y su entrada en el índice siguen en `Approved`. T01 a T05 permanecen pendientes de validación final.
