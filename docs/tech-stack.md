# Stack técnico

| Estado | Tecnología | Uso previsto |
| --- | --- | --- |
| Approved | Kotlin | Lenguaje de la aplicación, establecido en `AGENTS.md`. |
| Approved | Jetpack Compose y Material 3 | UI nativa, establecida en `AGENTS.md`. |
| Approved | Storage Access Framework (SAF) | Selección de archivos locales, definida en el alcance del producto. |
| Approved | MVVM + Repository + Clean Architecture simplificada | Arquitectura offline-first de módulo único aprobada en [ADR-002](decisions/ADR-002-package-architecture.md). |
| Proposed | Room | Catálogo local y progreso por título. |
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

- **Pending para la importación:** definir el mecanismo de acceso a CBZ desde SAF y validar la viabilidad de `ZipFile` con la estrategia elegida.

Las dependencias requieren aprobación antes de incorporarse. Aprobar una tecnología en este documento fija su uso previsto. La autorización de incorporación corresponde a la tarea que la necesite.
