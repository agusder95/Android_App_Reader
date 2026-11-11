# SPEC-001 — Plan técnico

**Estado de SPEC-001: Approved.** Cada tarea requiere autorización antes de iniciarse. [Registro de aprobación](spec.md)

El plan aplica la arquitectura aprobada en [ADR-002](../../docs/decisions/ADR-002-package-architecture.md), los identificadores `com.agustin.sumi` y la [configuración aprobada del stack](../../docs/tech-stack.md#configuración-aprobada-para-spec-001).

## Enfoque

1. **Comprobar el entorno.** Verificar el JDK y el SDK con los [criterios de T01](tasks.md#t01--criterios-de-aceptación) antes de crear archivos del proyecto. El resultado conforme satisface la precondición técnica de T02. El cierre de T01 queda sujeto a las verificaciones obligatorias del build completo.
2. **Preparar la base.** En T02, configurar el Wrapper, el catálogo de versiones y el módulo `:app` con Kotlin. En T03, habilitar Compose, aplicar su compilador e incorporar el BOM y las dependencias autorizadas. Ubicar la entrada y la pantalla mínima de arranque en el paquete base, el tema en `core/ui/theme` y el nombre Sumi en los recursos.
3. **Documentar y verificar.** Registrar los prerrequisitos y probar el flujo desde un checkout limpio hasta el arranque de la app. En T05, registrar la evidencia de los tres comandos obligatorios para el cierre de T01 y los resultados del resto de los criterios.

## Verificaciones previstas

Comandos previstos para PowerShell.

| Criterio | Verificación |
| --- | --- |
| CA-01 | Revisar el catálogo, el Wrapper, los niveles de SDK y los identificadores en `:app`. Ejecutar `./gradlew.bat --version` para verificar Gradle y su JVM, y `./gradlew.bat buildEnvironment` para verificar la versión resuelta de KGP. Compilar desde un checkout limpio y revisar el manifiesto combinado para confirmar el identificador de aplicación, `minSdk` y `targetSdk`. |
| CA-02 | Revisar que el plugin Compose Compiler use la versión de Kotlin aprobada y que Material 3 envuelva la pantalla inicial. Ejecutar `./gradlew.bat :app:dependencies --configuration debugRuntimeClasspath` para comprobar el BOM resuelto y las dependencias Compose. Instalar el APK de depuración en un emulador o dispositivo compatible y comprobar que arranca y muestra Sumi. |
| CA-03 | Ejecutar por separado `./gradlew.bat :app:assembleDebug`, `./gradlew.bat :app:testDebugUnitTest` y `./gradlew.bat :app:lintDebug`. Registrar el resultado de cada comando y qué pruebas se ejecutaron. Si se reporta cero pruebas o `NO-SOURCE`, justificarlo según CA-03. Cualquier fallo de compilación, pruebas existentes o lint bloquea el cierre de T01 y de SPEC-001. |
| CA-04 | Ejecutar `./gradlew.bat projects` y confirmar que `:app` es el único módulo. Revisar las rutas y declaraciones de paquete contra el [árbol de referencia](../../docs/architecture.md#árbol-de-referencia) y comprobar que cada directorio de paquetes incorporado contiene archivos directamente o en sus subdirectorios. |
| CA-05 | Revisar imports y tipos expuestos en los archivos del setup contra la tabla de dependencias de [Arquitectura](../../docs/architecture.md). Desactivar la conectividad en el emulador o dispositivo y abrir Sumi desde un arranque en frío. |

Las dependencias del MVP se evaluarán en las especificaciones que las requieran. Consultá el orden y las dependencias de cada paso en [Tareas](tasks.md).
