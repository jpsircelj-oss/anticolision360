# AntiColisión 360 V6 nativa Android

Primera rama nativa separada de la V5 web.

## V6.0 alpha 1

- Camera2 nativa con cámara trasera y análisis YUV de baja latencia.
- EfficientDet-Lite0 / TensorFlow Lite Task Vision para: persona, bicicleta, motocicleta, auto, bus y camión.
- Prioridad reforzada para peatón, bicicleta y motocicleta.
- Persistencia temporal de clase para reducir cambios bus/camión/auto.
- Detección visual de marcas de carril blancas o amarillas; solo se dibujan si su geometría/perspectiva es coherente.
- Clasificación IZQUIERDA / ADELANTE / DERECHA respecto del corredor detectado.
- Seguimiento temporal del tamaño aparente para evitar rojo cuando el vehículo de adelante conserva aproximadamente la distancia.
- GPS para velocidad y rumbo aproximado; IMU para orientación.
- Sonido solamente en riesgo rojo.
- Pantalla completa.

> Prototipo experimental. No sustituye atención del conductor, radar, AEB ni sistemas homologados.
