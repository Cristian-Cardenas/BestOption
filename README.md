# BO2 Overlay MVP

Aplicación Flutter para Android que muestra **"BO2 está activo"** sobre otras aplicaciones mediante un servicio nativo persistente.

## Ejecución

```bash
flutter pub get
flutter run
# verificación de compilación
flutter build apk --debug
```

Al pulsar **Conceder permiso**, Android abre la pantalla de acceso especial `Mostrar sobre otras aplicaciones`. Activa el permiso y vuelve a BO2; después pulsa **Iniciar**. El mensaje tiene un botón **Detener** en la propia superposición y también se puede detener desde la app.

## Permisos y Android moderno

- `SYSTEM_ALERT_WINDOW` no es un permiso de runtime: el usuario debe concederlo explícitamente en Ajustes.
- Se usa `TYPE_APPLICATION_OVERLAY` en Android 8+.
- El overlay corre en un foreground service con notificación visible, requerido para ejecución persistente.
- El servicio declara `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_SPECIAL_USE` para Android moderno, con propiedad explicativa.
- En Android 13+ las notificaciones pueden requerir `POST_NOTIFICATIONS`; si se deniega, el servicio sigue sujeto a las políticas del sistema y el usuario debe revisar la notificación/ajustes.
- Android puede detener servicios, restringir overlays, ocultar el overlay en pantallas sensibles o aplicar restricciones de batería/fabricante.
- Este MVP no inicia automáticamente tras reinicio y no incluye una experiencia de producción (icono, accesibilidad o configuración avanzada).

El APK debug debe probarse en un dispositivo/emulador Android compatible; la compilación por sí sola no concede el permiso ni garantiza el comportamiento específico de cada fabricante.
