## v1.0.5+15
- Corrige volumen inaudible al usar aplicaciones multimedia como Facebook, Instagram o YouTube: solicita foco de audio transitorio con atenuación (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), bajando automáticamente el volumen de los videos mientras anuncia la notificación y restableciéndolo al terminar.
- Corrige la desconexión del NotificationListenerService al cargar o tras periodos prolongados de inactividad: reduce intervalos del watchdog a 45s/30s y registra receptores de conexión/desconexión de cargador (`ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`) para reconectar automáticamente sin intervención manual.

## v1.0.3+13
- Corrige la migración de `ListView.cacheExtent` al nuevo API de Flutter.
- `scrollCacheExtent` ahora usa `const ScrollCacheExtent.pixels(500.0)`, que es el tipo requerido por Flutter 3.41+.
- Mantiene los arreglos de notificación de estado persistente, TTS como notificación, listener y UX de versiones anteriores.

## v1.0.3+12
- Corrige compilacion Kotlin de la notificacion persistente en Android (`setPriority`).
- Corrige el assertion de Flutter al expandir Aplicaciones para leer usando un `Material` real alrededor de los `ListTile`.
- Migra APIs de Flutter deprecadas: `cacheExtent` a `scrollCacheExtent` y `DropdownButtonFormField.value` a `initialValue`.
- Mantiene sin cambios la logica de notificacion persistente, audio como notificacion y recuperacion tras reinicio.

## 1.0.3+11

- Refuerza la notificacion de estado para que vuelva a aparecer automaticamente despues de reiniciar el telefono, sin abrir VoxNotify.
- Cambia a un nuevo canal `Estado permanente de VoxNotify` con importancia DEFAULT pero sin sonido, vibracion ni luces, para favorecer que HyperOS/Android muestre el icono en la barra de estado sin generar alertas molestas.
- Solicita `FOREGROUND_SERVICE_IMMEDIATE` en Android 12+ para evitar que el sistema difiera la visibilidad de la notificacion del foreground service durante el arranque.
- Mantiene la notificacion como `ongoing`, sin auto-cancel y sin boton Detener; tocarla solo abre VoxNotify.
- El BootReceiver restaura primero el foreground service aunque el acceso al Notification Listener este deshabilitado, permitiendo mostrar `VoxNotify necesita atencion` tras un reinicio.
- Si la lectura esta desactivada, el BootReceiver vuelve a publicar `VoxNotify esta desactivado` tras reiniciar en vez de dejar desaparecer el indicador.

## 1.0.3+10

- Elimina el boton `Detener` de la notificacion persistente para evitar apagados accidentales, especialmente en usuarios mayores.
- La notificacion queda como indicador de estado y al tocarla solo abre VoxNotify.
- Muestra `VoxNotify esta activo` mientras el servicio esta funcionando.
- Si el acceso a notificaciones se deshabilita, cambia a `VoxNotify necesita atencion` con una indicacion clara.
- Si el usuario desactiva manualmente la lectura continua desde la app, conserva una notificacion persistente `VoxNotify esta desactivado` que permite volver a la app tocandola.
- Las actualizaciones de estado usan `setOnlyAlertOnce`, por lo que no generan sonidos repetidos.

## 1.0.3+9

- Cambia la lectura TTS de audio multimedia a audio de notificacion (`USAGE_NOTIFICATION` + `CONTENT_TYPE_SPEECH`).
- Elimina `requestAudioFocus()` de la ruta de lectura: un rechazo de Audio Focus en Android 15/HyperOS ya no puede bloquear un Yape que ya fue recibido y parseado.
- El foreground service permanece siempre como `specialUse`; se elimina la promocion dinamica a `mediaPlayback` y su permiso asociado.
- Mantiene recuperacion automatica del motor TTS despues del boot.
- Agrega diagnosticos explicitos `speak_called`, `lastTtsSpeakCalledAt`, `ttsNotificationVolume`, `ttsUtteranceId` y `lastAudioFocusResult=not_requested_notification_audio`.
- Evita que dos Yapes muy seguidos se pisen durante el pequeno intervalo entre `TextToSpeech.speak()` y `onStart()`.

## 1.0.3+8

- Corrige una segunda ruta de audio detectada en el Xiaomi/HyperOS: +7 ya consigue Audio Focus, pero el TTS estaba reproduciendo como `USAGE_ASSISTANCE_ACCESSIBILITY` aunque VoxNotify no es un servicio de accesibilidad.
- Cambia la salida hablada a `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, alineada con el foreground service `mediaPlayback`.
- Cambia el foco de `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` a `AUDIOFOCUS_GAIN_TRANSIENT` para que la voz no compita con el sonido propio de Yape.
- Fuerza volumen TTS interno a 1.0 sin modificar el volumen global del usuario.
- Añade recuperación automática del motor TextToSpeech si falla durante el arranque temprano del teléfono; un fallo de TTS ya no deja VoxNotify mudo hasta recrear el listener.
- Añade diagnósticos de uso de audio, tipo de foco, motor TTS y volumen multimedia.

## 1.0.3+7

- Corrige la voz después de reiniciar en Android 15+: el log real confirmó `AUDIOFOCUS_REQUEST_FAILED` por `AS.HardeningEnforcer`.
- Mantiene `specialUse` al arrancar desde `BOOT_COMPLETED`, porque Android 15 impide iniciar directamente `mediaPlayback` desde ese broadcast.
- Justo antes de hablar, el foreground service ya activo se promueve temporalmente a `specialUse|mediaPlayback`, obteniendo la capacidad `FOREGROUND_AUDIO_CONTROL` requerida por Android.
- Al terminar la cola TTS, vuelve a `specialUse`.
- Agrega diagnósticos de promoción/restauración del tipo de foreground service.

# Changelog

## 1.0.3+5 - 2026-08-25

- Corrige el test Flutter desactualizado que seguía intentando crear `YapeNotifierApp` aunque la aplicación actual se llama `VoxNotifyApp`.
- Actualiza los mocks del widget test a los métodos y diagnósticos actuales de VoxNotify.
- Mantiene intactas las correcciones de listener, watchdog, rebind, arranque y TTS de 1.0.3+4.

## 1.0.3+4 - 2026-08-24

- Corrige el estado falso "Listener desactualizado" despues de 10 minutos.
- Agrega chequeo real periodico del NotificationListenerService usando activeNotifications.
- Agrega watchdog desde el servicio foreground para solicitar recuperacion si el listener deja de responder.
- En Android 14+ puede forzar un ciclo unbind/rebind cuando el listener queda aparentemente conectado pero congelado.
- Cambia el foreground service de mediaPlayback a specialUse para poder restaurarlo tras BOOT_COMPLETED en Android 15.
- Restaura lectura continua automaticamente al reiniciar, actualizar o desbloquear el telefono cuando la opcion estaba activada.
- Evita que un fallo al iniciar el foreground service desde TTS haga caer el listener.
- Mejora diagnosticos de TTS, salud del listener y autorreparacion en la pantalla principal.

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

## 1.0.3+6
- Simplifica la pantalla principal para usuarios no tecnicos.
- Muestra fallos criticos como alertas destacadas con acciones directas.
- Colapsa configuracion de segundo plano, apps, historial y diagnostico avanzado.
- Sustituye el selector segmentado de modo de lectura por un dropdown adaptado a pantallas angostas.
- Carga la lista de aplicaciones bajo demanda en lugar de cada vez que llega una notificacion.
- Reduce refrescos completos provocados por EventChannel; los eventos de runtime solo actualizan diagnostico/servicio y, cuando corresponde, historial.
- Agrupa eventos con debounce de 900 ms y evita refrescos solapados.
- Paraleliza las consultas de estado inicial y limita el historial visible a bloques de 20.
- Agrega RepaintBoundary en paneles principales para reducir repintado durante scroll.
