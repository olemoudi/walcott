# Fase 0 — Resultados del spike Device Owner (2026-07-06)

Validado sobre AVD `walcott-spike` (Pixel 6, Android 15 / API 35, google_apis x86_64),
provisionado con `adb shell dpm set-device-owner dev.walcott/.WalcottAdminReceiver`.

## Criterios de éxito — todos cumplidos

| Pilar | Resultado |
|---|---|
| Provisionado Device Owner | ✅ `dpm set-device-owner` OK; la app reporta `isDeviceOwnerApp=true` |
| Bloqueo de apps (`setPackagesSuspended`) | ✅ Chrome suspendido (`dumpsys`: `suspended=true`); al abrirlo, diálogo de sistema que impide el acceso; reactivación OK |
| Restricciones anti-tamper | ✅ `DISALLOW_ADD_USER` verificado funcionalmente (`pm create-user` → "no_add_user is enabled"); safe-boot, factory-reset y auto-time aplicados sin error |
| Lectura de uso (`UsageStatsManager`) | ✅ Segundos de foreground por app en la última hora |

## Aprendizajes para las siguientes fases

1. **Paquetes no suspendibles.** El sistema rechaza suspender paquetes críticos
   (Settings, launcher, dialer…): `setPackagesSuspended` los devuelve en la lista de
   fallidos sin lanzar excepción. El enforcement debe tratar esa lista como resultado
   normal, y esos paquetes deben ir a `essentialPackages` por defecto.
2. **El diálogo de suspensión por defecto es corporativo** ("Blocked by work policy /
   contact your IT admin") — inaceptable para un niño. Fase 1: usar `SuspendDialogInfo`
   (API 28+) con mensaje propio y botón "pedir más tiempo" (acción del botón neutral).
3. **El appop `GET_USAGE_STATS` se perdió una vez** (volvió a `default` tras el
   provisionado). El asistente de configuración post-provisioning debe comprobar y
   pedir el permiso de Usage Access, no asumirlo concedido.
4. **Provisionado con cuentas transitorias.** El primer `set-device-owner` falló con
   "already some accounts on the device" aunque `dumpsys account` mostraba 0 cuentas
   (cuenta efímera de GMS durante el primer arranque). El reintento funcionó. La guía
   de provisionado debe indicar: si falla, esperar un minuto y reintentar.
5. `dumpsys user` no lista las restricciones puestas por el DO donde uno esperaría;
   verificar funcionalmente (p. ej. `pm create-user`) o vía `dumpsys device_policy`.

## Reproducir

Ver README (sección "Spike Fase 0"). El AVD queda provisionado; para re-arrancarlo:
`emulator -avd walcott-spike -no-window -no-audio -gpu swiftshader_indirect`.
