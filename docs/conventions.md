# Convenciones de desarrollo

## Kotlin y Compose

- Usá cuatro espacios. Nombrá clases y tipos en `PascalCase`, funciones y propiedades en `lowerCamelCase`, y constantes de compilación en `UPPER_SNAKE_CASE`. Usá identificadores en inglés y paquetes en minúsculas.
- Nombrá los composables que emiten UI en `PascalCase`, por ejemplo `LibraryScreen`. Pasá estado y callbacks explícitos a los composables de presentación. Mantené el acceso a repositorios en el ViewModel.
- Representá los estados relevantes de pantalla, como carga, contenido, vacío y error. Ubicá los textos visibles en recursos de Android.

## Ubicación y nombres de clases

Seguí el árbol y las dependencias de [Arquitectura](architecture.md). Agregá cada paquete cuando tenga su primer archivo.

| Tipo | Ubicación | Ejemplos |
| --- | --- | --- |
| Entrada, navegación global y ensamblado | Paquete base | `MainActivity`, `SumiNavHost` |
| Tema y componentes Compose compartidos | `core/ui/theme` y `core/ui` | `SumiTheme`, `LoadingIndicator` |
| Pantallas, ViewModels, estados y acciones | `feature/<funcionalidad>` | `LibraryScreen`, `LibraryViewModel`, `LibraryUiState`, `LibraryAction` |
| Modelos y contratos de dominio | `domain/model` y `domain/repository` | `Comic`, `LibraryRepository` |
| Casos de uso con lógica propia | `domain/usecase` | `ImportComicUseCase` |
| Implementaciones de repositorio | `data/repository` | `LocalLibraryRepository` |
| Adaptadores y modelos de almacenamiento | Subpaquete correspondiente de `data/local` | `SafDocumentSource` en `document`, `CbzArchiveReader` en `archive`, `ComicEntity` en `database`, `ReaderPreferencesSource` en `preferences` |

Usá el mismo paquete que la clase bajo prueba en `src/test` o `src/androidTest`, según corresponda.

## Errores

- Traducí fallas esperables de acceso o lectura a resultados que el ViewModel pueda convertir en mensajes y acciones concretas. Conservá el contexto técnico para diagnóstico y evitá registrar URIs o nombres de archivos del usuario. Propagá la cancelación de coroutines.

## Pruebas

- Cubrí la lógica de negocio con pruebas automatizadas apropiadas. Usá pruebas unitarias para reglas aisladas y pruebas de integración o de UI cuando el criterio dependa de Android o de la interacción entre capas.
- Nombrá la clase de prueba con el sufijo `Test`. Describí el escenario y el resultado esperado en cada prueba.
- Ejecutá las verificaciones de la especificación y registrá los comandos y resultados. Cuando una tarea carezca de lógica verificable y la ejecución reporte que no hay pruebas, documentá la ausencia y su justificación. Para el setup, aplicá la política de [SPEC-001](../specs/001-project-setup/spec.md#requisitos-y-criterios-de-aceptación).
