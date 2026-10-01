# Note 10 DeX Mirror

Aplicación Android independiente y optimizada para **Samsung DeX** que permite reflejar la pantalla, transmitir audio en tiempo real y controlar de forma remota un **Samsung Galaxy Note 10** por WiFi a través de TCP directo y ADB (`scrcpy-server`).

---

## Características Principales

- **Streaming por TCP Directo a Alta Velocidad (60 FPS):**
  - Utiliza `scrcpy-server` (v1.20) y un puente TCP propio (`TcpBridge`) para evadir la sobrecarga de ADB en la transmisión de datos.
  - Decodificación por hardware de ultra baja latencia con Android `MediaCodec` (`vendor.low-latency.enable`, `KEY_PRIORITY = 0`).
  - Adaptabilidad de resolución sin reinicio del decodificador (`KEY_MAX_WIDTH` / `KEY_MAX_HEIGHT`).

- **Transmisión de Audio en Tiempo Real:**
  - Servidor de audio PCM incorporado en `TcpBridge` (`AudioRecord` con fuente `REMOTE_SUBMIX`).
  - Reproducción de baja latencia con `AudioTrack` y amplificación PCM ×3 para un volumen claro.

- **Integración Optimizada con Samsung DeX:**
  - **Ventana Bloqueada (Non-resizable):** `android:resizeableActivity="false"` evita deformaciones manuales o estiramientos no deseados en DeX.
  - **Preservación Estricta de Aspect Ratio:** Contenedor personalizado (`AspectRatioFrameLayout`) que calcula `onMeasure` con precisión matemática para que el video nunca se aplaste ni se distorsione.
  - **Cálculo Exacto de Ventana:** Compensa la altura de la barra de título nativa de Samsung DeX para que el área de contenido coincida píxel a píxel con la pantalla del Note 10 (cero bordes negros).
  - **Adaptación Automática de Orientación:** Al rotar entre vertical y horizontal (por ejemplo, al reproducir videos en YouTube a pantalla completa), la ventana de DeX se relanza dinámicamente con los bounds adecuados (`ActivityOptions.setLaunchBounds`).
  - **Sesión de Streaming Permanente (`StreamManager`):** La conexión WiFi ADB, el servidor scrcpy y el audio continúan activos sin desconexión al reasociar la `Surface` (`MediaCodec.setOutputSurface`).

- **Control Remoto Completo:**
  - Inyección de toques multitáctiles y scroll con ratón mapeados al 100% de la resolución del dispositivo objetivo.
  - Despertar de pantalla (`KEYCODE_WAKEUP`), desbloqueo automático (`wm dismiss-keyguard`) y modo siempre encendido (`stay_awake`).

---

## Estructura del Proyecto

```
note10_project/
├── AndroidManifest.xml          # Configuración de Activity, DeX metadata y permisos
├── assets/                      # scrcpy-server.jar, tcpbridge.jar y llaves ADB
├── bridge/                      # Código fuente de TcpBridge (Java para app_process)
├── res/                         # Diseños XML, drawables y colores
├── src/
│   ├── com/dex/note10/          # MainActivity, ScrcpyClient, VideoDecoder, AudioPlayer, etc.
│   └── com/tananaev/adblib/     # Librería ADB Java pura para autenticación y shell
└── build.sh                     # Script de compilación y empaquetado autónomo en Termux
```

---

## Compilación y Empaquetado

El proyecto se puede compilar directamente en Termux (o cualquier entorno Android con AAPT, D8 y Apksigner):

```bash
bash build.sh
```

El APK firmado generado se guardará en `/sdcard/Download/Note10.apk`.
