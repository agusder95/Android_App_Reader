# ADR-002 — Arquitectura de paquetes de Sumi

**Estado: Approved.** Aprobado por el usuario el 11 de noviembre de 2026 en la decisión 1 de la revisión de SPEC-001.

## Contexto

El MVP se centra en la lectura local, según [ADR-001](ADR-001-local-first.md). La presentación, las reglas de negocio y el acceso a datos deben poder evolucionar con responsabilidades claras dentro de una sola aplicación.

## Decisión

Usar Kotlin, Jetpack Compose y Material 3 con MVVM + Repository y Clean Architecture simplificada en un único módulo `:app`. Organizar el código en `core`, `domain`, `data` y `feature`, con Presentation agrupada por funcionalidad. Los contratos de dominio definen los límites entre capas y los casos de uso se incorporan cuando encapsulan lógica relevante. Las fuentes locales sostienen el flujo offline-first. Los catálogos online quedan fuera del MVP.

La aprobación de este ADR cubre la arquitectura objetivo. Las versiones del setup se registran en el [stack técnico](../tech-stack.md#configuración-aprobada-para-spec-001).

## Consecuencias

- El módulo único permite un setup Gradle acotado. La separación entre capas se verifica revisando paquetes e imports, según [Arquitectura](../architecture.md).
- El código de presentación puede usar implementaciones de prueba a través de los contratos de dominio. Los detalles de Android y persistencia quedan en la entrada de la app, la presentación y los adaptadores.
- [SPEC-001](../../specs/001-project-setup/spec.md) verifica la estructura de lo que incorpore el setup. Las especificaciones de funcionalidad aplicarán los mismos límites al agregar sus clases.
