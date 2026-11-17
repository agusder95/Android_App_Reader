# Stack técnico

| Estado | Tecnología | Uso previsto |
| --- | --- | --- |
| Approved | Kotlin | Lenguaje de la aplicación, establecido en `AGENTS.md`. |
| Approved | Jetpack Compose y Material 3 | UI nativa, establecida en `AGENTS.md`. |
| Approved | Storage Access Framework (SAF) | Selección de archivos locales, definida en el alcance del producto. |
| Approved | MVVM + Repository + Clean Architecture simplificada | Arquitectura offline-first de módulo único aprobada en [ADR-002](decisions/ADR-002-package-architecture.md). |
| Approved para SPEC-003 | Room | Metadatos de biblioteca local. El progreso por título se definirá en su especificación. |
| Proposed | DataStore | Preferencias de lectura. |
| Proposed | Coil | Carga de portadas e imágenes de página. |
| Proposed | Coroutines + Flow | Operaciones asíncronas y observación de estado. |
| Proposed | Navigation Compose | Navegación entre pantallas. |
| Proposed | `ZipFile` | Lectura de archivos CBZ. Su uso queda sujeto a la estrategia de acceso a documentos. |

## Identificadores aprobados

`applicationId` y `namespace` tienen el valor explícito `com.agustin.sumi`.

## Configuración aprobada para SPEC-001

**Estado: Approved.** El 11 de noviembre de 2026 se verificó documentalmente la compatibilidad de AGP, Gradle, JDK y Kotlin con las matrices oficiales, y se contrastaron las versiones y requisitos de Compose y Android SDK con la guía de Android Developers. La selección se aprobó como parte de [SPEC-001](../specs/001-project-setup/spec.md). La resolución efectiva de las dependencias y la ejecución del build se verificarán durante el setup.

| Elemento | Configuración y justificación |
| --- | --- |
| AGP y Gradle | **AGP 9.3.1** y **Gradle Wrapper 9.5.0**. AGP 9.3 admite API 37 y declara Gradle 9.5.0 como mínimo y predeterminado. Ambos están dentro del rango publicado para KGP 2.4.21. [AGP](https://developer.android.com/build/releases/agp-9-3-0-release-notes) · [Matriz de Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) |
| Kotlin y Compose Compiler | **Kotlin 2.4.21** con el soporte integrado de AGP. Fijar KGP 2.4.21 en el classpath del build raíz y aplicar `org.jetbrains.kotlin.plugin.compose` **2.4.21** en `:app`. El plugin `org.jetbrains.kotlin.android` queda excluido del setup. [Kotlin integrado](https://developer.android.com/build/migrate-to-built-in-kotlin) · [Versión de KGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin) · [Compose Compiler](https://kotlinlang.org/docs/compose-compiler-migration-guide.html) |
| JDK | **JDK 21** para ejecutar Gradle. AGP declara JDK 17 como mínimo, pero sus notas registran un fallo de lint con JDK 17 corregido en AGP 9.3.2. Gradle 9.5.0 admite JDK 21. [AGP](https://developer.android.com/build/releases/agp-9-3-0-release-notes) · [Matriz de Gradle](https://docs.gradle.org/current/userguide/compatibility.html#java) |
| Android SDK | **`compileSdk = 37`**, **`targetSdk = 37`** y **`minSdk = 23`**. API 37 satisface el requisito de compilación de Compose 1.12 y permite probar los comportamientos de Android 17. API 23 es el piso de soporte propuesto, alineado con AndroidX Activity. Verificarlo con las dependencias resueltas y el manifiesto combinado. Usar **SDK Build Tools 36.0.0**, el valor predeterminado de AGP 9.3. [Compose](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) · [Android 17](https://developer.android.com/about/versions/17/setup-sdk) · [AndroidX Activity](https://developer.android.com/jetpack/androidx/releases/activity) · [AGP](https://developer.android.com/build/releases/agp-9-3-0-release-notes#compatibility) |
| Jetpack Compose | Habilitar Compose en `:app`. Importar el **Compose BOM estable 2026.09.00** como plataforma y declarar `androidx.compose.material3:material3` sin versión individual. Declarar **`androidx.activity:activity-compose:1.13.0`** por separado. El BOM alinea las librerías Compose, mientras que el compilador sigue la versión de Kotlin. [Configuración de Compose](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) · [BOM](https://developer.android.com/develop/ui/compose/bom) |
| Version Catalog | Usar `gradle/libs.versions.toml` para las versiones de AGP, Kotlin, Compose BOM y Activity Compose, y para los alias de plugins y librerías. La distribución de Gradle se fija en `gradle/wrapper/gradle-wrapper.properties` y los niveles de SDK se declaran en `app/build.gradle.kts`. [Version Catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html) · [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) |

**Recomendaciones.** Verificar la distribución del Wrapper con su SHA-256 oficial y probar el arranque tanto en API 23 como en API 37. [Verificación del Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification)

## Pendiente de evaluación

- **Pending para la importación:** verificar en Android la viabilidad de las APIs ZIP estándar con el acceso SAF definido en [ADR-003](decisions/ADR-003-cbz-document-access.md) y los controles acotados del [plan de SPEC-002](../specs/002-cbz-import/plan.md#viabilidad-con-apis-estándar).

Las dependencias requieren aprobación antes de incorporarse. Aprobar una tecnología en este documento fija su uso previsto. La autorización de incorporación corresponde a la tarea que la necesite.

## Decisiones y candidatos de SPEC-003

| Estado | Tecnología | Decisión para la biblioteca |
| --- | --- | --- |
| Approved | Kotlin 2.4.21, AGP 9.3.1, Gradle 9.5.0 y JDK 21 | Conservar la base aprobada en SPEC-001 y el módulo único `:app`. El catálogo y el Wrapper coinciden con las versiones de Kotlin, AGP y Gradle. T01 debe alinear y verificar el daemon con JDK 21 sin registrar rutas personales. |
| Approved | APIs de Android y Compose Material 3 | Usar decodificación nativa para las miniaturas y la pantalla existente. Posponer Coil y Navigation Compose. |
| Approved | Room 2.8.5 | Persistencia de metadatos. Exportar el esquema JSON inicial de forma reproducible. Admite el mínimo API 23. [Notas de Room](https://developer.android.com/jetpack/androidx/releases/room). |
| Candidato autorizado | KSP2 2.3.12 | Sujeto a T03 con Kotlin integrado y Room 2.8.5. Generar el DAO y exportar el esquema. Ante incompatibilidad, detenerse y solicitar autorización antes de cambiar la versión. Evitar 2.3.13 por la regresión en ejecuciones concurrentes. [KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12) · [Regresión de KSP 2.3.13](https://github.com/google/ksp/releases/tag/2.3.13). |
| Approved | JUnit, AndroidX Test y `room-testing` 2.8.5 | JUnit en `testDebugUnitTest` y `AndroidJUnitRunner` en `connectedDebugAndroidTest`. T01 selecciona y documenta versiones compatibles de JUnit y AndroidX Test antes de incorporarlas y preserva las regresiones de SPEC-002. [AndroidJUnitRunner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner). |

Para T01 se seleccionaron `junit:junit:4.13.2`, `androidx.test:runner:1.7.0` y `androidx.test.ext:junit:1.3.0`. `AndroidJUnitRunner` se distribuye con `androidx.test:runner`. La línea estable de AndroidX Test seleccionada admite API 21 y cubre el mínimo API 23 de Sumi. Su compatibilidad efectiva se verifica al resolver y compilar la variante instrumentada. `room-testing` se incorpora en T03. [JUnit 4](https://junit.org/junit4/dependency-info.html) · [Versiones de AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test).
