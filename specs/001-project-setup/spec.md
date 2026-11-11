# SPEC-001 — Configuración inicial del proyecto Android

**Estado: Approved.** El usuario aprobó SPEC-001 el 11 de noviembre de 2026 en el mensaje «Perfecto, podes pasar SPEC-001 a Approved». Las tareas requieren autorización por separado.

## Objetivo

Preparar una base Android reproducible para Sumi, con una pantalla inicial mínima que permita verificar el arranque en un emulador o dispositivo compatible.

## Requisitos y criterios de aceptación

| ID | Requisito | Criterio de aceptación |
| --- | --- | --- |
| CA-01 | Configurar un proyecto Android con Kotlin, Gradle Wrapper y Version Catalog. | El build usa las versiones y los niveles de SDK aprobados en el [stack técnico](../../docs/tech-stack.md#configuración-aprobada-para-spec-001). `applicationId` y `namespace` se declaran explícitamente como `com.agustin.sumi`. En un checkout limpio, con los prerrequisitos documentados, el Wrapper compila el APK de depuración. |
| CA-02 | Configurar Compose y Material 3 en el módulo de aplicación. | El compilador de Compose coincide con la versión de Kotlin y el BOM aprobado alinea las dependencias Compose. El APK de depuración se instala y arranca. La pantalla inicial muestra el nombre Sumi bajo un tema Material 3. |
| CA-03 | Ejecutar y documentar la compilación, las pruebas unitarias y Android Lint. | Se ejecutan `:app:assembleDebug`, `:app:testDebugUnitTest` y `:app:lintDebug` sin fallos. La entrega registra los prerrequisitos, comandos y resultados, los pasos de arranque y el dispositivo o emulador usado. Si el setup carece de lógica verificable y la tarea de pruebas reporta cero pruebas o `NO-SOURCE`, se registra ese resultado y se justifica la ausencia de pruebas. |
| CA-04 | Mantener un único módulo y respetar el árbol de paquetes de [Arquitectura](../../docs/architecture.md). | Gradle lista únicamente `:app`. La entrada y el ensamblado están en el paquete base, el tema compartido está en `core/ui/theme` y cada archivo incorporado ocupa la ubicación correspondiente a su responsabilidad. Los paquetes se agregan cuando tienen código. |
| CA-05 | Respetar los límites entre capas y el arranque offline-first. | Los imports y tipos expuestos por el código incorporado respetan las dependencias permitidas en Arquitectura. La pantalla inicial arranca sin conexión. |

## Límites y decisiones aprobadas

La configuración inicial cubre el entorno, la compilación y el arranque. La revisión arquitectónica se aplica a los archivos del setup. Los paquetes de dominio, datos y funcionalidades se incorporarán cuando los requieran sus propias especificaciones.

La arquitectura está aprobada en [ADR-002](../../docs/decisions/ADR-002-package-architecture.md). La selección de versiones se aprobó como parte de SPEC-001 tras la revisión documental de compatibilidad. Los fallos de compilación, pruebas existentes o lint bloquean el cierre de T01 y de la especificación.

[Plan técnico](plan.md) · [Tareas](tasks.md)
