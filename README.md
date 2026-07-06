# Walcott — control parental familiar para Android

App de control parental construida para la familia, con dos principios que la diferencian
de las comerciales:

1. **Reglas inteligentes y dinámicas** — presupuestos por categoría y tipo de día
   (lectivo/finde/vacaciones), hora de dormir, tiempo extra bajo petición con aprobación
   del padre, y tiempo ganado por recompensas.
2. **Coste recurrente cero y configuración mínima** — sin servidor, sin cuentas, sin
   suscripciones: enforcement 100 % local vía Device Owner y sincronización por mensajes
   cifrados E2E (ntfy.sh) con emparejamiento por QR.

Plan de diseño completo: ver el plan aprobado del proyecto (arquitectura, fases, riesgos).

## Módulos

- `:core-rules` — motor de reglas: Kotlin puro, determinista, sin dependencias Android.
  Toda la lógica de presupuestos/ventanas/bedtime vive aquí, con tests unitarios.
- `:app` — app Android (Compose, minSdk 29). Actúa como DPC (Device Policy Controller):
  en el móvil del niño se provisiona como Device Owner.

## Entorno de desarrollo (WSL2)

Toolchain instalado en el home (sin sudo):

```bash
export JAVA_HOME=$HOME/.jdks/jdk-17.0.19+10
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
```

Compilar y testear:

```bash
./gradlew :core-rules:test        # tests del motor de reglas
./gradlew :app:assembleDebug      # APK en app/build/outputs/apk/debug/
```

## Spike Fase 0 — validar Device Owner en emulador

Requisito único: acceso a KVM (`sudo usermod -aG kvm $USER` y re-login, o
`sudo chmod 666 /dev/kvm` hasta el próximo reinicio).

```bash
# 1. Arrancar el emulador (AVD ya creado: walcott-spike, API 35 google_apis)
emulator -avd walcott-spike -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device

# 2. Instalar la app y provisionarla como Device Owner
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner dev.walcott/.WalcottAdminReceiver

# 3. Conceder el permiso de UsageStats (en producción: asistente post-provisioning)
adb shell appops set dev.walcott android:get_usage_stats allow

# 4. Abrir la app y probar desde su pantalla:
adb shell am start -n dev.walcott/.MainActivity
#    - "Suspender" un paquete y comprobar que no abre:
#      adb shell am start -n com.android.settings/.Settings  -> debe aparecer suspendido
#    - "Leer UsageStats" -> debe listar apps usadas
#    - "Aplicar restricciones" -> safe-boot/factory-reset/add-user bloqueados
```

Para des-provisionar el emulador durante el desarrollo:
`adb shell dpm remove-active-admin dev.walcott/.WalcottAdminReceiver` (solo funciona
en builds de debug con testOnly; alternativamente, wipe del AVD con `-wipe-data`).

## Provisionado de un móvil real (resumen)

En un móvil nuevo o tras factory reset: tocar 6 veces la pantalla de bienvenida →
escanear el QR de provisioning (apunta al APK release) → el sistema instala Walcott
como Device Owner. Sin ADB ni ordenador. Guía detallada: pendiente (Fase 2).
