# Arquitectura

**Estado: Approved.** Sumi usa Kotlin, Jetpack Compose y Material 3 con MVVM + Repository y Clean Architecture simplificada en un único módulo Gradle `:app`. El diseño offline-first usa fuentes locales para acceder a archivos, biblioteca, progreso y preferencias. Los catálogos online quedan fuera del MVP. [Producto](product.md) · [ADR-002](decisions/ADR-002-package-architecture.md)

## Árbol de referencia

```text
app/                                      # módulo Gradle :app
└── src/
    ├── main/
    │   ├── java/com/agustin/sumi/         # paquetes de código Kotlin
    │   │   ├── core/
    │   │   │   └── ui/
    │   │   │       └── theme/
    │   │   ├── domain/
    │   │   │   ├── model/
    │   │   │   ├── repository/
    │   │   │   └── usecase/
    │   │   ├── data/
    │   │   │   ├── repository/
    │   │   │   └── local/
    │   │   │       ├── document/
    │   │   │       ├── archive/
    │   │   │       ├── database/
    │   │   │       └── preferences/
    │   │   └── feature/
    │   │       ├── library/
    │   │       ├── reader/
    │   │       └── settings/
    │   └── res/                           # recursos Android
    ├── test/java/com/agustin/sumi/        # pruebas unitarias
    └── androidTest/java/com/agustin/sumi/ # pruebas instrumentadas
```

Las clases de entrada y ensamblado viven directamente en el paquete base `com.agustin.sumi`. Los directorios se materializan al incorporar archivos de una tarea autorizada.

## Responsabilidades y dependencias

| Paquete | Responsabilidad y dependencias internas permitidas |
| --- | --- |
| `core` | Tema y componentes Compose reutilizables. Independiente de `feature`, `domain` y `data`. |
| `feature` (Presentation) | Pantallas, estado y ViewModels agrupados por funcionalidad. Cada funcionalidad solo puede depender de su propio paquete, `core` y `domain`. La navegación entre funcionalidades se coordina desde el paquete base. |
| `domain` | Modelos, contratos de repositorio y reglas de negocio independientes de Android y de las demás capas. Incorporar un caso de uso cuando encapsule una regla o coordine operaciones. El ViewModel puede invocar directamente un contrato para operaciones simples. |
| `data` | Implementaciones de los contratos, adaptadores de archivos y persistencia, y conversión a modelos de dominio. Depende de `domain`. |
| Paquete base | Punto de entrada y ensamblado. Puede conectar las capas e inyectar implementaciones de datos detrás de contratos de dominio. |

Las dependencias de código se orientan hacia los contratos en `domain/repository`. La tabla define los límites que se revisan dentro de `:app`.

## Flujo de datos

1. La pantalla Compose envía acciones al ViewModel. El ViewModel invoca un contrato de repositorio directamente o mediante un caso de uso.
2. La implementación del repositorio consulta o actualiza las fuentes locales. Los adaptadores de datos resuelven el acceso a documentos SAF, la lectura CBZ y la persistencia.
3. El repositorio devuelve modelos o resultados de dominio. El ViewModel actualiza el estado observable y Compose representa ese estado.

**Pending:** elegir entre conservar el acceso al URI o copiar el archivo al almacenamiento de la app. La especificación de importación documentará la decisión y la recuperación cuando se pierda el acceso.
