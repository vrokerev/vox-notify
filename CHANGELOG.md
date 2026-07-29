# Changelog

## 1.0.2+3 - 2026-07-28

- Verificacion real de instalacion de Yape antes de activar la integracion inteligente.
- Listado de aplicaciones basado en paquetes instalados, detectados y selecciones guardadas.
- Diagnostico detallado del callback real del listener y prueba debug del NotificationListenerService.
- Mejoras de audio focus en Android 15 cuando el servicio foreground aun no esta confirmado.

## 1.0.1+2 - 2026-07-28

- Mejora del funcionamiento en segundo plano con servicio foreground persistente.
- Correcciones del rebind del listener de notificaciones y diagnosticos nativos.
- Diferenciacion entre preferencia de lectura continua y servicio realmente corriendo.
- Restauracion defensiva tras actualizacion/desbloqueo cuando la lectura continua esta activada.
- Mejoras de estabilidad para TextToSpeech y deduplicacion persistente.
- Nuevo icono oficial de VoxNotify generado desde `logo.png`.
- Mejoras de interfaz para estado, permisos, bateria y listado de aplicaciones.
- Confirmacion antes de eliminar el historial.
