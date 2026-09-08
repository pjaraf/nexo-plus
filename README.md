# Nexo TV (`nexo-plus`)

App Android TV con VLC, sesión guardada y actualización automática.

## Actualizaciones (GitHub Actions)

1. Cada **push a `main`** (o “Run workflow”) ejecuta [.github/workflows/build-and-release.yml](.github/workflows/build-and-release.yml).
2. Compila la APK, sube un **GitHub Release** (`app-release.apk` + `version.json`).
3. En los dispositivos, la app consulta el último `version.json` y ofrece **Actualizar**.

> El repositorio debe ser **público** para que las TVs puedan descargar la APK sin token.

## Desarrollo local

```bash
gradle assembleRelease
```

Keystore de firma (CI): `debug.keystore` con alias `androiddebugkey` / `android`.
