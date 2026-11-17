# Repository Guidelines

## Alcance y stack

El proyecto es un lector nativo de cómics y mangas para Android. El MVP se limita a la lectura de archivos CBZ locales. EPUB, PDF, CBR y los catálogos online son funcionalidades futuras del roadmap. No implementes funcionalidades futuras.

Usá Kotlin, Jetpack Compose y Material 3. Leé [docs/architecture.md](docs/architecture.md) antes de planificar o implementar cambios. Respetá las decisiones arquitectónicas aprobadas. Pedí aprobación antes de incorporar dependencias o modificar la arquitectura.

## Flujo SDD con Codex CLI

1. **Planificación.** Leé la documentación relevante de `docs/` y `specs/` antes de implementar. Identificá la especificación, la tarea, sus criterios de aceptación y las verificaciones previstas. Si falta la especificación, su aprobación o la autorización de la tarea, detenete en la planificación.
2. **Implementación.** Empezá solo con una especificación aprobada y una tarea autorizada. Mantené los cambios pequeños, verificables y dentro del alcance solicitado. Dejá los requisitos y las decisiones de diseño en sus documentos SDD.
3. **Verificación.** Escribí pruebas para la lógica relevante y ejecutá las verificaciones disponibles que apliquen al cambio. Registrá los comandos y sus resultados. Indicá qué pruebas quedaron sin ejecutar y por qué. Nunca informes como aprobada una prueba que no ejecutaste.

## Skills locales

Durante la planificación, consultá las guías que correspondan a la tarea.

- [android-clean-architecture](.agents/skills/android-clean-architecture/SKILL.md) para arquitectura, dominio, repositorios y ViewModels.
- [mobile-android-design](.agents/skills/mobile-android-design/SKILL.md) para UI, Jetpack Compose, Material 3 y accesibilidad.
- Consultá ambas si la tarea abarca presentación y alguna de las responsabilidades de arquitectura, como una pantalla Compose con su ViewModel.

Priorizá la documentación SDD aprobada, incluidos los ADR y la especificación aplicable. Aplicá únicamente recomendaciones compatibles con las decisiones aprobadas y dentro del alcance de la tarea autorizada. Si detectás un conflicto, informá la recomendación y la decisión afectada antes de continuar con el trabajo que dependa de resolverlo.

## Reglas de trabajo

- Obtené autorización antes de hacer commits, ejecutar `git push` o realizar operaciones destructivas.
- Respondé en español. Escribí el código y los nombres de clases y variables en inglés.

## Definition of Done

- Se cumplen los criterios de aceptación de la especificación aprobada.
- Las verificaciones requeridas se ejecutaron y pasaron.
- El informe de entrega enumera los archivos modificados, los resultados de las verificaciones y los problemas pendientes.
- El usuario revisó y dio su aprobación final para cerrar la tarea.
