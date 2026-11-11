# Especificaciones SDD

Creá un directorio `NNN-nombre-breve/` por especificación. Enlazá el contexto de `docs/` y mantené cada requisito y decisión en su documento de referencia.

| Archivo | Contenido |
| --- | --- |
| `spec.md` | Identificador, estado, objetivo, alcance, exclusiones, requisitos, criterios de aceptación verificables y decisiones pendientes. |
| `plan.md` | Enfoque técnico, decisiones aplicables, dependencias, orden de trabajo y estrategia de verificación. |
| `tasks.md` | Tareas acotadas con identificador, dependencias, criterio asociado, verificación y estado. |

## Estados y aprobación

1. **Draft.** Redactá los requisitos y marcá las decisiones abiertas como `Proposed` o `Pending`.
2. **Review.** Presentá el alcance, los criterios de aceptación y las decisiones relevantes para revisión del usuario.
3. **Approved.** Registrá la aprobación explícita del usuario en la especificación, con fecha y referencia. Para iniciar una tarea también se requiere su autorización explícita.
4. **In Progress.** Implementá las tareas autorizadas según el plan. Si cambia el alcance aprobado, actualizá los documentos afectados y volvé a `Review`.
5. **Validated.** Cerrá tras cumplir la Definition of Done de [AGENTS.md](../AGENTS.md), incluida la aprobación final del usuario. Registrá los resultados, los pendientes y la referencia a la aprobación.

La creación de estos documentos inicia la etapa `Draft`. La revisión del borrador y la autorización de tareas se registran por separado.

## Índice

- [SPEC-001 — Configuración inicial del proyecto Android](001-project-setup/spec.md) · **Approved**
